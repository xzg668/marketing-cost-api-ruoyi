package com.sanhua.marketingcost.service.quoteconfirmation;

import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingResult;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBatchCostRunRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBatchCostRunResponse;
import com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy;
import com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy.MaterialNode;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import com.sanhua.marketingcost.integration.oa.workflow.OaMaterialConfirmationClient;
import com.sanhua.marketingcost.integration.oa.workflow.OaWorkflowResult;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.BusinessUnitRepriceLockGuard;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import com.sanhua.marketingcost.service.QuoteBatchCostRunService;
import com.sanhua.marketingcost.service.costing.ProductCostingContextResolver;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingProductQuery;
import com.sanhua.marketingcost.service.quoteconfirmation.QuoteMaterialConfirmationRepository.Confirmation;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 整单资料检查、一次 I06、核算接续；准备及回执各自提交事务，HTTP 不占业务锁。 */
@Service
public class QuoteMaterialConfirmationService {
  public record Request(String requestKey, Long itemId, String periodMonth, boolean retryRejected) {}
  public record State(long formId, String status, String oaState, boolean needsConfirmation,
      boolean canConfirm, boolean hasSupplement, int totalProducts, String message) {}
  public record Outcome(State confirmation, List<ProductCostingResult> checks,
      QuoteBatchCostRunResponse batch, ProductCostingResult product) {}

  private final TechnicalDataPendingProductQuery pendingProducts;
  private final JdbcTemplate jdbc;
  private final QuoteMaterialConfirmationRepository repository;
  private final OaWorkflowAccessPolicy access;
  private final ProductCostingPipeline pipeline;
  private final ProductCostingContextResolver contexts;
  private final QuoteBatchCostRunService batches;
  private final OaMaterialConfirmationClient oa;
  private final BusinessUnitRepriceLockGuard repriceLock;
  private final TechnicalDataOaWorkflowRepository technicalWorkflow;
  private final OaMessageCodec codec;
  private final TransactionTemplate transaction;

  public QuoteMaterialConfirmationService(JdbcTemplate jdbc, QuoteMaterialConfirmationRepository repository,
      OaWorkflowAccessPolicy access, ProductCostingPipeline pipeline, ProductCostingContextResolver contexts,
      QuoteBatchCostRunService batches, OaMaterialConfirmationClient oa, BusinessUnitRepriceLockGuard repriceLock,
      TechnicalDataOaWorkflowRepository technicalWorkflow, OaMessageCodec codec, PlatformTransactionManager manager,
      TechnicalDataPendingProductQuery pendingProducts) {
    this.pendingProducts = pendingProducts;
    this.jdbc = jdbc;
    this.repository = repository;
    this.access = access;
    this.pipeline = pipeline;
    this.contexts = contexts;
    this.batches = batches;
    this.oa = oa;
    this.repriceLock = repriceLock;
    this.technicalWorkflow = technicalWorkflow;
    this.codec = codec;
    this.transaction = new TransactionTemplate(manager);
  }

  public State state(String oaNo) {
    long formId = requireForm(oaNo);
    var view = access.view(formId);
    String status = "NOT_REQUIRED", message = "按当前资料继续核算";
    boolean needs = view != null && "MATERIAL_REVIEW".equals(view.state());
    boolean allowed = view == null || view.canCost();
    if (view != null && !allowed) {
      status = "WAITING";
      message = view.syncError() != null ? view.syncError() : "当前为" + view.label() + "，等待有效报价员待办";
    } else if (needs) {
      var node = access.materialNode(formId);
      var confirmation = repository.find(formId, node.formVersion(), node.workItemId());
      status = confirmation == null ? "REQUIRES_CONFIRMATION" : confirmation.status();
      message = switch (status) {
        case "SUCCESS" -> "本轮资料已确认，可继续核算";
        case "PREPARED", "SENDING" -> "本轮资料正在提交 OA，请等待原请求结果";
        case "UNKNOWN" -> "OA 结果尚未确认，请核实原流程后再办理";
        case "REJECTED", "NOT_SENT" -> confirmation.result().message();
        default -> "核算前检查整单资料，齐全后确认并继续核算";
      };
      needs = !"SUCCESS".equals(status);
      allowed = !Set.of("SENDING", "UNKNOWN").contains(status);
    }
    if (needs && allowed && repository.pendingApprovals(formId, access.materialNode(formId).formVersion()) > 0
        && !Set.of("SENDING", "UNKNOWN").contains(status)) {
      Integer revisions = jdbc.queryForObject("""
          SELECT COUNT(*) FROM lp_quote_tech_task t JOIN lp_quote_tech_oa_recipient r ON r.task_id=t.id
          WHERE t.oa_form_id=? AND t.active_flag=1 AND r.active_flag=1
            AND (r.todo_status='RETURN_PENDING' OR (r.todo_status='OPEN' AND r.return_reason IS NOT NULL))
          """, Integer.class, formId);
      if (revisions > 0) message = "部分资料已退回或退回结果待确认，等待修改及重新审批";
    }
    return new State(formId, status, view == null ? null : view.state(), needs, allowed,
        repository.hasSupplement(formId) || pendingProducts.count("ALL", null, null, null, oaNo) > 0,
        repository.activeItems(formId).size(), message);
  }

