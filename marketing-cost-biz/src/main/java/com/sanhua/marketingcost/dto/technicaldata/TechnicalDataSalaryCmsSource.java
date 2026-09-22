package com.sanhua.marketingcost.dto.technicaldata;

import java.math.BigDecimal;
import java.util.List;

/** 年度工资来源；两项各自保留期间和原始行，不把工时原表的“分”换算套在元金额上。 */
public record TechnicalDataSalaryCmsSource(
    String materialNo, String name, String model, int costYear, String businessUnitType,
    String fingerprint, Item direct, Item indirect, List<String> issues) {
  public TechnicalDataSalaryCmsSource { issues = List.copyOf(issues); }

  public record Item(Long sourceId, String laborType, String subjectCode, String subjectName,
      String sourcePeriod, String sourceTable, String sourceRowIds,
      @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) BigDecimal amountYuan) {}
}
