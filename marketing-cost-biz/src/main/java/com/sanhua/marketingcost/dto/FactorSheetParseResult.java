package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class FactorSheetParseResult {
  private String sheetName;
  private Integer headerRowNumber;
  private final List<FactorRowParseResult> rows = new ArrayList<>();
  private final List<ParseError> errors = new ArrayList<>();

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

  public List<FactorRowParseResult> getRows() {
    return rows;
  }

  public List<ParseError> getErrors() {
    return errors;
  }

  public static class ParseError {
    private Integer rowNumber;
    private String message;

    public ParseError() {
    }

    public ParseError(Integer rowNumber, String message) {
      this.rowNumber = rowNumber;
      this.message = message;
    }

    public Integer getRowNumber() {
      return rowNumber;
    }

    public void setRowNumber(Integer rowNumber) {
      this.rowNumber = rowNumber;
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }
  }
}
