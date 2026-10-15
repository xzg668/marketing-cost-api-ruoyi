package com.sanhua.marketingcost.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import java.math.BigDecimal;
import java.util.List;

/** 财务原表的只读解析结果；错误存在时整批禁止发布。 */
public record FinanceFactorWorkbook(
    String fileName,
    String fileSha256,
    String sheetName,
    String parserVersion,
    List<Scheme> schemes,
    List<Value> values,
    List<Problem> problems) {
  public FinanceFactorWorkbook {
    schemes = List.copyOf(schemes);
    values = List.copyOf(values);
    problems = List.copyOf(problems);
  }

  public record Scheme(String code, String label) {}

  public record Value(
      String canonicalKey,
      String factorName,
      String shortName,
      String priceSource,
      String schemeCode,
      Condition condition,
      @JsonSerialize(using = ToStringSerializer.class) BigDecimal price,
      String unit,
      boolean taxIncluded,
      int rowNumber,
      int columnNumber,
      String cellRef,
      String sourceValueText,
      @JsonSerialize(using = ToStringSerializer.class) BigDecimal originalValue,
      String sourceFormula,
      String numberFormat,
      String sourceUnit,
      String conversionBasis) {}

  public record Problem(String sheetName, String cellRef, String message) {}
}
