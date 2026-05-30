package com.sanhua.marketingcost.service.ingest;

import java.util.ArrayList;
import java.util.List;

public class QuotePdfPage {
  private int pageIndex;
  private List<QuotePdfLine> lines = new ArrayList<>();
  private List<QuotePdfToken> tokens = new ArrayList<>();

  public int getPageIndex() {
    return pageIndex;
  }

  public void setPageIndex(int pageIndex) {
    this.pageIndex = pageIndex;
  }

  public List<QuotePdfLine> getLines() {
    return lines;
  }

  public void setLines(List<QuotePdfLine> lines) {
    this.lines = lines;
  }

  public List<QuotePdfToken> getTokens() {
    return tokens;
  }

  public void setTokens(List<QuotePdfToken> tokens) {
    this.tokens = tokens;
  }
}
