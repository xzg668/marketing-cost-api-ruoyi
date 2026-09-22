package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TechnicalDataManufacturingSaveRequest {
  private Integer expectedVersion;
  private Long sourceVersionId;
  private String sourceFingerprint;
  private List<Item> items;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();
  @JsonAnySetter public void unknown(String name, Object value) { unknownFields.put(name, value); }

  @Getter
  @Setter
  public static class Item {
    private Long parentSourceNodeId;
    private String rawMaterialNo;
    private BigDecimal netLengthMm;
    private BigDecimal grossWeightKg;
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();
    @JsonAnySetter public void unknown(String name, Object value) { unknownFields.put(name, value); }
  }
}
