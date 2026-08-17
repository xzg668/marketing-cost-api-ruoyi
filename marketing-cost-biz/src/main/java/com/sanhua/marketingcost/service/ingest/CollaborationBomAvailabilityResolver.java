package com.sanhua.marketingcost.service.ingest;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 报价核算的协作BOM候选：U9优先；无U9用已审核电子图库；裸品组合U9本体与已审核包装。 */
@Component
public class CollaborationBomAvailabilityResolver {
  private final JdbcTemplate jdbc;

  public CollaborationBomAvailabilityResolver(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public BomAvailability resolve(
      Long oaFormItemId, String businessUnitType, String costPeriodMonth,
      BomAvailability u9Availability) {
    List<Row> rows = jdbc.query("""
        SELECT p.primary_scope,p.supplement_version_id,p.package_reference_id,
               p.electronic_bom_fingerprint,l.accounting_month
        FROM lp_quote_collaboration_quote_link l
        JOIN lp_quote_collaboration_product_task p ON p.id=l.product_task_id
        WHERE l.oa_form_item_id=? AND l.active_flag=1 AND l.link_status='READY'
          AND p.business_unit_type=?
          AND p.task_status IN ('READY_FOR_COSTING','COSTING','COMPLETED')
        ORDER BY l.id DESC LIMIT 1
        """, (rs, index) -> new Row(rs.getString("primary_scope"),
        rs.getObject("supplement_version_id", Long.class),
        rs.getObject("package_reference_id", Long.class),
        rs.getString("electronic_bom_fingerprint"), rs.getString("accounting_month")),
        oaFormItemId, businessUnitType);
    if (rows.isEmpty()) return null;
    Row row = rows.get(0);
    String month = costPeriodMonth == null ? row.accountingMonth() : costPeriodMonth;
    YearMonth period = YearMonth.parse(month);
    if ("BARE_PACKAGE".equals(row.scope())) {
      if (u9Availability == null || !u9Availability.isAvailable()) {
        return BomAvailability.unavailable("裸品已审核包装存在，但U9本体BOM当前不可用");
      }
      if (row.packageReferenceId() == null) {
        return BomAvailability.unavailable("裸品已审核包装结果缺少来源引用");
      }
      return available("U9_BODY+APPROVED_PACKAGE", "裸品本体+包装", "PKG-"
          + row.packageReferenceId(), "PACKAGE_REFERENCE:" + row.packageReferenceId(), period);
    }
    if (u9Availability != null && u9Availability.isAvailable()) return null;
    if ("FULL_BOM".equals(row.scope()) && row.supplementVersionId() != null
        && row.fingerprint() != null && !row.fingerprint().isBlank()) {
      return available("ELECTRONIC_DRAWING_BOM", "完整BOM", "ED-"
          + row.supplementVersionId(), "SUPPLEMENT_VERSION:" + row.supplementVersionId(), period);
    }
    return BomAvailability.unavailable("协作已完成，但缺少可核算的审核BOM来源");
  }

  private static BomAvailability available(
      String source, String purpose, String version, String batch, YearMonth period) {
    BomAvailability result = new BomAvailability();
    result.setAvailable(true);
    result.setSource(source);
    result.setBomPurpose(purpose);
    result.setBomVersion(version);
    result.setSyncBatchId(batch);
    result.setEffectiveFrom(period.atDay(1));
    result.setEffectiveTo(period.atEndOfMonth());
    return result;
  }

  private record Row(
      String scope, Long supplementVersionId, Long packageReferenceId,
      String fingerprint, String accountingMonth) {}
}
