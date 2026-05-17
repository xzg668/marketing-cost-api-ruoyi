package com.sanhua.marketingcost.service.ingest;

import java.time.LocalDate;

public class BomAvailability {
  private boolean available;
  private String source;
  private String bomPurpose;
  private String bomVersion;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String syncBatchId;
  private String message;

  public boolean isAvailable() {
    return available;
  }

  public void setAvailable(boolean available) {
    this.available = available;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getBomPurpose() {
    return bomPurpose;
  }

  public void setBomPurpose(String bomPurpose) {
    this.bomPurpose = bomPurpose;
  }

  public String getBomVersion() {
    return bomVersion;
  }

  public void setBomVersion(String bomVersion) {
    this.bomVersion = bomVersion;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(LocalDate effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  public void setEffectiveTo(LocalDate effectiveTo) {
    this.effectiveTo = effectiveTo;
  }

  public String getSyncBatchId() {
    return syncBatchId;
  }

  public void setSyncBatchId(String syncBatchId) {
    this.syncBatchId = syncBatchId;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }

  public static BomAvailability unavailable(String message) {
    BomAvailability availability = new BomAvailability();
    availability.setAvailable(false);
    availability.setMessage(message);
    return availability;
  }
}
