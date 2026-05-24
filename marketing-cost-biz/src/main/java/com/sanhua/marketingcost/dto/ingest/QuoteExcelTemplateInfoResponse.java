package com.sanhua.marketingcost.dto.ingest;

public class QuoteExcelTemplateInfoResponse {
  private String templateType;
  private String processCode;
  private String quoteScenario;
  private String displayName;
  private String fileName;

  public String getTemplateType() {
    return templateType;
  }

  public void setTemplateType(String templateType) {
    this.templateType = templateType;
  }

  public String getProcessCode() {
    return processCode;
  }

  public void setProcessCode(String processCode) {
    this.processCode = processCode;
  }

  public String getQuoteScenario() {
    return quoteScenario;
  }

  public void setQuoteScenario(String quoteScenario) {
    this.quoteScenario = quoteScenario;
  }

  public String getDisplayName() {
    return displayName;
  }

  public void setDisplayName(String displayName) {
    this.displayName = displayName;
  }

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }
}
