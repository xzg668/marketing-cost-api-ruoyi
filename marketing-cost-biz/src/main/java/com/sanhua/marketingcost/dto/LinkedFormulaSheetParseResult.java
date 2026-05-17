package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class LinkedFormulaSheetParseResult {
  private String sheetName;
  private Integer headerRowNumber;
  private final List<LinkedFormulaRow> rows = new ArrayList<>();

  public String getSheetName() {
    return sheetName;
  }

  public void setSheetName(String sheetName) {
    this.sheetName = sheetName;
  }

  public Integer getHeaderRowNumber() {
    return headerRowNumber;
  }

  public void setHeaderRowNumber(Integer headerRowNumber) {
    this.headerRowNumber = headerRowNumber;
  }

  public List<LinkedFormulaRow> getRows() {
    return rows;
  }
}
