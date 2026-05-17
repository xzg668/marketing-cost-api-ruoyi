package com.sanhua.marketingcost.dto.ingest;

public class QuoteIngestPreviewResponse extends QuoteIngestResponse {
  private boolean valid;
  private boolean classificationPending;

  public boolean isValid() {
    return valid;
  }

  public void setValid(boolean valid) {
    this.valid = valid;
  }

  public boolean isClassificationPending() {
    return classificationPending;
  }

  public void setClassificationPending(boolean classificationPending) {
    this.classificationPending = classificationPending;
  }
}
