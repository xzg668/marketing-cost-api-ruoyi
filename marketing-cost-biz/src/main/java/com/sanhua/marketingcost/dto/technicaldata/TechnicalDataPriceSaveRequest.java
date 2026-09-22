package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class TechnicalDataPriceSaveRequest {
  private Integer expectedVersion;
  private String requirementsFingerprint;
  private List<Item> items;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();
  @JsonAnySetter public void unknown(String key, Object value) { unknownFields.put(key, value); }

  @Getter @Setter
  public static class Item {
    private String itemKey;
    private String entryMode;
    private BigDecimal unitPrice;
    private String formula;
    private TechnicalDataSupplementContent.PriceParameters parameters;
    private Long referenceId;
    private String referenceFingerprint;
    private String notes;
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();
    @JsonAnySetter public void unknown(String key, Object value) { unknownFields.put(key, value); }
  }
}
