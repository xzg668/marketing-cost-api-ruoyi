package com.sanhua.marketingcost.service.costing;

import com.sanhua.marketingcost.dto.*;
import java.math.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Bounded, exact-ID source reads: one batch per raw table, never a whole CMS table scan. */
@Service
public class CmsCompositionReader {
  private final JdbcTemplate jdbc;
  public CmsCompositionReader(JdbcTemplate jdbc) { this.jdbc = jdbc; }
  private static final Map<String,String> QUERIES = Map.of(
      "cms_product_subject_cost_raw", "SELECT id,period,parent_code,parent_name,material_price AS cents,material_price_yuan AS yuan,path,"
          + "second_subject_code AS subject_code,second_subject_name AS subject_name,import_batch_id,row_no,source_row_id "
          + "FROM cms_product_subject_cost_raw WHERE business_unit_type=? AND id IN (%s)",
      "cms_workshop_labor_raw", "SELECT id,period,parent_code,parent_name,working_cost_cent AS cents,working_cost_yuan AS yuan,path,"
          + "last_unit_code AS workshop_code,last_unit_name AS workshop_name,working_hours,funding,import_batch_id,row_no,source_row_id "
          + "FROM cms_workshop_labor_raw WHERE business_unit_type=? AND id IN (%s)");

  public void complete(List<CostRunCostItemDto> items, String unit) {
    var wanted = new TreeMap<String,Set<Long>>();
    for (var item : items) if (item.getComposition() != null)
      for (var source : item.getComposition().sources())
        if (source.table() != null && QUERIES.containsKey(source.table())) wanted.computeIfAbsent(source.table(), ignored -> new TreeSet<>()).addAll(ids(source.rowIds()));
    var raw = new HashMap<String,Map<Long,Map<String,Object>>>();
    wanted.forEach((table, values) -> {
      var byId = new HashMap<Long,Map<String,Object>>(); var all = new ArrayList<>(values);
      for (int start=0; start<all.size(); start+=256) {
        var batch = all.subList(start, Math.min(start+256, all.size()));
        var args = new ArrayList<Object>(); args.add(unit); args.addAll(batch);
        String sql = QUERIES.get(table).formatted(String.join(",", Collections.nCopies(batch.size(), "?")));
        for (var row : jdbc.queryForList(sql, args.toArray())) byId.put(((Number)row.get("id")).longValue(), row);
      }
      raw.put(table, byId);
    });
    for (var item : items) {
      var composition = item.getComposition(); if (composition == null) continue;
      var details = new ArrayList<>(composition.rows()); var notes = new ArrayList<String>();
      for (var source : composition.sources()) {
        var sourceIds = ids(source.rowIds());
        var rawTotal = BigDecimal.ZERO; boolean complete = !sourceIds.isEmpty();
        if (sourceIds.isEmpty()) notes.add("来源未记录原始明细ID：" + source.parentCode() + " " + source.subjectCode());
        for (Long id : sourceIds) {
          var row = raw.getOrDefault(source.table(), Map.of()).get(id);
          var detail = new LinkedHashMap<String,String>();
          detail.put("sourceTable", value(source.table())); detail.put("sourceId", String.valueOf(id));
          detail.put("parentCode", value(source.parentCode())); detail.put("period", value(source.period()));
          detail.put("subjectCode", value(source.subjectCode()));
          if (row == null || !Objects.equals(value(row.get("parent_code")), value(source.parentCode()))
              || !Objects.equals(value(row.get("period")), value(source.period()))) {
            detail.put("note", "原始来源缺失或归属不匹配，不能作为本金额的完整明细"); notes.add("存在缺失或不匹配的原始来源");
            complete = false;
          } else {
            detail.put("parentName", value(row.get("parent_name")));
            detail.put("subjectName", value(row.get("subject_name")));
            if (detail.get("subjectCode").isBlank()) detail.put("subjectCode", value(row.get("subject_code")));
            detail.put("originalCent", value(row.get("cents"))); detail.put("amountYuan", value(row.get("yuan")));
            detail.put("path", value(row.get("path"))); detail.put("workshopCode", value(row.get("workshop_code")));
            detail.put("workshopName", value(row.get("workshop_name"))); detail.put("workingHours", value(row.get("working_hours")));
            detail.put("funding", value(row.get("funding"))); detail.put("sourceBatch", value(row.get("import_batch_id")));
            detail.put("sourceRow", value(row.get("row_no"))); detail.put("cmsRowId", value(row.get("source_row_id")));
            if (row.get("yuan") instanceof BigDecimal amount) rawTotal = rawTotal.add(amount);
            else complete = false;
          }
          details.add(detail);
        }
        if (complete && source.amountYuan() != null && !source.amountYuan().isBlank()
            && rawTotal.setScale(6,RoundingMode.HALF_UP).compareTo(new BigDecimal(source.amountYuan())) != 0)
          notes.add("原始明细合计 " + rawTotal.toPlainString() + " 元与生效来源 " + source.amountYuan()
              + " 元不同，请核对来源汇总口径；采用金额保持原保存值");
      }
      item.setComposition(composition.withRows(details, String.join("；", new LinkedHashSet<>(notes))));
    }
  }
  private static String value(Object value) { return value == null ? "" : value instanceof BigDecimal d ? d.toPlainString() : value.toString(); }
  private static List<Long> ids(String value) {
    if (value == null || value.isBlank()) return List.of();
    var result = new LinkedHashSet<Long>();
    for (String entry : value.replace("[", "").replace("]", "").split(",")) {
      String id = entry.trim(); if (id.matches("[0-9]{1,18}")) result.add(Long.parseLong(id));
    }
    return List.copyOf(result);
  }
}
