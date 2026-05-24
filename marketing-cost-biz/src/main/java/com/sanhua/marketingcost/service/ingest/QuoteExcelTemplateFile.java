package com.sanhua.marketingcost.service.ingest;

import com.sanhua.marketingcost.enums.QuoteExcelTemplateType;

public class QuoteExcelTemplateFile {
  private final QuoteExcelTemplateType templateType;
  private final byte[] content;

  public QuoteExcelTemplateFile(QuoteExcelTemplateType templateType, byte[] content) {
    this.templateType = templateType;
    this.content = content;
  }

  public QuoteExcelTemplateType getTemplateType() {
    return templateType;
  }

  public byte[] getContent() {
    return content;
  }

  public String getFileName() {
    return templateType.getFileName();
  }
}
