package com.sanhua.marketingcost.service.quotefinal;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.integration.oa.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaGateway;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.costing.ProductCostingContextResolver;
import com.sanhua.marketingcost.service.costing.ProductCostingSuccessLookup;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActor;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaUserDirectory;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户确认冻结本轮成本；各步通过持久发送箱顺序执行，HTTP 不占用业务事务。 */
@Service
public class QuoteFinalSubmissionService {
  private com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy oaWorkflowAccess;
  @org.springframework.beans.factory.annotation.Autowired
  public void setOaWorkflowAccess(com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy policy) {
    this.oaWorkflowAccess = policy;
  }
  public record CostLine(long itemId, String productCode, long costVersionId, String versionNo,
      BigDecimal totalCost, BigDecimal quoteAmount, String sourceRevision, String technicalSourcesJson) {}
  public record Status(Long submissionId, String status, String step, List<CostLine> costs,
      String fingerprint, String error, String returnReason, boolean canConfirm) {}
  private final QuoteFinalSubmissionRepository repository;
  private final OaFormMapper forms;
  private final OaFormItemMapper items;
  private final ProductCostingContextResolver contexts;
  private final ProductCostingSuccessLookup successes;
  private final OaMessageRepository messages;
  private final OaMessageCodec codec;
  private final TechnicalDataOaGateway gateway;
  private final TechnicalDataOaUserDirectory users;
  private final TechnicalDataOaWorkflowRepository workflow;

  public QuoteFinalSubmissionService(QuoteFinalSubmissionRepository repository, OaFormMapper forms,
      OaFormItemMapper items, ProductCostingContextResolver contexts, ProductCostingSuccessLookup successes,
      OaMessageRepository messages, OaMessageCodec codec, TechnicalDataOaGateway gateway,
      TechnicalDataOaUserDirectory users, TechnicalDataOaWorkflowRepository workflow) {
    this.repository=repository; this.forms=forms; this.items=items; this.contexts=contexts;
    this.successes=successes; this.messages=messages; this.codec=codec; this.gateway=gateway;
    this.users=users; this.workflow=workflow;
  }

  // Readiness checks can legitimately reject missing input in their own transactions.
  // Do not wrap them in an outer transaction whose rollback would turn NOT_READY into HTTP 500.
  public Status status(String oaNo, String month, TechnicalDataActor actor) {
    var form = requireForm(oaNo, actor);
    String period = CostPricingPeriodUtils.requireCurrentPricingMonth(month);
    var submission = repository.latest(form.getId(), period);
    var oaState = oaWorkflowAccess.view(form.getId());
    if (oaState != null && !oaState.canCost()) return new Status(submission == null ? null : submission.id(), oaState.state(), null, List.of(), null, oaState.syncError(), oaState.reason(), false);
    if (submission != null && Set.of("PENDING","UNKNOWN","SUBMITTED").contains(submission.status())) return view(submission);
    try {
      var costs = currentCosts(form, period, actor.name());
      requireRecalculation(submission, costs);
      return new Status(submission == null ? null : submission.id(), submission == null ? "READY" : submission.status(),
          submission == null ? null : submission.step(), costs, codec.canonicalHash(costs),
          submission == null ? null : submission.error(), submission == null ? null : submission.returnReason(), gateway.enabled());
    } catch (IllegalArgumentException | IllegalStateException | com.sanhua.marketingcost.service.EffectiveTechnicalDataException error) {
      return new Status(submission == null ? null : submission.id(), submission == null ? "NOT_READY" : submission.status(),
          submission == null ? null : submission.step(), List.of(), null, error.getMessage(),
          submission == null ? null : submission.returnReason(), false);
    }
  }

