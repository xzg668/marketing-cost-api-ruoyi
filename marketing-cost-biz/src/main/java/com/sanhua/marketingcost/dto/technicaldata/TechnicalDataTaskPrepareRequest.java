package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/** 报价员或管理员在正式分派前，建立可复用的真实补录草稿上下文。 */
@Getter
@Setter
public final class TechnicalDataTaskPrepareRequest {
  private Long oaFormItemId;
  private String accountingMonth;
  private String checkFingerprint;
  private String requestId;

  @Setter(lombok.AccessLevel.NONE)
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  @JsonAnySetter
  public void captureUnknownField(String name, Object value) {
    unknownFields.put(name, value);
  }
}
