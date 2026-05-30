package com.sanhua.marketingcost.service.ingest;

import java.util.ArrayList;
import java.util.List;

public class QuotePdfDocument {
  private String fileName;
  private String fullText;
  private List<QuotePdfPage> pages = new ArrayList<>();

  public String getFileName() {
    return fileName;
  }

  public void setFileName(String fileName) {
    this.fileName = fileName;
  }

  public String getFullText() {
    return fullText;
  }

  public void setFullText(String fullText) {
    this.fullText = fullText;
  }

  public List<QuotePdfPage> getPages() {
    return pages;
  }

  public void setPages(List<QuotePdfPage> pages) {
    this.pages = pages;
  }
}