  public Outcome confirmAndCost(String oaNo, Request request, String operatorName) {
    if (request == null || request.requestKey() == null
        || !request.requestKey().matches("[A-Za-z0-9._:-]{1,128}")) {
      throw new IllegalArgumentException("资料确认请求编号不能为空或格式不正确");
    }
    long formId = requireForm(oaNo);
    String month = CostPricingPeriodUtils.requireCurrentPricingMonth(request.periodMonth());
    var items = repository.activeItems(formId);
    if (items.isEmpty()) throw new IllegalArgumentException("报价单没有有效产品");
    if (request.itemId() != null && !items.contains(request.itemId())) {
      throw new IllegalArgumentException("所选产品不属于当前报价单");
    }
    access.requireCosting(formId);
    repriceLock.assertCostRunAllowed(oaNo);
    var view = access.view(formId);
    if (view == null || !"MATERIAL_REVIEW".equals(view.state())) {
      return cost(oaNo, request, operatorName, month, List.of());
    }
    MaterialNode node = access.materialNode(formId);
    Confirmation previous = repository.find(formId, node.formVersion(), node.workItemId());
    if (previous != null && !mayRetry(previous, request)) {
      return finish(oaNo, request, operatorName, month, previous, List.of());
    }

    List<ProductCostingResult> checks = new ArrayList<>();
    for (long itemId : items) {
      checks.add(pipeline.prepare(new ProductCostingRequest(oaNo, itemId, month, operatorName, false)));
    }
    if (checks.stream().anyMatch(check -> !"READY".equals(check.getPipelineStatus()))) {
      var state = state(oaNo);
      return new Outcome(new State(formId, "WAITING_INPUT", state.oaState(), true, true,
          state.hasSupplement() || checks.stream().anyMatch(check -> check.getTechnicalDataCheck() != null
              && check.getTechnicalDataCheck().modules().stream().anyMatch(module -> module.required())),
          items.size(), "整单资料检查未通过，请处理缺口或等待必要技术审批；尚未提交 OA"), checks, null, null);
    }
    String id = transaction.execute(status -> prepare(oaNo, node, request, month, operatorName, items, checks));
    return finish(oaNo, request, operatorName, month, repository.find(id), checks);
  }

  private String prepare(String oaNo, MaterialNode expected, Request request, String month, String operator,
      List<Long> items, List<ProductCostingResult> checks) {
    repository.lockForm(expected.formId());
    MaterialNode current = access.materialNode(expected.formId());
    if (expected.formVersion() != current.formVersion() || !expected.workItemId().equals(current.workItemId())
        || !items.equals(repository.activeItems(expected.formId()))) {
      throw new IllegalArgumentException("整单需求或 OA 资料待办已变化，请重新检查");
    }
    var existing = repository.find(current.formId(), current.formVersion(), current.workItemId());
    if (existing != null && !mayRetry(existing, request)) return existing.id();
    if (repository.pendingApprovals(current.formId(), current.formVersion()) > 0) {
      throw new IllegalArgumentException("本单仍有必要技术任务未完成审批，不能提交资料节点");
    }
    for (var check : checks) {
      var context = contexts.resolve(new ProductCostingRequest(oaNo, check.getOaFormItemId(), month, operator, false));
      if (!Objects.equals(check.getSourceRevision(), contexts.resolveRevision(context).sourceRevision())) {
        throw new IllegalArgumentException("产品 " + check.getProductCode() + " 的核算资料已变化，请重新检查");
      }
    }
    var body = oa.preview(current.requestId(), current.employeeNo(), repository.hasSupplement(current.formId()));
    if (existing != null) {
      repository.retryRejected(existing.id(), request.requestKey(), current.actorId(), current.employeeNo(), month, body, checks);
      return existing.id();
    }
    String id = UUID.randomUUID().toString();
    repository.prepare(id, current.formId(), current.formVersion(), current.workItemId(), current.actorId(),
        current.employeeNo(), request.requestKey(), month, body, checks);
    return id;
  }

  private boolean mayRetry(Confirmation record, Request request) {
    return request.retryRejected() && Set.of("REJECTED", "NOT_SENT").contains(record.status())
        && !record.requestKey().equals(request.requestKey());
  }

