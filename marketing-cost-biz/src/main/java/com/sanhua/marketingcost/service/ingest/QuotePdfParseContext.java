package com.sanhua.marketingcost.service.ingest;

public class QuotePdfParseContext {
  private String fileName;
  private QuotePdfDocument document;
  private QuoteOaPdfTemplateDefinition templateDefinition;

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  public QuotePdfDocument getDocument() {
    return document;
  }

  public void setDocument(QuotePdfDocument document) {
    this.document = document;
  }

  public QuoteOaPdfTemplateDefinition getTemplateDefinition() {
    return templateDefinition;
  }

  public void setTemplateDefinition(QuoteOaPdfTemplateDefinition templateDefinition) {
    this.templateDefinition = templateDefinition;
  }
}
