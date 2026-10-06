package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingResult;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBatchCostRunRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBatchCostRunResponse;
import com.sanhua.marketingcost.integration.oa.OaWorkflowAccessPolicy;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.BusinessUnitRepriceLockGuard;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import com.sanhua.marketingcost.service.QuoteBatchCostRunService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingProductQuery;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 检查整单资料、采用当前提交版本，再发起核算；此入口不调用 OA 提交流程。 */
@Service
public class QuoteCostingPreparationService {
  public record Request(Long itemId, String periodMonth) {}
  public record State(long formId, String status, String oaState, boolean canCost,
      boolean hasSupplement, int totalProducts, String message) {}
  public record Outcome(State preparation, List<ProductCostingResult> checks,
      QuoteBatchCostRunResponse batch, ProductCostingResult product) {}

  private final com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaSubmissionLifecycle lifecycle;
  private final JdbcTemplate jdbc;
  private final OaWorkflowAccessPolicy access;
  private final ProductCostingPipeline pipeline;
  private final ProductCostingContextResolver contexts;
  private final QuoteBatchCostRunService batches;
  private final BusinessUnitRepriceLockGuard repriceLock;
  private final QuoteCostingSubmissionService technicalSubmissions;
  private final TechnicalDataPendingProductQuery pendingProducts;
  private final TransactionTemplate transaction;

  public QuoteCostingPreparationService(JdbcTemplate jdbc, OaWorkflowAccessPolicy access,
      ProductCostingPipeline pipeline, ProductCostingContextResolver contexts, QuoteBatchCostRunService batches,
      BusinessUnitRepriceLockGuard repriceLock, QuoteCostingSubmissionService technicalSubmissions,
      TechnicalDataPendingProductQuery pendingProducts, PlatformTransactionManager manager,
      com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaSubmissionLifecycle lifecycle) {
    this.lifecycle = lifecycle;
    this.jdbc = jdbc;
    this.access = access;
    this.pipeline = pipeline;
    this.contexts = contexts;
    this.batches = batches;
    this.repriceLock = repriceLock;
    this.technicalSubmissions = technicalSubmissions;
    this.pendingProducts = pendingProducts;
    this.transaction = new TransactionTemplate(manager);
  }

  public State state(String oaNo) {
    return state(oaNo, null);
  }

  public State state(String oaNo, Long itemId) {
    long formId = requireForm(oaNo);
    var items = activeItems(formId);
    if (itemId != null && !items.contains(itemId)) throw new IllegalArgumentException("所选产品不属于当前报价单");
    var view = access.view(formId);
    boolean pending = technicalSubmissions.hasPendingSubmissions(formId, itemId);
    boolean allowed = (view == null || view.canCost()) && !pending;
    String message = itemId == null ? "点击核算检查整单资料，资料齐全后开始计算"
        : "点击核算检查本产品资料，资料齐全后开始计算";
    if (pending) message = "等待技术员提交补录资料，可查看已提交内容及任务进度";
    else if (view != null && !view.canCost()) {
      message = view.syncError() != null ? view.syncError() : "当前为" + view.label() + "，暂不能核算";
    }
    boolean supplement = Boolean.TRUE.equals(jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM lp_quote_tech_task WHERE oa_form_id=? AND (? IS NULL OR oa_form_item_id=?))",
        Boolean.class, formId, itemId, itemId));
    return new State(formId, allowed ? "AVAILABLE" : "WAITING", view == null ? null : view.state(), allowed,
        supplement || (itemId == null ? pendingProducts.count("ALL", null, null, null, oaNo) > 0
            : pendingProducts.hasPendingItem(oaNo, itemId)),
        itemId == null ? items.size() : 1, message);
  }

  public Outcome prepareAndCost(String oaNo, Request request, String operatorName) {
    if (request == null) throw new IllegalArgumentException("核算请求不能为空");
    long formId = requireForm(oaNo);
    String month = CostPricingPeriodUtils.requireCurrentPricingMonth(request.periodMonth());
    var items = activeItems(formId);
    if (items.isEmpty()) throw new IllegalArgumentException("报价单没有有效产品");
    if (request.itemId() != null && !items.contains(request.itemId())) {
      throw new IllegalArgumentException("所选产品不属于当前报价单");
    }
    Long itemId = request.itemId();
    var selectedItems = itemId == null ? items : List.of(itemId);
    access.requireCosting(formId);
    technicalSubmissions.requireSubmitted(formId, itemId);
    repriceLock.assertCostRunAllowed(oaNo);
    var node = access.view(formId) == null ? null : access.quoterNode(formId);
    long actorId = node != null ? node.actorId() : jdbc.queryForObject(
        "SELECT user_id FROM sys_user WHERE user_name=? AND status='0' AND del_flag='0'", Long.class, operatorName);
    transaction.executeWithoutResult(status -> {
      jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? AND deleted=0 FOR UPDATE", Long.class, formId);
      technicalSubmissions.requireSubmitted(formId, itemId);
      lifecycle.prepareSubmittedMaterials(formId, itemId);
      technicalSubmissions.beginReview(formId, actorId, itemId);
    });
    List<ProductCostingResult> checks = new ArrayList<>();
    for (long selectedItemId : selectedItems) {
      checks.add(pipeline.prepare(new ProductCostingRequest(oaNo, selectedItemId, month, operatorName, false)));
    }
    if (checks.stream().anyMatch(check -> !"READY".equals(check.getPipelineStatus()))) {
      var current = state(oaNo, itemId);
      return new Outcome(new State(formId, "WAITING_INPUT", current.oaState(), current.canCost(),
          current.hasSupplement() || checks.stream().anyMatch(check -> check.getTechnicalDataCheck() != null
              && check.getTechnicalDataCheck().modules().stream().anyMatch(module -> module.required())),
          selectedItems.size(), itemId == null
              ? "整单资料尚未齐全，请处理缺口；需补录的产品可到补录工作台分派"
              : "本产品资料尚未齐全，请处理缺口；需补录时可到补录工作台分派"), checks, null, null);
    }
    transaction.executeWithoutResult(status -> {
      jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? AND deleted=0 FOR UPDATE", Long.class, formId);
      access.requireCosting(formId);
      if (!items.equals(activeItems(formId)) || node != null && !node.equals(access.quoterNode(formId))) {
        throw new IllegalArgumentException("整单需求或报价员办理状态已变化，请重新检查");
      }
      for (var check : checks) {
        var context = contexts.resolve(new ProductCostingRequest(oaNo, check.getOaFormItemId(), month, operatorName, false));
        if (!Objects.equals(check.getSourceRevision(), contexts.resolveRevision(context).sourceRevision())) {
          throw new IllegalArgumentException("产品 " + check.getProductCode() + " 的核算资料已变化，请重新检查");
        }
      }
      technicalSubmissions.accept(formId, actorId, itemId);
    });
    access.requireCostPublication(formId, itemId);
    if (itemId != null) return new Outcome(state(oaNo, itemId), checks, null,
        pipeline.execute(new ProductCostingRequest(oaNo, itemId, month, operatorName, false)));
    var batchRequest = new QuoteBatchCostRunRequest();
    batchRequest.setPeriodMonth(month);
    return new Outcome(state(oaNo), checks, batches.submit(oaNo, batchRequest, operatorName), null);
  }

  private List<Long> activeItems(long formId) {
    return jdbc.queryForList("SELECT id FROM oa_form_item WHERE oa_form_id=? AND deleted=0 ORDER BY id", Long.class, formId);
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
