package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.function.Predicate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Stop reuse of adopted prices while keeping historical prices readable by ID. */
@Component
public class VoidedSourceMonthlyPrices {
  private record Adopted(long id, boolean fromImport, Set<Long> dependencies) {}

  private final NamedParameterJdbcTemplate jdbc;
  private final MonthlyPriceDocumentCodec codec;

  public VoidedSourceMonthlyPrices(NamedParameterJdbcTemplate jdbc, MonthlyPriceDocumentCodec codec) {
    this.jdbc = jdbc;
    this.codec = codec;
  }

  public void household(String unit, Collection<Long> ids) {
    retire(unit, Set.of("HOUSEHOLD_INQUIRY"), ids, Set.of());
  }

  public void settlement(String unit, Collection<Long> ids) {
    var blocked = new HashSet<>(jdbc.queryForList("""
        SELECT m.id FROM lp_material_monthly_price m
        WHERE m.business_unit_type=:unit AND m.disabled=0 AND m.source_type IN ('SETTLE_FIXED','SETTLE_LINKED')
          AND (SELECT p.id FROM lp_settlement_price p WHERE p.business_unit_type=m.business_unit_type COLLATE utf8mb4_unicode_ci
            AND p.material_code=m.material_code COLLATE utf8mb4_unicode_ci
            AND p.price_type=m.source_type COLLATE utf8mb4_unicode_ci AND p.active=1 AND p.price_month<=m.pricing_month COLLATE utf8mb4_unicode_ci
            ORDER BY p.price_month DESC,p.revision_no DESC LIMIT 1) IN (:ids)
        """, Map.of("unit",unit,"ids",ids), Long.class));
    retire(unit, Set.of("SETTLE_FIXED", "SETTLE_LINKED"), ids, blocked);
  }

  public void invoice(String unit,long batch) {
    retire(unit,Set.of("INVOICE"),evidence->PriceImportReferences.invoice(evidence).contains(batch),Set.of());
  }

  public void invoiceRow(String unit,long id,long batch) {
    retire(unit,Set.of("INVOICE"),evidence->PriceImportReferences.invoiceRows(evidence)
        .stream().anyMatch(row->row.path("id").asLong()==id && row.path("batch_id").asLong()==batch),Set.of());
  }

  public void inquiryRow(String unit,long id) {
    retire(unit,Set.of("INQUIRY"),evidence->PriceImportReferences.inquiryRows(evidence).contains(id),Set.of());
  }

  public void linkedRow(String unit,long id) {
    retire(unit,Set.of("LINKED","LINKED_LEGACY"),evidence->PriceImportReferences.linkedRows(evidence).contains(id),Set.of());
  }

  public void settlementRow(String unit,long id) {
    retire(unit,Set.of("SETTLE_FIXED","SETTLE_LINKED"),Set.of(id),Set.of());
  }

  public void scrapRow(String unit,long id,int ruleRevision) {
    retire(unit,Set.of("SCRAP_PRICE"),evidence->PriceImportReferences.scrapRows(evidence)
        .stream().anyMatch(row->row.path("id").asLong()==id && row.path("ruleRevision").asInt()==ruleRevision),Set.of());
  }

  public void scrap(String unit,String batch) {
    retire(unit,Set.of("SCRAP_PRICE"),evidence->PriceImportReferences.scrap(evidence).contains(batch),Set.of());
  }

  public void range(String unit,Collection<Long> batches) {
    retire(unit,Set.of("RANGE_FACTOR","RANGE_LEGACY_FACTOR"),evidence->PriceImportReferences.range(evidence).stream().anyMatch(batches::contains),Set.of());
  }

  public void rangeRow(String unit,long id) {
    retire(unit,Set.of("RANGE_FACTOR","RANGE_LEGACY_FACTOR","RANGE_QTY"),
        evidence->PriceImportReferences.usesRangeRow(evidence,id),Set.of());
  }

  private void retire(String unit, Set<String> types, Collection<Long> ids, Set<Long> blocked) {
    if (!ids.isEmpty()) retire(unit,types,null,blocked,Set.copyOf(ids));
  }

  private void retire(String unit, Set<String> types, Predicate<JsonNode> fromBatch, Set<Long> blocked) {
    retire(unit,types,fromBatch,blocked,Set.of());
  }

  private void retire(String unit, Set<String> types, Predicate<JsonNode> fromBatch, Set<Long> blocked, Set<Long> sourceIds) {
    var rows = jdbc.query("""
        SELECT id,source_type,source_record_id,first_use_json,source_evidence_json
        FROM lp_material_monthly_price
        WHERE business_unit_type=:unit AND disabled=0
        ORDER BY family_hash,applicability_hash,id FOR UPDATE
        """, Map.of("unit", unit), (row, index) -> {
      var dependencies = new HashSet<Long>();
      codec.decode(row.getString("first_use_json"), JsonNode.class).path("dependencies")
          .forEach(value -> dependencies.add(value.path("monthlyPriceId").asLong()));
      return new Adopted(row.getLong("id"),
          blocked.contains(row.getLong("id")) || (types.contains(row.getString("source_type"))
              && (fromBatch == null ? sourceIds.contains(row.getLong("source_record_id"))
                  : fromBatch.test(codec.decode(row.getString("source_evidence_json"),JsonNode.class)))), dependencies);
    });
    var dependents = new HashMap<Long, List<Long>>();
    var retired = new LinkedHashSet<Long>();
    for (var row : rows) {
      if (row.fromImport()) retired.add(row.id());
      for (long dependency : row.dependencies())
        dependents.computeIfAbsent(dependency, ignored -> new ArrayList<>()).add(row.id());
    }
    var queue = new ArrayDeque<>(retired);
    while (!queue.isEmpty())
      for (long id : dependents.getOrDefault(queue.removeFirst(), List.of()))
        if (retired.add(id)) queue.addLast(id);
    var updates = retired.stream().map(id -> Map.of("id", id)).toList();
    if (!updates.isEmpty())
      jdbc.batchUpdate("UPDATE lp_material_monthly_price SET disabled=1 WHERE id=:id AND disabled=0",
          updates.toArray(Map[]::new));
  }
}
