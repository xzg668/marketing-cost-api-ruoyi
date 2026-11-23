package com.sanhua.marketingcost.service.costing;

import static com.sanhua.marketingcost.service.QuoteMonthCloseLocks.COMMERCIAL;

import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.QuoteMonthCloseLocks;
import com.sanhua.marketingcost.service.QuotePricingPeriod;
import com.sanhua.marketingcost.service.reprice.MonthlyQuotationControl;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class QuoteMonthCloseService {
  public record Close(long id, String periodMonth, String closedBy, LocalDateTime closedAt,
      String revokedBy, LocalDateTime revokedAt, String revokeReason) {}

  public record Row(long itemId, String oaNo, String customer, Integer lineNo, String productCode,
      String productName, String model, String spec, Long versionId, String versionNo,
      BigDecimal amount, LocalDateTime completedAt) {}

  public record View(String periodMonth, String activeMonth, boolean canConfirm, boolean canRevoke,
      Close close, long orderCount, long readyCount, List<Row> rows) {}

  public record OrderStatus(String periodMonth, Map<Long, Long> lockedVersions) {}

  private static final String COMPLETED_AT = "COALESCE(v.confirmed_at, v.trial_finished_at, v.created_at)";
  private static final RowMapper<Close> CLOSE = (rs, row) -> new Close(rs.getLong("id"),
      rs.getString("period_month"), rs.getString("closed_by"), date(rs.getTimestamp("closed_at")),
      rs.getString("revoked_by"), date(rs.getTimestamp("revoked_at")), rs.getString("revoke_reason"));
  private static final RowMapper<Row> ROW = (rs, row) -> new Row(rs.getLong("item_id"),
      rs.getString("oa_no"), rs.getString("customer"), rs.getObject("line_no", Integer.class),
      rs.getString("product_code"), rs.getString("product_name"), rs.getString("model"),
      rs.getString("spec"), rs.getObject("version_id", Long.class), rs.getString("version_no"),
      rs.getBigDecimal("amount"), date(rs.getTimestamp("completed_at")));

  private final JdbcTemplate jdbc;
  private final QuotePricingPeriod periods;
  private final QuoteMonthCloseLocks locks;
  private final MonthlyQuotationControl quotationControl;
  private final TransactionTemplate transactions;

  public QuoteMonthCloseService(JdbcTemplate jdbc, QuotePricingPeriod periods,
      QuoteMonthCloseLocks locks, MonthlyQuotationControl quotationControl,
      PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.periods = periods;
    this.locks = locks;
    this.quotationControl = quotationControl;
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Transactional(readOnly = true)
  public View view(String requestedMonth, boolean operatorCanChange) {
    requireCommercial();
    String active = periods.currentMonth(COMMERCIAL);
    String month = requestedMonth == null ? active : CostPricingPeriodUtils.normalizePricingMonth(requestedMonth);
    Close close = findClose(month);
    List<Row> rows = close != null
        ? lockedRows(close.id())
        : month.equals(active) ? candidates(month) : List.of();
    long orders = rows.stream().map(Row::oaNo).distinct().count();
    boolean canChange = operatorCanChange && locks.permitsChanges() && month.equals(active);
    return new View(month, active, canChange && close == null && !rows.isEmpty(),
        canChange && close != null, close, orders, rows.size(), rows);
  }

  @Transactional(readOnly = true)
  public List<Close> history() {
    requireCommercial();
    return locks.hasStorage()
        ? jdbc.query("SELECT * FROM lp_quote_month_close WHERE revoked_at IS NULL ORDER BY period_month DESC", CLOSE)
        : List.of();
  }

  @Transactional(readOnly = true)
  public OrderStatus orderStatus(String oaNo) {
    if (!locks.hasStorage() || !COMMERCIAL.equals(BusinessUnitContext.getCurrentBusinessUnitType())) {
      return new OrderStatus(null, Map.of());
    }
    String active = periods.currentMonth(COMMERCIAL);
    return new OrderStatus(active, locks.forOrder(COMMERCIAL, active, oaNo));
  }

  public Close confirm(String requestedMonth, String username) {
    requireChange(requestedMonth);
    // Ordinary publication, batch admission and repricing confirmation use this same database lease.
    return quotationControl.serialized(COMMERCIAL, () -> transactions.execute(status -> {
      String month = periods.requireActiveMonth(requestedMonth, COMMERCIAL);
      Close existing = findClose(month);
      if (existing != null) return existing;
      if (hasActiveCosting(month)) throw new IllegalStateException("本月还有正在处理的报价核算，请完成后再结账");
      List<Row> rows = candidates(month);
      if (rows.isEmpty()) throw new IllegalStateException("当月尚无可结账的正式报价成本");

      jdbc.update("INSERT INTO lp_quote_month_close(period_month, closed_by, closed_at) VALUES(?,?,?)",
          month, username, LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE));
      Close close = Objects.requireNonNull(findClose(month));
      jdbc.batchUpdate("""
          INSERT INTO lp_quote_month_close_item
            (close_id, oa_form_item_id, cost_version_id, oa_no, customer, line_no, product_code, product_name, model, spec)
          VALUES (?,?,?,?,?,?,?,?,?,?)
          """, rows, 250, (statement, row) -> {
        statement.setLong(1, close.id());
        statement.setLong(2, row.itemId());
        statement.setLong(3, row.versionId());
        statement.setString(4, row.oaNo());
        statement.setString(5, row.customer());
        statement.setObject(6, row.lineNo());
        statement.setString(7, row.productCode());
        statement.setString(8, row.productName());
        statement.setString(9, row.model());
        statement.setString(10, row.spec());
      });
      return close;
    }));
  }

  public Close revoke(long closeId, String requestedMonth, String reason, String username) {
    requireChange(requestedMonth);
    String explanation = reason == null ? "" : reason.trim();
    if (explanation.isEmpty() || explanation.length() > 250) {
      throw new IllegalArgumentException("请输入撤销原因，最多250字");
    }
    return quotationControl.serialized(COMMERCIAL, () -> transactions.execute(status -> {
      String month = periods.requireActiveMonth(requestedMonth, COMMERCIAL);
      Close close = byId(closeId, month);
      if (close.revokedAt() != null) return close;
      jdbc.update("""
          UPDATE lp_quote_month_close SET revoked_by=?, revoked_at=?, revoke_reason=?
          WHERE id=? AND revoked_at IS NULL
          """, username, LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE), explanation, closeId);
      return byId(closeId, month);
    }));
  }

  private Close byId(long closeId, String month) {
    List<Close> result = jdbc.query("SELECT * FROM lp_quote_month_close WHERE id=? AND period_month=?",
        CLOSE, closeId, month);
    if (result.isEmpty()) throw new IllegalArgumentException("该月份的结账记录不存在");
    return result.getFirst();
  }

  private void requireChange(String month) {
    requireCommercial();
    if (!locks.permitsChanges()) throw new AccessDeniedException("当前环境仅开放结账预览");
    if (month == null || month.isBlank()) throw new IllegalArgumentException("请确认结账月份");
  }

  private Close findClose(String month) {
    if (!locks.hasStorage()) return null;
    List<Close> result = jdbc.query("SELECT * FROM lp_quote_month_close WHERE active_period_month=?", CLOSE, month);
    return result.isEmpty() ? null : result.getFirst();
  }

  private List<Row> candidates(String month) {
    return jdbc.query("""
        WITH ranked AS (
          SELECT v.id AS version_id, v.oa_form_item_id, v.oa_no, v.version_no,
            COALESCE(v.final_quote_amount, v.total_cost) AS amount, %1$s AS completed_at,
            ROW_NUMBER() OVER (PARTITION BY v.oa_form_item_id, v.oa_no ORDER BY %1$s DESC, v.id DESC) AS position
          FROM lp_quote_cost_run_version v
          WHERE v.business_unit_type='COMMERCIAL' AND v.pricing_month=? AND v.oa_form_item_id IS NOT NULL
            AND (v.reprice_no IS NULL OR v.reprice_no='')
            AND v.status IN ('SUCCESS','CONFIRMED','HISTORY') AND v.total_cost IS NOT NULL
        )
        SELECT i.id AS item_id, f.oa_no, f.customer, i.seq AS line_no, i.material_no AS product_code,
          i.product_name, i.sunl_model AS model, i.spec, v.version_id, v.version_no, v.amount, v.completed_at
        FROM oa_form f JOIN oa_form_item i ON i.oa_form_id=f.id AND i.deleted=0
        JOIN ranked v ON v.oa_form_item_id=i.id AND v.position=1
          AND CAST(v.oa_no AS BINARY)=CAST(f.oa_no AS BINARY)
        WHERE f.deleted=0 AND f.business_unit_type='COMMERCIAL' AND i.business_unit_type='COMMERCIAL'
        ORDER BY f.oa_no, i.seq, i.id
        """.formatted(COMPLETED_AT), ROW, month);
  }

  private List<Row> lockedRows(long closeId) {
    return jdbc.query("""
        SELECT i.oa_form_item_id AS item_id, i.oa_no, i.customer, i.line_no, i.product_code,
          i.product_name, i.model, i.spec, v.id AS version_id, v.version_no,
          COALESCE(v.final_quote_amount, v.total_cost) AS amount, %s AS completed_at
        FROM lp_quote_month_close_item i JOIN lp_quote_cost_run_version v ON v.id=i.cost_version_id
        WHERE i.close_id=? ORDER BY i.oa_no, i.line_no, i.oa_form_item_id
        """.formatted(COMPLETED_AT), ROW, closeId);
  }

  private boolean hasActiveCosting(String month) {
    return Boolean.TRUE.equals(jdbc.queryForObject("""
        SELECT EXISTS(SELECT 1 FROM lp_cost_run_task
          WHERE business_unit_type='COMMERCIAL' AND scene='QUOTE' AND pricing_month=?
            AND status IN ('PENDING','RUNNING','RETRYABLE'))
          OR EXISTS(SELECT 1 FROM oa_form_item
            WHERE business_unit_type='COMMERCIAL' AND deleted=0
              AND IS_USED_LOCK(CONCAT('quote-item:', LEFT(SHA2(CONCAT(DATABASE(),':',id,':',?),256),48))) IS NOT NULL)
        """, Boolean.class, month, month));
  }

  private static void requireCommercial() {
    if (!COMMERCIAL.equals(BusinessUnitContext.getCurrentBusinessUnitType())) {
      throw new AccessDeniedException("月末结账仅开放商用报价");
    }
  }

  private static LocalDateTime date(Timestamp timestamp) {
    return timestamp == null ? null : timestamp.toLocalDateTime();
  }
}
