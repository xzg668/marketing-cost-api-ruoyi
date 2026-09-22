package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/** 多产品共用默认办理人及可选模块分工；各产品仍保留独立任务。 */
@Getter
@Setter
public final class TechnicalDataTaskPublishRequest {
  private String requestId;
  private List<Long> oaFormItemIds;
  private String accountingMonth;
  private Long assigneeUserId;
  private Map<String, Long> moduleAssignees;
  private Map<Long, String> checkFingerprints;
  private LocalDateTime dueAt;
  @Setter(lombok.AccessLevel.NONE)
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  @JsonAnySetter
  public void captureUnknownField(String name, Object value) {
    unknownFields.put(name, value);
  }
}
