package com.sanhua.marketingcost.service.technicaldata;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 唯一办理来源的持久化。占用和业务写入使用调用方同一事务。 */
@Repository
public class TechnicalDataSharedModuleRepository {
  public record Owner(long moduleId, long productId, long taskId, long quoteItemId,
      String materialNo, String moduleType, String taskStatus, String moduleStatus,
      Long assigneeId, String assigneeName, Long versionId, String versionStatus,
      String businessUnit, String organization, String accountingMonth) {}

  private static final String OWNER_SELECT = """
      SELECT m.id,m.product_id,p.task_id,p.oa_form_item_id,p.material_no,m.module_type,
        t.task_status,m.module_status,m.assignee_user_id,m.assignee_name,m.current_version_id,
        v.version_status,t.business_unit_type,t.applicable_org_code,p.accounting_month
      FROM lp_quote_tech_module m JOIN lp_quote_tech_product p ON p.id=m.product_id
      JOIN lp_quote_tech_task t ON t.id=p.task_id
      LEFT JOIN lp_quote_tech_data_version v ON v.id=m.current_version_id
      """;
  private final JdbcTemplate jdbc;

  public TechnicalDataSharedModuleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public Owner module(long productId, String type) {
    return one(jdbc.query(OWNER_SELECT + " WHERE p.id=? AND m.module_type=?", this::owner, productId, type));
  }

  public Owner find(String identity, String type) {
    return one(jdbc.query(OWNER_SELECT + """
        JOIN lp_quote_tech_shared_module s ON s.owner_module_id=m.id
        WHERE s.product_identity=? AND s.module_type=?
        """, this::owner, identity, type));
  }

  public long lockOrInsert(String identity, String type, long moduleId) {
    jdbc.update("""
        INSERT INTO lp_quote_tech_shared_module(product_identity,module_type,owner_module_id) VALUES(?,?,?)
        ON DUPLICATE KEY UPDATE owner_module_id=lp_quote_tech_shared_module.owner_module_id
        """, identity, type, moduleId);
    return jdbc.queryForObject("""
        SELECT owner_module_id FROM lp_quote_tech_shared_module
        WHERE product_identity=? AND module_type=? FOR UPDATE
        """, Long.class, identity, type);
  }

  /** 检出上线前已存在的重复办理，不能靠插入顺序选中一个历史来源。 */
  public List<Owner> existing(String identity, String type) {
    return jdbc.query(OWNER_SELECT + """
        WHERE p.content_schema_version=2 AND t.task_status<>'CANCELLED' AND m.module_type=?
          AND (m.assignee_user_id IS NOT NULL OR m.current_version_id IS NOT NULL)
          AND BINARY CASE WHEN NULLIF(TRIM(p.material_no),'') IS NULL THEN CONCAT('QUOTE_LINE:',p.oa_form_item_id)
                   ELSE CONCAT('MATERIAL:',TRIM(p.material_no)) END = BINARY ?
        ORDER BY m.id
        """, this::owner, type, identity);
  }

  public void transfer(String identity, String type, long expectedOwner, long nextOwner) {
    if (jdbc.update("""
        UPDATE lp_quote_tech_shared_module SET owner_module_id=?,updated_at=NOW(6)
        WHERE product_identity=? AND module_type=? AND owner_module_id=?
        """, nextOwner, identity, type, expectedOwner) != 1) {
      throw new IllegalStateException("唯一补录来源已变化，请重新检查");
    }
  }

  private Owner one(List<Owner> values) { return values.isEmpty() ? null : values.getFirst(); }

  private Owner owner(java.sql.ResultSet row, int index) throws java.sql.SQLException {
    return new Owner(row.getLong("id"), row.getLong("product_id"), row.getLong("task_id"),
        row.getLong("oa_form_item_id"), row.getString("material_no"), row.getString("module_type"),
        row.getString("task_status"), row.getString("module_status"), row.getObject("assignee_user_id", Long.class),
        row.getString("assignee_name"), row.getObject("current_version_id", Long.class), row.getString("version_status"),
        row.getString("business_unit_type"), row.getString("applicable_org_code"), row.getString("accounting_month"));
  }
}
