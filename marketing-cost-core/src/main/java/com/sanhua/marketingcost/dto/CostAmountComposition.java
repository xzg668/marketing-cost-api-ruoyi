package com.sanhua.marketingcost.dto;

import java.util.*;

/** Audit evidence only; never an input to the cost formula. Decimal strings retain CMS precision. */
public record CostAmountComposition(String origin, String formula, String sourceAmount,
    String excludedAmount, String note, List<Source> sources, List<Map<String, String>> rows) {
  public record Source(String table, String rowIds, String parentCode, String period,
      String subjectCode, String amountYuan) {}
  public CostAmountComposition {
    sources = sources == null ? List.of() : List.copyOf(sources);
    rows = rows == null ? List.of() : rows.stream().map(row -> Collections.unmodifiableMap(
        new LinkedHashMap<>(row))).toList();
  }
  public CostAmountComposition withRows(List<Map<String, String>> details, String extraNote) {
    return new CostAmountComposition(origin, formula, sourceAmount, excludedAmount,
        String.join("；", java.util.stream.Stream.of(note, extraNote)
            .filter(value -> value != null && !value.isBlank()).toList()), sources, details);
  }
  public CostAmountComposition currentReference() {
    return new CostAmountComposition("CURRENT_REFERENCE", formula, sourceAmount, excludedAmount,
        "此旧版本未保存当时的CMS分项，以下是当前来源核对资料；保存金额不变。" + (note == null ? "" : note), sources, rows);
  }
}
