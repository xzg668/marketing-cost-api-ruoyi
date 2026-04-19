package com.sanhua.marketingcost.dto;

import java.time.LocalDateTime;

public class BomManualSummaryRow {
  private String bomCode;
  private Long detailCount;
  private LocalDateTime updatedAt;

  public String getBomCode() {
    return bomCode;
  }

  public void setBomCode(String bomCode) {
    this.bomCode = bomCode;
  }

  public Long getDetailCount() {
    return detailCount;
  }

  public void setDetailCount(Long detailCount) {
    this.detailCount = detailCount;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