  @Transactional
  public Status confirm(String oaNo, String month, String fingerprint, TechnicalDataActor actor) {
    var form = requireForm(oaNo, actor);
    var peer = gateway.peer();
    if (!peer.businessUnits().contains(form.getBusinessUnitType())) throw conflict("OA 无权接收本业务单元");
    String period = CostPricingPeriodUtils.requireCurrentPricingMonth(month);
    repository.lockForm(form.getId());
    oaWorkflowAccess.requireCosting(form.getId());
    var prior = repository.latest(form.getId(), period);
    if (prior != null && Set.of("PENDING","UNKNOWN","SUBMITTED").contains(prior.status())) {
      if (!Objects.equals(prior.fingerprint(), fingerprint)) throw conflict("已有本轮确认，请先核实原提交结果");
      var message = prior.messageId() == null ? null : messages.findById(prior.messageId());
      if (message != null && "FAILED".equals(message.status())) messages.retryOutgoing(message.id());
      return view(prior);
    }
    var costs = currentCosts(form, period, actor.name());
    requireRecalculation(prior, costs);
    if (!Objects.equals(codec.canonicalHash(costs), fingerprint)) throw conflict("核算版本发生变化，请刷新结果再确认");
    if (prior != null && "FAILED".equals(prior.status()) && prior.fingerprint().equals(fingerprint)) {
      enqueue(prior, prior.step());
      return view(repository.find(prior.id()));
    }
    var submission = repository.create(form.getId(), form.getOaNo(), period, prior == null ? 1 : prior.round()+1,
        peer, repository.document(form.getId(), peer), form.getBusinessUnitType(), fingerprint, codec.write(costs),
        actor.userId(), users.externalId(peer, actor.userId()));
    enqueue(submission, "QUOTE_STATUS");
    return view(repository.find(submission.id()));
  }

  /** 由发送箱在其回执事务内调用；成功一步再持久排队下一步。 */
  public void acceptReceipt(OaMessageRepository.Message message, TechnicalDataOaGateway.Receipt receipt) {
    var submission = repository.byMessage(message.id());
    if (submission == null || !submission.step().equals(message.interfaceType())) throw conflict("报价提交步骤与接口回执不一致");
    if (!receipt.accepted()) {
      repository.state(submission.id(), "FAILED", "OA 未接受本步骤：" + receipt.errorCode());
      return;
    }
    var result = receipt.result();
    if (!Objects.equals(result.path("documentId").asText(), submission.documentId())
        || !Objects.equals(result.path("accountingMonth").asText(), submission.month())
        || result.path("finalSubmissionId").asLong() != submission.id()
        || !Objects.equals(result.path("contentFingerprint").asText(), submission.fingerprint())) {
      throw conflict("OA 回执与本报价、月份或已确认内容不一致");
    }
    String step = submission.step();
    if ("QUOTE_STATUS".equals(step)) {
      String flow = OaMessageCodec.text(result, "externalFlowId", 128);
      String approver = OaMessageCodec.text(result, "approverExternalId", 128);
      String node = OaMessageCodec.text(result, "node", 24);
      if (!Set.of("DATA", "FINANCE").contains(node)) {
        repository.state(submission.id(), "FAILED", "OA 当前不在报价员资料或核算节点，请核实流程");
        return;
      }
      repository.bindFlow(submission.id(), flow, approver);
      enqueue(repository.find(submission.id()), "DATA".equals(node) ? "QUOTE_DATA_SUBMIT" : "QUOTE_RESULT_SAVE");
      return;
    }
    if (!Objects.equals(submission.flowId(), result.path("externalFlowId").asText())) throw conflict("OA 回执流程身份变化");
    switch (step) {
      case "QUOTE_DATA_SUBMIT" -> enqueue(submission, "QUOTE_RESULT_SAVE");
      case "QUOTE_RESULT_SAVE" -> enqueue(submission, "QUOTE_COST_SUBMIT");
      case "QUOTE_COST_SUBMIT" -> {
        repository.state(submission.id(), "SUBMITTED", null);
        messages.resumeQuoteEvents(submission.peer(), submission.id());
      }
      default -> throw conflict("未知报价提交步骤");
    }
  }

  public void unknown(OaMessageRepository.Message message, String reason) {
    var submission = repository.byMessage(message.id());
    if (submission != null) repository.state(submission.id(), "UNKNOWN", reason);
  }

  public Object returned(OaMessageRepository.Message message, JsonNode event) {
    String eventId = OaMessageCodec.text(event,"eventId",128);
    String prior = workflow.claimEvent(message.peer(),eventId,codec.canonicalHash(event),message.id());
    if (prior != null) return codec.read(prior);
    long id = positive(event,"finalSubmissionId");
    long sequence = positive(event,"sequence");
    var submission = repository.lock(id);
    if (submission == null || !message.peer().sourceSystem().equals(submission.peer().sourceSystem())
        || !message.peer().environment().equals(submission.peer().environment())
        || !message.peer().businessUnits().contains(submission.businessUnit())
        || !Objects.equals(submission.documentId(),event.path("documentId").asText())
        || !Objects.equals(submission.flowId(),event.path("externalFlowId").asText())
        || !Objects.equals(submission.approver(),event.path("operatorExternalId").asText())) throw conflict("退回事件与原报价流程、提交或审批人不一致");
    users.actor(message.peer(),submission.approver());
    if (Set.of("PENDING","UNKNOWN").contains(submission.status())) throw new OaWorkflowNotReadyException();
    String outcome;
    if (sequence <= submission.returnSequence() || "RETURNED".equals(submission.status())) outcome="IGNORED_OLD_RETURN";
    else {
      if (!"SUBMITTED".equals(submission.status())) throw conflict("该报价尚未成功提交，不能接收领导退回");
      repository.returned(id,sequence,OaMessageCodec.text(event,"reason",512));
      outcome="RETURNED_RECALCULATION_REQUIRED";
    }
    var result = Map.of("outcome",outcome,"finalSubmissionId",id);
    workflow.completeEvent(message.peer(),eventId,codec.write(result));
    return result;
  }

