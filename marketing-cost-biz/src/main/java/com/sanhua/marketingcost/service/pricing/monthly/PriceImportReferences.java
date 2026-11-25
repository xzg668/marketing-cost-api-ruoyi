package com.sanhua.marketingcost.service.pricing.monthly;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import java.util.TreeSet;

/** Import identities captured in both the winner and the prices compared during selection. */
final class PriceImportReferences {
  private PriceImportReferences() {}

  static Set<Long> inquiryRows(JsonNode evidence) {
    var source=evidence.path("source");
    var ids=new TreeSet<Long>();
    add(ids,source.path("selected").path("id"));
    source.path("candidates").forEach(candidate->add(ids,candidate.path("id")));
    return ids;
  }

  static Set<Long> linkedRows(JsonNode evidence) {
    var source=evidence.path("source");
    var ids=new TreeSet<Long>();
    add(ids,source.path("selected").path("id"));
    for(String field:java.util.List.of("candidates","candidateRules"))
      source.path(field).forEach(candidate->add(ids,candidate.path("id")));
    return ids;
  }

  static Set<Long> invoice(JsonNode evidence) {
    var source=evidence.path("source");
    var ids=new TreeSet<Long>();
    add(ids,source.path("selected").path("batch_id"));
    source.path("candidates").forEach(row->add(ids,row.path("batch_id")));
    return ids;
  }

  static java.util.List<JsonNode> invoiceRows(JsonNode evidence) {
    var source=evidence.path("source");
    var rows=new java.util.ArrayList<JsonNode>();
    rows.add(source.path("selected"));
    source.path("candidates").forEach(rows::add);
    return rows;
  }

  static Set<Long> range(JsonNode evidence) {
    var source=evidence.path("source");
    var ids=new TreeSet<Long>();
    add(ids,source.path("selected").path("sourceUploadBatchId"));
    add(ids,source.path("selectedRule").path("sourceUploadBatchId"));
    source.path("candidates").forEach(row->add(ids,row.path("sourceUploadBatchId")));
    source.path("originalBatches").forEach(row->add(ids,row.path("id")));
    return ids;
  }

  static long selectedRangeRow(JsonNode evidence) {
    var source=evidence.path("source");
    return switch(evidence.path("sourceKind").asText()) {
      case "RANGE_FACTOR" -> source.path("selectedBandId").asLong();
      case "RANGE_LEGACY_FACTOR" -> source.path("calculation").path("resultRefId").asLong();
      case "RANGE_QTY" -> source.path("selected").path("id").asLong();
      default -> 0;
    };
  }

  static boolean usesRangeRow(JsonNode evidence,long id) {
    if(selectedRangeRow(evidence)==id)return true;
    if(!"RANGE_FACTOR".equals(evidence.path("sourceKind").asText()))return false;
    for(var band:evidence.path("source").path("bands"))if(band.path("id").asLong()==id)return true;
    return false;
  }

  static Set<String> scrap(JsonNode evidence) {
    var source=evidence.path("source");
    var refs=new TreeSet<String>();
    scrap(refs,source.path("selected"));
    source.path("candidates").forEach(row->scrap(refs,row.path("original")));
    return refs;
  }

  static java.util.List<JsonNode> scrapRows(JsonNode evidence) {
    var source=evidence.path("source");
    var rows=new java.util.ArrayList<JsonNode>();
    rows.add(source.path("selected"));
    source.path("candidates").forEach(row->rows.add(row.path("original")));
    return rows;
  }

  private static void scrap(Set<String> refs,JsonNode row) {
    String batch=row.path("importBatchNo").asText("");
    if ("IMPORT".equals(row.path("originType").asText()) && !batch.isBlank()) refs.add(batch);
  }

  private static void add(Set<Long> ids,JsonNode node) {
    long id=node.asLong();
    if(id>0)ids.add(id);
  }
}
