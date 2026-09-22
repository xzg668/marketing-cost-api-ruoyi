package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 手填传百分数文本；参考只传选中标识，由服务端重新读取真实费率。 */
public class TechnicalDataNetLossSaveRequest {
  private Integer expectedVersion;
  private String entryMode;
  private String referenceMaterialNo;
  private String referenceFingerprint;
  private String percent;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();
  public Integer getExpectedVersion() { return expectedVersion; }
  public void setExpectedVersion(Integer value) { expectedVersion = value; }
  public String getEntryMode() { return entryMode; }
  public void setEntryMode(String value) { entryMode = value; }
  public String getReferenceMaterialNo() { return referenceMaterialNo; }
  public void setReferenceMaterialNo(String value) { referenceMaterialNo = value; }
  public String getReferenceFingerprint() { return referenceFingerprint; }
  public void setReferenceFingerprint(String value) { referenceFingerprint = value; }
  public String getPercent() { return percent; }
  public void setPercent(String value) { percent = value; }
  @JsonAnySetter public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return java.util.Collections.unmodifiableMap(unknownFields); }
}
