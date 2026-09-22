package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceOwner;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceRequirement;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 价格清单项与整产品分派共用原 PRICE 模块，只有料号占用需要单独加锁。 */
@Repository
public class TechnicalDataPriceOwnership {
  private final JdbcTemplate jdbc;
  public TechnicalDataPriceOwnership(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public TechnicalDataPriceOwner find(String material) {
    var rows = jdbc.query("""
        SELECT c.material_code,c.owner_module_id,m.product_id,p.task_id,m.assignee_name,m.module_status,
          t.task_status,m.current_version_id,c.organization_code,c.business_unit_type,c.price_unit,c.currency
        FROM lp_quote_tech_price_claim c JOIN lp_quote_tech_module m ON m.id=c.owner_module_id
        JOIN lp_quote_tech_product p ON p.id=m.product_id JOIN lp_quote_tech_task t ON t.id=p.task_id
        WHERE c.material_code=?
        """, (r,n) -> new TechnicalDataPriceOwner(r.getString(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getString(5),
            r.getString(6),r.getString(7),r.getObject(8,Long.class),r.getString(9),r.getString(10),r.getString(11),r.getString(12)), material);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void require(long moduleId, String businessUnit, List<TechnicalDataPriceRequirement> requirements) {
    // 所有入口按料号排序加锁，两个产品含多项相同料号时保持同一锁序。
    for (var item : requirements.stream().filter(r -> "MISSING".equals(r.status()))
        .sorted(java.util.Comparator.comparing(TechnicalDataPriceRequirement::materialNo)).toList()) {
      jdbc.update("""
          INSERT INTO lp_quote_tech_price_claim(material_code,owner_module_id,organization_code,business_unit_type,price_unit,currency)
          VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE owner_module_id=lp_quote_tech_price_claim.owner_module_id
          """, item.materialNo(), moduleId, item.organizationCode(), businessUnit, item.unit(), item.currency());
      long current = jdbc.queryForObject("SELECT owner_module_id FROM lp_quote_tech_price_claim WHERE material_code=? FOR UPDATE", Long.class, item.materialNo());
      var owner = find(item.materialNo());
      if (current != moduleId) {
        if (!"CANCELLED".equals(owner.taskStatus()) || "APPROVED".equals(owner.moduleStatus())) {
          throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.SHARED_MODULE_CONFLICT,
              item.materialNo() + " 已由" + Objects.toString(owner.assigneeName(), "原技术员") + "办理，请查看原任务 " + owner.taskId() + "，不能重复补价");
        }
        jdbc.update("""
            UPDATE lp_quote_tech_price_claim SET owner_module_id=?,organization_code=?,business_unit_type=?,price_unit=?,currency=?
            WHERE material_code=? AND owner_module_id=?
            """, moduleId,item.organizationCode(),businessUnit,item.unit(),item.currency(),item.materialNo(),current);
      } else if (!Objects.equals(owner.organizationCode(), item.organizationCode()) || !Objects.equals(owner.businessUnit(), businessUnit)
          || !Objects.equals(owner.unit(), item.unit()) || !Objects.equals(owner.currency(), item.currency())) {
        throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, item.materialNo() + " 的适用组织或单位已变化，请核实原补录资料");
      }
    }
  }
}
