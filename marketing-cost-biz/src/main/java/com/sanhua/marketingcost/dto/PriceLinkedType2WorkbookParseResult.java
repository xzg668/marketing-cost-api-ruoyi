package com.sanhua.marketingcost.dto;

import java.util.List;

/** 类型 2 工作簿只读解析结果，不承载任何数据库写入行为。 */
public final class PriceLinkedType2WorkbookParseResult {

  private final String sourceFileName;
  private final String businessSheetName;
  private final Integer businessHeaderRowNumber;
  private final String standardSheetName;
  private final Integer standardHeaderRowNumber;
  private final List<PriceLinkedType2FactorRow> factorRows;
  private final List<PriceLinkedType2ProductRow> productRows;
  private final List<PriceLinkedType2StandardRow> standardRows;
  private final List<PriceLinkedType2ParseError> errors;

  public PriceLinkedType2WorkbookParseResult(
      String sourceFileName,
      String businessSheetName,
      Integer businessHeaderRowNumber,
      String standardSheetName,
      Integer standardHeaderRowNumber,
      List<PriceLinkedType2FactorRow> factorRows,
      List<PriceLinkedType2ProductRow> productRows,
      List<PriceLinkedType2StandardRow> standardRows,
      List<PriceLinkedType2ParseError> errors) {
    this.sourceFileName = sourceFileName;
    this.businessSheetName = businessSheetName;
    this.businessHeaderRowNumber = businessHeaderRowNumber;
    this.standardSheetName = standardSheetName;
    this.standardHeaderRowNumber = standardHeaderRowNumber;
    this.factorRows = List.copyOf(factorRows);
    this.productRows = List.copyOf(productRows);
    this.standardRows = List.copyOf(standardRows);
    this.errors = List.copyOf(errors);
  }

  public String getSourceFileName() {
    return sourceFileName;
  }

  public String getBusinessSheetName() {
    return businessSheetName;
  }

  public Integer getBusinessHeaderRowNumber() {
    return businessHeaderRowNumber;
  }

  public String getStandardSheetName() {
    return standardSheetName;
  }

  public Integer getStandardHeaderRowNumber() {
    return standardHeaderRowNumber;
  }

  public List<PriceLinkedType2FactorRow> getFactorRows() {
    return factorRows;
  }

  public List<PriceLinkedType2ProductRow> getProductRows() {
    return productRows;
  }

  public List<PriceLinkedType2StandardRow> getStandardRows() {
    return standardRows;
  }

  public List<PriceLinkedType2ParseError> getErrors() {
    return errors;
  }
}
