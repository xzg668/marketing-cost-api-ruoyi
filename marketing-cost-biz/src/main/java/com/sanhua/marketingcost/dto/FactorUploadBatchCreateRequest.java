package com.sanhua.marketingcost.dto;

public class FactorUploadBatchCreateRequest {
  private String priceMonth;
  private String businessUnitType;
  private String fileName;
  private String fileSha256;
  private String uploadedBy;
  private String importType;
  private String importPurpose;
  private String effectiveStrategy;
  private FactorWorkbookParseResult parseResult;

  public String getPriceMonth() {
    return priceMonth;
  }

  public void setPriceMonth(String priceMonth) {
    this.priceMonth = priceMonth;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  public String getFileSha256() {
    return fileSha256;
  }

  public void setFileSha256(String fileSha256) {
    this.fileSha256 = fileSha256;
  }

  public String getUploadedBy() {
    return uploadedBy;
  }

  public void setUploadedBy(String uploadedBy) {
    this.uploadedBy = uploadedBy;
  }

  public String getImportType() {
    return importType;
  }

  public void setImportType(String importType) {
    this.importType = importType;
  }

  public String getImportPurpose() {
    return importPurpose;
  }

  public void setImportPurpose(String importPurpose) {
    this.importPurpose = importPurpose;
  }

  public String getEffectiveStrategy() {
    return effectiveStrategy;
  }

  public void setEffectiveStrategy(String effectiveStrategy) {
    this.effectiveStrategy = effectiveStrategy;
  }

  public FactorWorkbookParseResult getParseResult() {
    return parseResult;
  }

  public void setParseResult(FactorWorkbookParseResult parseResult) {
    this.parseResult = parseResult;
  }
}
