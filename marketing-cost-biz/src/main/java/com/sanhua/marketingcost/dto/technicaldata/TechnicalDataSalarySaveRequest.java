package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 工资金额来自所选完整来源，客户端不能伪造金额或来源快照。 */
public class TechnicalDataSalarySaveRequest {
  private Integer expectedVersion;
  private String entryMode;
  private String referenceMaterialNo;
  private String referenceFingerprint;
  private String fileSha256;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public Integer getExpectedVersion() { return expectedVersion; }
  public void setExpectedVersion(Integer value) { expectedVersion = value; }
  public String getEntryMode() { return entryMode; }
  public void setEntryMode(String value) { entryMode = value; }
  public String getReferenceMaterialNo() { return referenceMaterialNo; }
  public void setReferenceMaterialNo(String value) { referenceMaterialNo = value; }
  public String getReferenceFingerprint() { return referenceFingerprint; }
  public void setReferenceFingerprint(String value) { referenceFingerprint = value; }
  public String getFileSha256() { return fileSha256; }
  public void setFileSha256(String value) { fileSha256 = value; }
  @JsonAnySetter public void addUnknownField(String key, Object value) { unknownFields.put(key, value); }
  public Map<String, Object> getUnknownFields() { return Collections.unmodifiableMap(unknownFields); }
}
