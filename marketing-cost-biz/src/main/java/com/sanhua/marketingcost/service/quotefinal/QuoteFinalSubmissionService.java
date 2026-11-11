package com.sanhua.marketingcost.service.quotefinal;

import com.sanhua.marketingcost.service.QuotePricingPeriod;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.integration.oa.*;
import com.sanhua.marketingcost.integration.oa.workflow.OaFinalCostSubmissionClient;
import com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.costing.ProductCostingContextResolver;
import com.sanhua.marketingcost.service.costing.ProductCostingSuccessLookup;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActor;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaContext;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 整单校验并固定成本 → 事务外发送一次 I07 → 保存回执。重复请求及未知结果不重发。 */
@Service
public class QuoteFinalSubmissionService {
  private final QuotePricingPeriod pricingPeriods;

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CostLine(long itemId, String productCode, Long costVersionId, String versionNo,
      String totalCost, String shippingFee, String sourceRevision, String technicalSourcesJson,
      String businessType, String sourceRowId, Integer sourceRowIndex, String status, String message) {}
  public record Status(Long submissionId, String status, String periodMonth, List<CostLine> costs,
      int totalProducts, int readyProducts, String fingerprint, String error, String returnReason, boolean canConfirm) {}
  private record Prepared(QuoteFinalSubmissionRepository.Submission submission, ObjectNode body, boolean send) {}
  private static final Set<String> FROZEN = Set.of("PENDING", "UNKNOWN", "SUBMITTED", "COMPLETED");
  private final QuoteFinalSubmissionRepository repository;
  private final OaFormMapper forms;
  private final OaFormItemMapper items;
  private final ProductCostingContextResolver contexts;
  private final ProductCostingSuccessLookup successes;
  private final OaMessageRepository messages;
  private final OaMessageCodec codec;
  private final ObjectMapper json;
  private final TechnicalDataOaContext oaContext;
  private final OaFinalCostSubmissionClient oa;
  private final OaWorkflowAccessPolicy access;
  private final TransactionTemplate transaction;

