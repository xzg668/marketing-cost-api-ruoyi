package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.MasterAction;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** QCBP-22：协作存在时，财务审核和重新取价完成才允许进入既有六步核算。 */
@Service
public class CollaborationCostingGate {
  private static final Set<String> ALLOWED_PRODUCT = Set.of(
      "READY_FOR_COSTING", "COSTING", "COMPLETED");
  private static final CollaborationPrincipal SYSTEM = new CollaborationPrincipal(
      0L, "系统", Set.of(CollaborationRole.SYSTEM));

  private final JdbcTemplate jdbc;
  private final QuoteCollaborationTaskRepository repository;
  private final CollaborationProductStateService productStateService;
  private final CollaborationMasterStateService masterStateService;
  private final CollaborationCurrentPrincipalProvider principalProvider;

  public CollaborationCostingGate(
      JdbcTemplate jdbc, QuoteCollaborationTaskRepository repository,
      CollaborationProductStateService productStateService,
      CollaborationMasterStateService masterStateService,
      CollaborationCurrentPrincipalProvider principalProvider) {
    this.jdbc = jdbc;
    this.repository = repository;
    this.productStateService = productStateService;
    this.masterStateService = masterStateService;
    this.principalProvider = principalProvider;
  }

  @Transactional
  public void requireReadyAndStart(Long oaFormItemId, String businessUnitType) {
    List<Row> rows = rows(oaFormItemId, businessUnitType);
    if (rows.isEmpty()) return; // 未进入协作的既有完整产品保持原流程。
    for (Row row : rows) {
      if (!"READY".equals(row.linkStatus()) || !ALLOWED_PRODUCT.contains(row.productStatus())) {
        throw pending(row);
      }
    }
    CollaborationPrincipal operator = currentOperator();
    Map<Long, Row> products = new LinkedHashMap<>();
    rows.forEach(row -> products.putIfAbsent(row.productTaskId(), row));
    for (Row row : products.values()) {
      if (!"READY_FOR_COSTING".equals(row.productStatus())) continue;
      QuoteCollaborationProductTask task = repository.findProductTaskById(
          row.productTaskId(), new CollaborationScope(row.businessUnitType(), row.orgCode()))
          .orElseThrow(() -> new QuoteIngestException("协作产品任务不存在"));
      productStateService.transition(task.getId(), task.getTaskVersion(),
          new CollaborationScope(task.getBusinessUnitType(), task.getApplicableOrgCode()),
          ProductAction.START_COSTING, operator);
    }
  }

  @Transactional
  public void complete(Long oaFormItemId, String businessUnitType) {
    List<Row> rows = rows(oaFormItemId, businessUnitType);
    Map<Long, Row> products = new LinkedHashMap<>();
    rows.forEach(row -> products.putIfAbsent(row.productTaskId(), row));
    for (Row row : products.values()) {
      QuoteCollaborationProductTask task = repository.findProductTaskById(
          row.productTaskId(), new CollaborationScope(row.businessUnitType(), row.orgCode()))
          .orElse(null);
      if (task == null || !"COSTING".equals(task.getTaskStatus())) continue;
      productStateService.transition(task.getId(), task.getTaskVersion(),
          new CollaborationScope(task.getBusinessUnitType(), task.getApplicableOrgCode()),
          ProductAction.COMPLETE_COSTING, SYSTEM);
      completeMasterWhenAllProductsDone(task.getOriginCollaborationId(), task.getBusinessUnitType());
    }
  }

  private void completeMasterWhenAllProductsDone(Long collaborationId, String businessUnitType) {
    List<QuoteCollaborationProductTask> products = repository.findProductTasksByCollaboration(
        collaborationId, businessUnitType);
    boolean done = !products.isEmpty() && products.stream().allMatch(product ->
        Set.of("COMPLETED", "CANCELLED").contains(product.getTaskStatus()));
    if (!done) return;
    QuoteCollaborationTask master = repository.findTaskById(collaborationId, businessUnitType)
        .orElse(null);
    if (master != null && "READY_FOR_COSTING".equals(master.getMasterStatus())) {
      masterStateService.transition(master.getId(), master.getTaskVersion(), businessUnitType,
          MasterAction.MARK_COMPLETED, SYSTEM);
    }
  }

  private List<Row> rows(Long oaFormItemId, String businessUnitType) {
    return jdbc.query("""
        SELECT l.product_task_id,l.link_status,p.task_status,p.business_unit_type,
               p.applicable_org_code,p.primary_scope,p.need_bom,p.need_package,p.need_price,
               p.open_gap_count,
               EXISTS(SELECT 1 FROM lp_quote_collaboration_gap g
                      WHERE g.product_task_id=p.id AND g.gap_type='MISSING_PRICE_TYPE'
                        AND g.gap_status NOT IN ('RESOLVED','WAIVED','OBSOLETE')) AS missing_price_type
        FROM lp_quote_collaboration_quote_link l
        JOIN lp_quote_collaboration_product_task p ON p.id=l.product_task_id
        WHERE l.oa_form_item_id=? AND l.active_flag=1 AND p.business_unit_type=?
        ORDER BY l.id
        """, (rs, index) -> new Row(rs.getLong("product_task_id"),
        rs.getString("link_status"), rs.getString("task_status"),
        rs.getString("business_unit_type"), rs.getString("applicable_org_code"),
        rs.getString("primary_scope"), rs.getInt("need_bom"), rs.getInt("need_package"),
        rs.getInt("need_price"), rs.getInt("open_gap_count"),
        rs.getBoolean("missing_price_type")),
        oaFormItemId, businessUnitType);
  }

  private CollaborationPrincipal currentOperator() {
    try {
      return principalProvider.current();
    } catch (IllegalStateException exception) {
      // Worker 没有 Web 登录上下文；任务本身已通过数据库锁取得执行权。
      return SYSTEM;
    }
  }

  private CollaborationCostingPendingException pending(Row row) {
    String status;
    String code;
    if (row.missingPriceType()) {
      status = "WAIT_PRICE_TYPE";
      code = "PRICE_TYPE_MISSING";
    } else if (row.needPrice() == 1
        || "PRICE_ONLY".equals(row.primaryScope())
        || "PRICE_IN_PROGRESS".equals(row.productStatus())) {
      status = "WAIT_PRICE";
      code = "PRICE_MISSING";
    } else {
      status = "WAIT_BOM";
      code = row.needPackage() == 1 ? "PACKAGE_MISSING" : "BOM_MISSING";
    }
    return new CollaborationCostingPendingException(
        status, code, row.openGapCount(),
        "该产品协作尚未完成财务审核和重新取价，当前由系统显示的处理人继续处理；完成后可再次核算");
  }

  private record Row(
      Long productTaskId, String linkStatus, String productStatus,
      String businessUnitType, String orgCode, String primaryScope,
      int needBom, int needPackage, int needPrice, int openGapCount,
      boolean missingPriceType) {}
}
