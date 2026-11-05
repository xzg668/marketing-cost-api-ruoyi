package com.sanhua.marketingcost.service.financefactor;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyFinanceReferences;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Retire current selections atomically with financial publication; saved prices/links stay exact. */
@Service
public class FinanceMonthlyPriceRefreshService {
  private final JdbcTemplate jdbc;
  private final MonthlyPriceDocumentCodec codec;

  public FinanceMonthlyPriceRefreshService(JdbcTemplate jdbc, MonthlyPriceDocumentCodec codec) {
    this.jdbc = jdbc;
    this.codec = codec;
  }

  private record Saved(long id, JsonNode source, Set<Long> dependencies) {}

  @Transactional(propagation = Propagation.MANDATORY)
  public int refresh(FinanceFactorImportContext context, long publicationId) {
    // A later finance month has its own basis. Cross-organization shared sources are evaluated
    // against the producer's financial references, never the quotation consumer's organization.
    var next = jdbc.queryForObject("""
        SELECT MIN(price_month) FROM lp_factor_upload_batch
        WHERE business_unit_type=? AND price_org_code=? AND import_type='FINANCE_BASE'
          AND status='PUBLISHED' AND published_at IS NOT NULL AND price_month>?
        """, String.class, context.businessUnitType(), context.priceOrgCode(), context.priceMonth());
    var rows = jdbc.query("""
        SELECT id,source_evidence_json,first_use_json FROM lp_material_monthly_price
        WHERE business_unit_type=? AND pricing_month>=? AND (? IS NULL OR pricing_month<?)
          AND superseded_by_finance_batch_id IS NULL ORDER BY family_hash,applicability_hash,id
        FOR UPDATE
        """, (rs, number) -> {
      var dependencies = new LinkedHashSet<Long>();
      var first = codec.decode(rs.getString("first_use_json"), JsonNode.class);
      first.path("dependencies").forEach(value -> dependencies.add(value.path("monthlyPriceId").asLong()));
      return new Saved(rs.getLong("id"), codec.decode(rs.getString("source_evidence_json"), JsonNode.class), dependencies);
    }, context.businessUnitType(), context.priceMonth(), next, next);
    var retired = new LinkedHashSet<Long>();
    for (var row : rows) {
      if (MonthlyFinanceReferences.read(row.source()).stream().anyMatch(reference ->
          reference.organization().equals(context.priceOrgCode()) && reference.batchId() != publicationId))
        retired.add(row.id());
    }
    // A legacy linked formula can use another adopted monthly scrap price.
    boolean changed;
    do {
      changed = false;
      for (var row : rows)
        if (!retired.contains(row.id()) && row.dependencies().stream().anyMatch(retired::contains))
          changed |= retired.add(row.id());
    } while (changed);
    int count = 0;
    for (var id : retired)
      count += jdbc.update("UPDATE lp_material_monthly_price SET superseded_by_finance_batch_id=? WHERE id=? AND superseded_by_finance_batch_id IS NULL", publicationId, id);
    return count;
  }
}
