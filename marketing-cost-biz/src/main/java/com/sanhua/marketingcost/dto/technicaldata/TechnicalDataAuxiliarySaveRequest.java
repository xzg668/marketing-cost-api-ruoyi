package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TechnicalDataAuxiliarySaveRequest {
  private Integer expectedVersion;
  private String entryMode;
  private String referenceMaterialNo;
  private String referenceFingerprint;
  private String fileSha256;
  private List<TechnicalDataAuxiliaryItemRequest> items;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public String getEntryMode() { return entryMode; }
  public void setEntryMode(String value) { entryMode = value; }
  public String getReferenceMaterialNo() { return referenceMaterialNo; }
  public void setReferenceMaterialNo(String value) { referenceMaterialNo = value; }
  public String getReferenceFingerprint() { return referenceFingerprint; }
  public void setReferenceFingerprint(String value) { referenceFingerprint = value; }
  public String getFileSha256() { return fileSha256; }
  public void setFileSha256(String value) { fileSha256 = value; }
  public Integer getExpectedVersion() { return expectedVersion; }
  public void setExpectedVersion(Integer value) { expectedVersion = value; }
  public List<TechnicalDataAuxiliaryItemRequest> getItems() { return items; }
  public void setItems(List<TechnicalDataAuxiliaryItemRequest> value) { items = value; }

  @JsonAnySetter
  public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return java.util.Collections.unmodifiableMap(unknownFields); }
}
