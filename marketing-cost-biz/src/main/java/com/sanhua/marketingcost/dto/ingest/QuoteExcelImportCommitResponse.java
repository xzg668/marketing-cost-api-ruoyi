package com.sanhua.marketingcost.dto.ingest;

import java.util.ArrayList;
import java.util.List;

public class QuoteExcelImportCommitResponse {
  private boolean committed;
  private QuoteExcelImportPreviewResponse preview;
  private List<QuoteIngestResponse> results = new ArrayList<>();

  public boolean isCommitted() {
    return committed;
  }

  public void setCommitted(boolean committed) {
    this.committed = committed;
  }

  public QuoteExcelImportPreviewResponse getPreview() {
    return preview;
  }

  public void setPreview(QuoteExcelImportPreviewResponse preview) {
    this.preview = preview;
  }

  public List<QuoteIngestResponse> getResults() {
    return results;
  }

  public void setResults(List<QuoteIngestResponse> results) {
    this.results = results;
  }
}
