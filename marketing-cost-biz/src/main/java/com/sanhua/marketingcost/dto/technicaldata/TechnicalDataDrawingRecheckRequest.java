package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TechnicalDataDrawingRecheckRequest {
  private Integer expectedVersion;
  private Boolean maintained;
  private String drawingNo;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  @JsonAnySetter
  public void unknown(String name, Object value) { unknownFields.put(name, value); }
}
