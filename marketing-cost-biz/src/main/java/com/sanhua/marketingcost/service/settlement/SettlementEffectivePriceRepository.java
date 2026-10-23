package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class SettlementEffectivePriceRepository {
  public record Result(long id, String effectiveMonth, int revisionNo, long ruleId, long financeBatchId,
      BigDecimal unitPrice, String priceMode, String calculationJson, LocalDateTime createdAt) {}
  public record RevisionKey(String material, String type, String month) {}
  public record Existing(Set<String> fingerprints, Map<RevisionKey, Integer> revisions) {}
  private static final RowMapper<Result> ROW = (r, n) -> new Result(r.getLong("id"), r.getString("effective_month"),
      r.getInt("revision_no"), r.getLong("rule_id"), r.getLong("finance_batch_id"), r.getBigDecimal("unit_price"),
      r.getString("price_mode"), r.getString("calculation_json"), r.getTimestamp("created_at").toLocalDateTime());
  private final NamedParameterJdbcTemplate jdbc;

  public SettlementEffectivePriceRepository(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

  /** 首次发布也有稳定锁行；锁随调用方事务提交/回滚释放。必须先于读取发布输入调用。 */
  public void lock(String businessUnit) {
    requireTransaction();
    jdbc.update("INSERT INTO lp_settlement_generation_scope(business_unit_type) VALUES(:unit) "
        + "ON DUPLICATE KEY UPDATE business_unit_type=business_unit_type", Map.of("unit", businessUnit));
  }

  public Existing existing(String unit) {
    Set<String> fingerprints = new HashSet<>();
    Map<RevisionKey, Integer> revisions = new HashMap<>();
    jdbc.query("SELECT input_fingerprint,material_code,price_type,effective_month,revision_no "
        + "FROM lp_settlement_effective_price WHERE business_unit_type=:unit", Map.of("unit", unit), r -> {
      fingerprints.add(r.getString("input_fingerprint"));
      revisions.merge(new RevisionKey(r.getString("material_code"), r.getString("price_type"), r.getString("effective_month")),
          r.getInt("revision_no"), Math::max);
    });
    return new Existing(fingerprints, revisions);
  }

  public void insert(List<MapSqlParameterSource> rows) {
    requireTransaction();
    for (int start = 0; start < rows.size(); start += 100) {
      jdbc.batchUpdate("""
          INSERT INTO lp_settlement_effective_price(business_unit_type,material_code,price_type,effective_month,
            revision_no,rule_id,rule_month,rule_revision,finance_batch_id,finance_month,finance_revision,
            price_mode,unit_price,calculator_version,input_fingerprint,calculation_json,created_by)
          VALUES(:unit,:material,:type,:month,:revision,:ruleId,:ruleMonth,:ruleRevision,:financeId,:financeMonth,
            :financeRevision,:mode,:price,:calculator,:fingerprint,:evidence,:actor)
          """, rows.subList(start, Math.min(rows.size(), start + 100)).toArray(MapSqlParameterSource[]::new));
    }
  }

  public Map<Long, Result> effective(SettlementScope scope, Collection<Long> ruleIds, long financeBatchId) {
    if (ruleIds.isEmpty()) return Map.of();
    var params = new MapSqlParameterSource("unit", scope.businessUnitType()).addValue("month", scope.priceMonth())
        .addValue("ids", ruleIds).addValue("finance", financeBatchId);
    var rows = jdbc.query("SELECT ranked.* FROM (SELECT p.*,ROW_NUMBER() OVER(PARTITION BY rule_id "
        + "ORDER BY effective_month DESC,revision_no DESC) ranking FROM lp_settlement_effective_price p "
        + "WHERE business_unit_type=:unit AND effective_month<=:month AND rule_id IN (:ids) "
        + "AND finance_batch_id IN (0,:finance)) ranked WHERE ranking=1", params, ROW);
    Map<Long, Result> result = new HashMap<>();
    rows.forEach(row -> result.put(row.ruleId(), row));
    return result;
  }

  public List<Result> history(String unit, String material, String type, String month) {
    return jdbc.query("SELECT * FROM lp_settlement_effective_price WHERE business_unit_type=:unit "
        + "AND material_code=:material AND price_type=:type AND effective_month<=:month ORDER BY effective_month DESC,revision_no DESC",
        Map.of("unit", unit, "material", material, "type", type, "month", month), ROW);
  }

  private void requireTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
      throw new IllegalStateException("结算结果必须在原稿/财务发布事务内生成");
  }
}
