package com.sanhua.marketingcost.service.pricing.inquiry;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingState;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Withdraw an import and its reusable adopted prices atomically; saved costs stay immutable. */
@Service
public class InquiryImportVoidService {
  private final InquiryPriceRepository repository;
  private final JdbcTemplate jdbc;
  private final MonthlyPriceDocumentCodec codec;
  private final TechnicalDataPendingState pending;
  @org.springframework.beans.factory.annotation.Autowired
  private com.sanhua.marketingcost.service.pricing.monthly.VoidedSourceMonthlyPrices monthly;

  public InquiryImportVoidService(InquiryPriceRepository repository, JdbcTemplate jdbc,
      MonthlyPriceDocumentCodec codec, TechnicalDataPendingState pending) {
    this.repository = repository;
    this.jdbc = jdbc;
    this.codec = codec;
    this.pending = pending;
  }

  public record Result(long batchId, int voidedRows, int retiredMonthlyPrices) {}
  private record Saved(long id, JsonNode source, Set<Long> dependencies) {}

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public void voidRow(String unit,long id,String actor) {
    InquiryPriceService.unit(unit);
    if (id<=0 || actor==null || actor.isBlank() || actor.length()>64)
      throw new IllegalArgumentException("无效询价或操作人");
    repository.lock(unit);
    var rows=jdbc.queryForList("""
        SELECT r.row_voided,b.status FROM lp_inquiry_rule r JOIN lp_inquiry_import_batch b ON b.id=r.batch_id
        WHERE r.business_unit_type=? AND r.id=? FOR UPDATE
        """,unit,id);
    if (rows.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.NOT_FOUND,"询价记录不存在");
    var row=rows.getFirst();
    if (((Number)row.get("row_voided")).intValue()==1 || "VOIDED".equals(row.get("status"))) return;
    jdbc.update("UPDATE lp_inquiry_rule SET row_voided=1 WHERE business_unit_type=? AND id=?",unit,id);
    monthly.inquiryRow(unit,id);
    pending.changed(new TechnicalDataPendingState.Change(Set.of("PRICE"),unit,Set.of(),Set.of()));
  }

  @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public Result voidBatch(String unit, long batchId, String actor) {
    InquiryPriceService.unit(unit);
    if (batchId <= 0 || actor == null || actor.isBlank() || actor.length() > 64)
      throw new IllegalArgumentException("无效询价导入记录或操作人");
    repository.lock(unit);
    var batches = jdbc.queryForList("SELECT status,row_count FROM lp_inquiry_import_batch WHERE business_unit_type=? AND id=? FOR UPDATE", unit, batchId);
    if (batches.isEmpty()) throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.NOT_FOUND, "询价导入记录不存在");
    if ("VOIDED".equals(batches.getFirst().get("status"))) return new Result(batchId, 0, 0);
    jdbc.update("UPDATE lp_inquiry_import_batch SET status='VOIDED',voided_by=?,voided_at=CURRENT_TIMESTAMP(6) WHERE business_unit_type=? AND id=?", actor, unit, batchId);
    var saved = jdbc.query("""
        SELECT id,source_evidence_json,first_use_json FROM lp_material_monthly_price
        WHERE business_unit_type=? AND disabled=0 ORDER BY family_hash,applicability_hash,id FOR UPDATE
        """, (row, index) -> {
      var dependencies = new LinkedHashSet<Long>();
      codec.decode(row.getString("first_use_json"), JsonNode.class).path("dependencies")
          .forEach(value -> dependencies.add(value.path("monthlyPriceId").asLong()));
      return new Saved(row.getLong("id"), codec.decode(row.getString("source_evidence_json"), JsonNode.class), dependencies);
    }, unit);
    var retired = new LinkedHashSet<Long>();
    for (var row : saved) {
      if (!"INQUIRY".equals(row.source().path("sourceKind").asText())) continue;
      var source = row.source().path("source");
      if (fromBatch(source.path("selected"), batchId)) retired.add(row.id());
      // A retained supplier choice also depends on the competing quotes it compared.
      source.path("candidates").forEach(candidate -> { if (fromBatch(candidate, batchId)) retired.add(row.id()); });
    }
    boolean changed;
    do {
      changed = false;
      for (var row : saved)
        if (!retired.contains(row.id()) && row.dependencies().stream().anyMatch(retired::contains))
          changed |= retired.add(row.id());
    } while (changed);
    for (long id : retired)
      jdbc.update("UPDATE lp_material_monthly_price SET disabled=1 WHERE id=? AND disabled=0", id);
    // Specification-only requests have no material code; dirty the unit's price waiting checks.
    pending.changed(new TechnicalDataPendingState.Change(Set.of("PRICE"), unit, Set.of(), Set.of()));
    return new Result(batchId, ((Number) batches.getFirst().get("row_count")).intValue(), retired.size());
  }

  private static boolean fromBatch(JsonNode selected, long batchId) {
    return selected.path("original").path("batchId").asLong() == batchId;
  }
}