  public QuoteFinalSubmissionService(QuoteFinalSubmissionRepository repository, OaFormMapper forms,
      OaFormItemMapper items, ProductCostingContextResolver contexts, ProductCostingSuccessLookup successes,
      OaMessageRepository messages, OaMessageCodec codec, ObjectMapper json, TechnicalDataOaContext oaContext,
      OaFinalCostSubmissionClient oa, OaWorkflowAccessPolicy access, PlatformTransactionManager manager,
      QuotePricingPeriod pricingPeriods) {
    this.pricingPeriods = pricingPeriods;
    this.repository = repository; this.forms = forms; this.items = items; this.contexts = contexts;
    this.successes = successes; this.messages = messages; this.codec = codec; this.json = json;
    this.oaContext = oaContext; this.oa = oa; this.access = access;
    transaction = new TransactionTemplate(manager);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  /** 状态读取不包外层事务；单个产品资料不足时仍返回其他产品的有效金额。 */
  public Status status(String oaNo, String month, TechnicalDataActor actor) {
    var form = requireForm(oaNo, actor);
    var prior = repository.latest(form.getId());
    if (prior != null && FROZEN.contains(prior.status())) return frozen(prior);
    String period = pricingPeriods.requireActiveMonth(month, form.getBusinessUnitType());
    var costs = currentCosts(form, period, actor.name());
    String error = costs.stream().filter(c -> !"READY".equals(c.status())).map(CostLine::message).findFirst().orElse(null);
    try {
      if (costs.isEmpty()) throw invalid("报价单没有产品");
      access.requireCostPublication(form.getId());
      access.requireOaHandling(form.getId());
      if (!repository.linked(form.getId())) throw invalid("本单未关联 OA 原流程，可查看核算成本，暂不能提交 OA");
      if (error == null) preview(form, costs, oaContext.operatorEmployeeNo(actor.userId()));
    } catch (IllegalArgumentException | IllegalStateException failure) {
      if (error == null) error = failure.getMessage();
    }
    boolean ready = error == null;
    String state = prior == null ? ready ? "READY" : "NOT_READY" : prior.status();
    return view(prior, state, period, costs, fingerprint(form, period, costs), error != null ? error : prior == null ? null : prior.error(),
        prior == null ? null : prior.returnReason(), ready);
  }

  @com.sanhua.marketingcost.service.reprice.QuotationWrite(holdUntilCompletion=true)
  public Status confirm(String oaNo, String month, String fingerprint, String requestKey, TechnicalDataActor actor) {
    if (requestKey == null || !requestKey.matches("[A-Za-z0-9._-]{1,80}")) throw invalid("提交请求编号无效，请刷新后重试");
    if (fingerprint == null || !fingerprint.matches("[a-f0-9]{64}")) throw invalid("请先核对本次成本结果");
    var form = requireForm(oaNo, actor);
    String period = pricingPeriods.requireActiveMonth(month, form.getBusinessUnitType());
    Prepared prepared = transaction.execute(tx -> prepare(form, period, fingerprint, requestKey, actor));
    if (!prepared.send()) return frozen(prepared.submission());
    OaWorkflowResult result;
    try {
      result = oa.submit(prepared.body());
    } catch (RuntimeException failure) {
      // 调用开始后的异常无法证明 OA 未办理，不允许通过重试按钮再次推进节点。
      result = new OaWorkflowResult(null, OaWorkflowResult.Status.UNKNOWN, null, "OA_DELIVERY_UNCONFIRMED",
          "OA 提交结果尚未确认，请核实原流程", null, null, 0);
    }
    OaWorkflowResult receipt = result;
    transaction.executeWithoutResult(tx -> {
      repository.lockForm(form.getId());
      repository.received(repository.lock(prepared.submission().id()), receipt, codec.write(receipt));
    });
    return status(oaNo, period, actor);
  }

  private Prepared prepare(OaForm form, String period, String expected, String key, TechnicalDataActor actor) {
    repository.lockForm(form.getId());
    String localRequest = "I07:" + actor.userId() + ":" + key;
    var repeated = repository.byRequest(localRequest);
    if (repeated != null) {
      if (repeated.formId() != form.getId() || !repeated.month().equals(period) || !repeated.fingerprint().equals(expected)) {
        throw invalid("同一提交编号不能用于另一份成本结果");
      }
      return new Prepared(repeated, null, false);
    }
    var prior = repository.latest(form.getId());
    if (prior != null && FROZEN.contains(prior.status())) return new Prepared(prior, null, false);
    access.requireCostPublication(form.getId());
    access.requireOaHandling(form.getId());
    var costs = currentCosts(form, period, actor.name());
    if (costs.isEmpty() || costs.stream().anyMatch(c -> !"READY".equals(c.status()))) {
      throw invalid("仍有产品尚未完成有效核算，请处理后重新确认");
    }
    if (!fingerprint(form, period, costs).equals(expected)) throw invalid("核算版本或原产品明细已变化，请刷新核对成本后再提交");
    String employeeNo = oaContext.operatorEmployeeNo(actor.userId());
    var document = oaContext.document(form.getId());
    var body = oa.preview(document.requestId(), employeeNo, document.processCode(), oaRows(costs));
    String raw = codec.write(Map.of("schemaVersion", 1, "sourceSystem", document.peer().sourceSystem(),
        "environment", document.peer().environment(), "requestId", localRequest, "occurredAt", OffsetDateTime.now().toString(),
        "payload", Map.of("documentId", document.requestId(), "request", body)));
    var type = OaMessageCodec.InterfaceType.QUOTE_COST_SUBMIT;
    long messageId = messages.enqueue(document.peer(), type, codec.decode(raw, document.peer(), type)).id();
    var submission = repository.create(form.getId(), form.getOaNo(), period, document.peer(), document.requestId(),
        form.getBusinessUnitType(), expected, codec.write(costs), actor.userId(), employeeNo, messageId);
    return new Prepared(submission, body, true);
  }

  private ObjectNode preview(OaForm form, List<CostLine> costs, String employee) {
    var document = oaContext.document(form.getId());
    return oa.preview(document.requestId(), employee, document.processCode(), oaRows(costs));
  }
  private List<OaFinalCostSubmissionClient.Row> oaRows(List<CostLine> costs) {
    return costs.stream().map(c -> new OaFinalCostSubmissionClient.Row(
        c.sourceRowIndex(), c.totalCost(), c.shippingFee(), c.businessType())).toList();
  }
  private List<CostLine> currentCosts(OaForm form, String period, String operator) {
    var products = items.selectList(Wrappers.<OaFormItem>lambdaQuery().eq(OaFormItem::getOaFormId, form.getId())
        .orderByAsc(OaFormItem::getSeq).orderByAsc(OaFormItem::getId));
    var rowIds = repository.sourceRows(form.getId());
    List<CostLine> result = new ArrayList<>();
    for (var item : products) {
      String product = item.getMaterialNo() == null ? item.getSunlModel() : item.getMaterialNo();
      String shipping = item.getShippingFee() == null ? null : item.getShippingFee().toPlainString();
      try {
        var context = contexts.resolve(new ProductCostingRequest(form.getOaNo(), item.getId(), period, operator, false));
        // 展示与提交同一份最新成功保存结果。公共价格、费率变化由报价员主动重新核算，
        // 不能在提交前重新读取来源而把已经完成的成本隐藏或判成失效。
        var match = successes.findSaved(context);
        if (match.isEmpty()) throw invalid("尚无已完成的核算结果，请先完成核算");
        var version = match.get().version();
        if (version.getFinalQuoteAmount() == null || version.getFinalQuoteAmount().signum() < 0) throw invalid("最终成本缺失或为负数，请重新核算");
        if (item.getShippingFee() != null && item.getShippingFee().signum() < 0) throw invalid("产品运费不能为负数，请核对报价需求单");
        // 最终成本已包含一次材料费差额；TOTAL 仅为财务正常成本，不作为对外提交金额。
        result.add(new CostLine(item.getId(), product, version.getId(), version.getVersionNo(), version.getFinalQuoteAmount().toPlainString(),
            shipping, version.getSourceRevision(), version.getTechDataInputJson(), item.getBusinessType(), rowIds.get(item.getId()), item.getSeq(), "READY", null));
      } catch (IllegalArgumentException | IllegalStateException | QuoteIngestException failure) {
        // No valid current result: historical costs remain in their saved-version reader.
        result.add(new CostLine(item.getId(), product, null, null, null, shipping,
            null, null, item.getBusinessType(),
            rowIds.get(item.getId()), item.getSeq(), item.getConfirmedCostVersionId() != null ? "STALE" : "NOT_READY", failure.getMessage()));
      }
    }
    return List.copyOf(result);
  }
  private String fingerprint(OaForm form, String month, List<CostLine> costs) {
    return codec.canonicalHash(Map.of("formId", form.getId(), "periodMonth", month,
        "sourceVersion", repository.linked(form.getId()) ? repository.sourceVersion(form.getId()) : 0,
        "processCode", Objects.toString(form.getProcessCode(), ""), "costs", costs));
  }
  private Status frozen(QuoteFinalSubmissionRepository.Submission submission) {
    List<CostLine> costs = new ArrayList<>();
    codec.read(submission.snapshotJson()).forEach(c -> costs.add(json.convertValue(c, CostLine.class)));
    return view(submission, submission.status(), submission.month(), costs, submission.fingerprint(),
        submission.error(), submission.returnReason(), false);
  }
  private Status view(QuoteFinalSubmissionRepository.Submission submission, String state, String month,
      List<CostLine> costs, String fingerprint, String error, String reason, boolean allowed) {
    int ready = (int)costs.stream().filter(c -> c.totalCost() != null && "READY".equals(c.status())).count();
    return new Status(submission == null ? null : submission.id(), state, month, costs, costs.size(), ready, fingerprint, error, reason, allowed);
  }
  private OaForm requireForm(String oaNo, TechnicalDataActor actor) {
    if (actor == null || actor.userId() == null || actor.oaSession() || !actor.has("ingest:quote:cost-run:execute")) throw invalid("无权确认本报价");
    var form = forms.selectOne(Wrappers.<OaForm>lambdaQuery().eq(OaForm::getOaNo, oaNo));
    if (form == null || !Objects.equals(form.getBusinessUnitType(), BusinessUnitContext.getCurrentBusinessUnitType())) throw invalid("报价单不存在或不属于当前业务单元");
    return form;
  }
  private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
