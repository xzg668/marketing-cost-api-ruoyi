package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CmsCostExcelParseError {
  private Integer rowNo;
  private String columnName;
  private String message;

  public CmsCostExcelParseError(Integer rowNo, String columnName, String message) {
    this.rowNo = rowNo;
    this.columnName = columnName;
    this.message = message;
  }
}
