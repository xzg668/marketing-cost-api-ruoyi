package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter
@Setter
public class SupplierSupplyRatioWorkbookParseResult {
  private String sourceFileName;
  private String sheetName;
  private Integer headerRowNumber;
  private final List<String> headers = new ArrayList<>();
  private final List<SupplierSupplyRatioExcelRow> rows = new ArrayList<>();
  private final List<ParseError> errors = new ArrayList<>();

  public boolean hasErrors() {
    return !errors.isEmpty();
  }

  @Getter
  @Setter
  @ToString
  public static class ParseError {
    private Integer rowNo;
    private String columnName;
    private String message;

    public ParseError(Integer rowNo, String columnName, String message) {
      this.rowNo = rowNo;
      this.columnName = columnName;
      this.message = message;
    }
  }
}