  private Outcome finish(String oaNo, Request request, String operator, String month,
      Confirmation confirmation, List<ProductCostingResult> checks) {
    if ("PREPARED".equals(confirmation.status())) confirmation = deliver(confirmation);
    if (!"SUCCESS".equals(confirmation.status())) return new Outcome(state(oaNo), checks, null, null);
    Confirmation accepted = confirmation;
    transaction.executeWithoutResult(status -> {
      repository.lockForm(accepted.formId());
      access.requireCostPublication(accepted.formId());
      if (repository.pendingApprovals(accepted.formId(), accepted.formVersion()) > 0) {
        throw new IllegalArgumentException("资料审批状态已变化，本次不继续核算，请刷新核实");
      }
      var ids = jdbc.queryForList("SELECT id FROM lp_oa_technical_flow WHERE oa_form_id=?", Long.class, accepted.formId());
      for (long id : ids) {
        var flow = technicalWorkflow.lockFlow(id);
        technicalWorkflow.refreshFinance(id);
        flow = technicalWorkflow.lockFlow(id);
        String fingerprint = codec.canonicalHash(technicalWorkflow.approvalBasis(id));
        if (flow.financeReady() && !fingerprint.equals(flow.confirmedFingerprint())) {
          technicalWorkflow.confirmFinance(id, fingerprint, accepted.actorId());
        }
      }
    });
    return cost(oaNo, request, operator, month, checks);
  }

  private Confirmation deliver(Confirmation confirmation) {
    boolean claimed = Boolean.TRUE.equals(transaction.execute(status -> {
      repository.lockForm(confirmation.formId());
      var node = access.materialNode(confirmation.formId());
      if (node.formVersion() != confirmation.formVersion() || !node.workItemId().equals(confirmation.workItemId())
          || repository.pendingApprovals(node.formId(), node.formVersion()) > 0) {
        throw new IllegalArgumentException("发送前资料待办或审批状态已变化，未发送 I06");
      }
      try {
        if (!repository.activeItems(node.formId()).equals(confirmation.checks().stream().map(ProductCostingResult::getOaFormItemId).toList())) {
          throw new IllegalArgumentException("整单产品已变化，请核实原资料确认记录");
        }
        CostPricingPeriodUtils.requireCurrentPricingMonth(confirmation.month());
        for (var check : confirmation.checks()) {
          var context = contexts.resolve(new ProductCostingRequest(check.getOaNo(), check.getOaFormItemId(),
              confirmation.month(), null, false));
          if (!Objects.equals(check.getSourceRevision(), contexts.resolveRevision(context).sourceRevision())) {
            throw new IllegalArgumentException("发送前核算资料已变化，未发送 I06，请重新检查");
          }
        }
      } catch (IllegalArgumentException invalidated) {
        repository.notSent(confirmation.id(), new OaWorkflowResult(null, OaWorkflowResult.Status.NOT_SENT, null,
            "MATERIAL_CHANGED", invalidated.getMessage(), node.requestId(), null, 0));
        return false;
      }
      return repository.claim(confirmation.id());
    }));
    if (claimed) {
      OaWorkflowResult result;
      try (var call = OaInterfaceLog.start("I06_DELIVERY")) {
        call.field("confirmationId", confirmation.id()).field("formId", confirmation.formId());
        try {
          result = oa.submit(confirmation.request());
          call.result(result.status().name(), result.httpStatus(), result.errorCode());
        } catch (RuntimeException exception) {
          call.failure(exception);
          result = new OaWorkflowResult(null, OaWorkflowResult.Status.UNKNOWN, null, "I06_RESULT_UNCONFIRMED",
              "I06 调用结果未确认，请核实原流程；未自动重发", confirmation.request().path("requestId").asText(), null, 0);
        }
      }
      var received = result;
      transaction.executeWithoutResult(status -> repository.received(confirmation.id(), received));
    }
    return repository.find(confirmation.id());
  }

  private Outcome cost(String oaNo, Request request, String operator, String month, List<ProductCostingResult> checks) {
    access.requireCostPublication(oaNo);
    if (request.itemId() != null) return new Outcome(state(oaNo), checks, null,
        pipeline.execute(new ProductCostingRequest(oaNo, request.itemId(), month, operator, false)));
    var batchRequest = new QuoteBatchCostRunRequest();
    batchRequest.setPeriodMonth(month);
    return new Outcome(state(oaNo), checks, batches.submit(oaNo, batchRequest, operator), null);
  }

  private long requireForm(String oaNo) {
    var rows = jdbc.queryForList("SELECT id,business_unit_type FROM oa_form WHERE oa_no=? AND deleted=0", oaNo);
    if (rows.size() != 1 || !BusinessUnitContext.isAdmin()
        && !Objects.equals(rows.getFirst().get("business_unit_type"), BusinessUnitContext.getCurrentBusinessUnitType())) {
      throw new IllegalArgumentException("报价单不存在或不属于当前业务单元");
    }
    return ((Number) rows.getFirst().get("id")).longValue();
  }
}
