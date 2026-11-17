package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reference edits contain quantities only; upload edits are reparsed from the retained original. */
public class TechnicalBomSaveRequest {
  private Integer expectedVersion;
  private String entryMode, referenceCode, referenceFingerprint, fileSha256;
  private Map<String, BigDecimal> quantities;
  private final Map<String,Object> unknownFields = new LinkedHashMap<>();
  public Integer getExpectedVersion() { return expectedVersion; }
  public void setExpectedVersion(Integer value) { expectedVersion = value; }
  public String getEntryMode() { return entryMode; }
  public void setEntryMode(String value) { entryMode = value; }
  public String getReferenceCode() { return referenceCode; }
  public void setReferenceCode(String value) { referenceCode = value; }
  public String getReferenceFingerprint() { return referenceFingerprint; }
  public void setReferenceFingerprint(String value) { referenceFingerprint = value; }
  public String getFileSha256() { return fileSha256; }
  public void setFileSha256(String value) { fileSha256 = value; }
  public Map<String, BigDecimal> getQuantities() { return quantities; }
  public void setQuantities(Map<String, BigDecimal> value) { quantities = value; }
  @JsonAnySetter public void unknown(String key, Object value) { unknownFields.put(key, value); }
  public Map<String,Object> getUnknownFields() { return Map.copyOf(unknownFields); }
}
