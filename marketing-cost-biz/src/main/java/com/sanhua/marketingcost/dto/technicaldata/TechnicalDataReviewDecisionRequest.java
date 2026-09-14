package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TechnicalDataReviewDecisionRequest {
  private Long productId;
  private Long submittedVersionId;
  private Integer expectedTaskVersion;
  private Integer expectedReviewItemVersion;
  private String reason;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  @JsonAnySetter
  public void unknown(String name, Object value) {
    unknownFields.put(name, value);
  }
}
