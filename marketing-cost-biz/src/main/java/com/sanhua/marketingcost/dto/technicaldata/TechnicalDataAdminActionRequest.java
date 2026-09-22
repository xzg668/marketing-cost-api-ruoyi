package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;

public class TechnicalDataAdminActionRequest {
  private Integer expectedTaskVersion;
  private String reason;
  private String requestId;
  private Long assigneeUserId;
  private Long reviewerUserId;
  private Map<String, Long> moduleAssignees = Map.of();
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public Map<String, Long> getModuleAssignees() { return moduleAssignees; }
  public void setModuleAssignees(Map<String, Long> value) { moduleAssignees = value; }
  public Integer getExpectedTaskVersion() { return expectedTaskVersion; }
  public void setExpectedTaskVersion(Integer value) { expectedTaskVersion = value; }
  public String getReason() { return reason; }
  public void setReason(String value) { reason = value; }
  public String getRequestId() { return requestId; }
  public void setRequestId(String value) { requestId = value; }
  public Long getAssigneeUserId() { return assigneeUserId; }
  public void setAssigneeUserId(Long value) { assigneeUserId = value; }
  public Long getReviewerUserId() { return reviewerUserId; }
  public void setReviewerUserId(Long value) { reviewerUserId = value; }
  public Map<String, Object> getUnknownFields() { return Map.copyOf(unknownFields); }

  @JsonAnySetter
  public void unknown(String name, Object value) { unknownFields.put(name, value); }
}
