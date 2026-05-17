package com.sanhua.marketingcost.dto.ingest;

public class QuoteValidationError {
  private String fieldPath;
  private String code;
  private String message;
  private Integer rowNo;

  public QuoteValidationError() {}

  public QuoteValidationError(String fieldPath, String code, String message) {
    this(fieldPath, code, message, null);
  }

  public QuoteValidationError(String fieldPath, String code, String message, Integer rowNo) {
    this.fieldPath = fieldPath;
    this.code = code;
    this.message = message;
    this.rowNo = rowNo;
  }

  public String getFieldPath() {
    return fieldPath;
  }

  public void setFieldPath(String fieldPath) {
    this.fieldPath = fieldPath;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public Integer getRowNo() {
    return rowNo;
  }

  public void setRowNo(Integer rowNo) {
    this.rowNo = rowNo;
  }
}
