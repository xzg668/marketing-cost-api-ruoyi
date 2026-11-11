package com.sanhua.marketingcost.service.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.service.pricing.SupplierRatiosImported;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Actual supplier ratios replace provisional selections; historical prices remain readable by ID. */
@Component
public class SupplierRatioMonthlyPriceRefresh {
  private final JdbcTemplate jdbc;
  private final MonthlyPriceDocumentCodec codec;

  public SupplierRatioMonthlyPriceRefresh(JdbcTemplate jdbc, MonthlyPriceDocumentCodec codec) {
    this.jdbc = jdbc;
    this.codec = codec;
  }

  private record Saved(long id, String material, JsonNode source, Set<Long> dependencies) {}

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void imported(SupplierRatiosImported event) {
    if (event.materialCodes().isEmpty()) return;
    var actual = new LinkedHashSet<>(jdbc.queryForList("""
        SELECT DISTINCT material_code FROM lp_supplier_supply_ratio
        WHERE business_unit_type=? AND deleted=0 AND is_active=1 AND supply_ratio IS NOT NULL
          AND (effective_from IS NULL OR effective_from<=?)
          AND (effective_to IS NULL OR effective_to>=?)
        """, String.class, event.businessUnitType(), CostPricingPeriodUtils.currentPricingDate(),
        CostPricingPeriodUtils.currentPricingDate()));
    actual.retainAll(event.materialCodes());
    if (actual.isEmpty()) return;
    // Keep the existing month's saved costs and immutable price evidence. Only future selection
    // stops reusing the old supplier choice; the next costing adopts the actual-ratio price.
    var rows = jdbc.query("""
        SELECT id,material_code,source_evidence_json,first_use_json FROM lp_material_monthly_price
        WHERE business_unit_type=? AND pricing_month>=? AND disabled=0
          AND superseded_by_finance_batch_id IS NULL ORDER BY family_hash,applicability_hash,id
        FOR UPDATE
        """, (rs, index) -> {
      var dependencies = new LinkedHashSet<Long>();
      codec.decode(rs.getString("first_use_json"), JsonNode.class).path("dependencies")
          .forEach(value -> dependencies.add(value.path("monthlyPriceId").asLong()));
      return new Saved(rs.getLong("id"), rs.getString("material_code"),
          codec.decode(rs.getString("source_evidence_json"), JsonNode.class), dependencies);
    }, event.businessUnitType(), CostPricingPeriodUtils.currentPricingMonth());
    var retired = new LinkedHashSet<Long>();
    for (var row : rows)
      if (actual.contains(row.material()) && row.source().path("source").hasNonNull("selectionRule"))
        retired.add(row.id());
    boolean changed;
    do {
      changed = false;
      for (var row : rows)
        if (!retired.contains(row.id()) && row.dependencies().stream().anyMatch(retired::contains))
          changed |= retired.add(row.id());
    } while (changed);
    for (long id : retired)
      jdbc.update("UPDATE lp_material_monthly_price SET disabled=1 WHERE id=? AND disabled=0", id);
  }
}
