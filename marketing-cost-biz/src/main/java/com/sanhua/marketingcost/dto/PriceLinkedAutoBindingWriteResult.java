package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;

public class PriceLinkedAutoBindingWriteResult {
  private int writtenCount;
  private int manualSkippedCount;
  private int errorCount;
  private final List<RowResult> rows = new ArrayList<>();

  public int getWrittenCount() {
    return writtenCount;
  }

  public void setWrittenCount(int writtenCount) {
    this.writtenCount = writtenCount;
  }

  public int getManualSkippedCount() {
    return manualSkippedCount;
  }

  public void setManualSkippedCount(int manualSkippedCount) {
    this.manualSkippedCount = manualSkippedCount;
  }

  public int getErrorCount() {
    return errorCount;
  }

  public void setErrorCount(int errorCount) {
    this.errorCount = errorCount;
  }

  public List<RowResult> getRows() {
    return rows;
  }

  public void addWritten(String tokenName, Long bindingId) {
    writtenCount++;
    rows.add(new RowResult(tokenName, "WRITTEN", bindingId, null));
  }

  public void addManualSkipped(String tokenName, String reason) {
    manualSkippedCount++;
    rows.add(new RowResult(tokenName, "SKIPPED_MANUAL", null, reason));
  }

  public void addError(String tokenName, String reason) {
    errorCount++;
    rows.add(new RowResult(tokenName, "ERROR", null, reason));
  }

  public static class RowResult {
    private String tokenName;
    private String action;
    private Long bindingId;
    private String reason;

    public RowResult() {
    }

    public RowResult(String tokenName, String action, Long bindingId, String reason) {
      this.tokenName = tokenName;
      this.action = action;
      this.bindingId = bindingId;
      this.reason = reason;
    }

    public String getTokenName() {
      return tokenName;
    }

    public void setTokenName(String tokenName) {
      this.tokenName = tokenName;
    }

    public String getAction() {
      return action;
    }

    public void setAction(String action) {
      this.action = action;
    }

    public Long getBindingId() {
      return bindingId;
    }

    public void setBindingId(Long bindingId) {
      this.bindingId = bindingId;
    }

    public String getReason() {
      return reason;
    }

    public void setReason(String reason) {
      this.reason = reason;
    }
  }
}
