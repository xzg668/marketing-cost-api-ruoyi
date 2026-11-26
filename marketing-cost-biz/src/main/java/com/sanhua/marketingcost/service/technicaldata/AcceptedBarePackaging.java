package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 记录财务实际认可或管理员确认发布的冻结版本。后续修改稿独立办理。 */
@Repository
public class AcceptedBarePackaging {
  private final JdbcTemplate jdbc;
  public AcceptedBarePackaging(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  private static final String CAPTURE = """
      INSERT INTO lp_quote_bare_package_accepted(product_id,material_code,business_unit_type,price_org_code,
          module_version_id,effective_version_id,content_fingerprint)
      SELECT p.id,p.material_no,t.business_unit_type,t.applicable_org_code,m.current_version_id,v.id,v.content_fingerprint
        FROM lp_quote_tech_product p JOIN lp_quote_tech_task t ON t.id=p.task_id
        JOIN lp_quote_tech_module m ON m.product_id=p.id AND m.module_type='PACKAGE'
        JOIN lp_quote_tech_data_version v ON v.id=p.effective_version_id AND v.product_id=p.id
        JOIN lp_material_master_raw mm ON mm.material_code=BINARY p.material_no AND mm.active_flag=1
          AND mm.organization_code=BINARY CASE t.applicable_org_code WHEN '220' THEN 'PLATE' WHEN '210' THEN 'COMMERCIAL' END
          AND mm.main_category_code LIKE '11%'
        WHERE p.active_flag=1 AND t.active_flag=1 AND t.task_status IN ('SUBMITTED','APPROVED')
          AND m.current_version_id IS NOT NULL AND m.module_status IN ('SUBMITTED','APPROVED')
          AND v.version_status IN ('SUBMITTED','APPROVED') AND v.content_fingerprint IS NOT NULL
      """;
  private static final String UPSERT = """
       ON DUPLICATE KEY UPDATE module_version_id=VALUES(module_version_id),
          effective_version_id=VALUES(effective_version_id),content_fingerprint=VALUES(content_fingerprint)
      """;

  public void confirmFlow(long flowId) { jdbc.update(CAPTURE + " AND t.oa_flow_id=?" + UPSERT, flowId); }
  public void confirmDirect(long productId) { jdbc.update(CAPTURE + " AND p.id=?" + UPSERT, productId); }

  TechnicalDataCostingSources.Source previous(QuoteBomReadContext context, TechnicalDataSharedModuleRepository.Owner owner,
      QuoteTechnicalDataRepository repository) {
    var rows = jdbc.query("""
        SELECT module_version_id,effective_version_id,content_fingerprint FROM lp_quote_bare_package_accepted
          WHERE product_id=? AND material_code=? AND business_unit_type=? AND price_org_code=?
        """, (rs,i) -> new Accepted(rs.getLong(1),rs.getLong(2),rs.getString(3)),
        owner.productId(),context.productCode(),context.businessUnitType(),context.priceOrgCode());
    if (rows.isEmpty()) return null;
    var prior = rows.getFirst();
    // 当前退回的正是同一份资料时，不能把它当成另一份较早的已认可包装。
    if (Objects.equals(owner.versionId(), prior.module()) && "RETURNED".equals(owner.moduleStatus())) return null;
    var version = repository.findVersion(prior.version()).orElseThrow();
    if (!TechnicalDataSubmissionState.submitted(version.getVersionStatus())) return null;
    if (!Objects.equals(version.getProductId(),owner.productId()) || !Objects.equals(version.getContentFingerprint(),prior.fingerprint()))
      throw new IllegalStateException("已认可包装的冻结版本校验失败");
    return new TechnicalDataCostingSources.Source("PACKAGE",repository.findTask(owner.taskId()).orElseThrow(),
        repository.findProduct(owner.productId()).orElseThrow(),version,prior.module());
  }
  private record Accepted(long module, long version, String fingerprint) {}
}