  private List<CostLine> currentCosts(OaForm form, String month, String operator) {
    var lines = items.selectList(Wrappers.<OaFormItem>lambdaQuery().eq(OaFormItem::getOaFormId,form.getId()).orderByAsc(OaFormItem::getId));
    if (lines.isEmpty()) throw conflict("报价单没有产品");
    List<CostLine> result = new ArrayList<>();
    for (var item : lines) {
      var context = contexts.resolveRevision(contexts.resolve(new ProductCostingRequest(form.getOaNo(),item.getId(),month,operator,false)));
      var version = successes.find(context).orElseThrow(() -> conflict("产品 " + context.productCode() + " 尚无与当前资料一致的成功核算，请重新核算")).version();
      result.add(new CostLine(item.getId(),context.productCode(),version.getId(),version.getVersionNo(),version.getTotalCost(),
          version.getFinalQuoteAmount(),version.getSourceRevision(),version.getTechDataInputJson()));
    }
    return List.copyOf(result);
  }
  private void requireRecalculation(QuoteFinalSubmissionRepository.Submission prior, List<CostLine> costs) {
    if (prior == null || !"RETURNED".equals(prior.status())) return;
    Set<Long> oldVersions = new HashSet<>();
    codec.read(prior.snapshotJson()).forEach(row -> oldVersions.add(row.path("costVersionId").longValue()));
    if (costs.stream().anyMatch(line -> oldVersions.contains(line.costVersionId()))) throw conflict("领导已退回，请先重新核算各产品再确认");
  }
  private void enqueue(QuoteFinalSubmissionRepository.Submission submission, String step) {
    Map<String,Object> payload = new LinkedHashMap<>();
    payload.put("finalSubmissionId",submission.id()); payload.put("documentId",submission.documentId());
    payload.put("accountingMonth",submission.month()); payload.put("externalFlowId",submission.flowId());
    payload.put("operatorExternalId",submission.operatorExternalId()); payload.put("contentFingerprint",submission.fingerprint());
    payload.put("costs",codec.read(submission.snapshotJson()));
    String requestId = "quote-final-"+submission.id()+"-"+step+"-"+(submission.stepAttempt()+1);
    if ("QUOTE_COST_SUBMIT".equals(step)) payload.put("resultSubmissionId", requestId);
    String raw = codec.write(Map.of("schemaVersion",1,"sourceSystem",submission.peer().sourceSystem(),"environment",submission.peer().environment(),
        "requestId",requestId,"occurredAt",OffsetDateTime.now().toString(),"payload",payload));
    var type = OaMessageCodec.InterfaceType.valueOf(step);
    var message = messages.enqueue(submission.peer(),type,codec.decode(raw,submission.peer(),type));
    repository.enqueue(submission.id(),step,message.id());
  }
  private Status view(QuoteFinalSubmissionRepository.Submission submission) {
    return new Status(submission.id(),submission.status(),submission.step(),List.of(),submission.fingerprint(),submission.error(),submission.returnReason(),false);
  }
  private OaForm requireForm(String oaNo, TechnicalDataActor actor) {
    if (actor == null || actor.shortSession() || !actor.has("ingest:quote:cost-run:execute")) throw conflict("无权确认本报价");
    var form = forms.selectOne(Wrappers.<OaForm>lambdaQuery().eq(OaForm::getOaNo,oaNo));
    if (form == null || !Objects.equals(form.getBusinessUnitType(),BusinessUnitContext.getCurrentBusinessUnitType())) throw conflict("报价单不存在或不属于当前业务单元");
    return form;
  }
  private static long positive(JsonNode event,String name) {
    var value=event.path(name);
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue()<=0) throw conflict(name+" 必须为正整数");
    return value.longValue();
  }
  private static IllegalArgumentException conflict(String message) { return new IllegalArgumentException(message); }
}
