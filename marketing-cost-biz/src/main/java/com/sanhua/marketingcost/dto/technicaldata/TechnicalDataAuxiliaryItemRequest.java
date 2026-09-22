package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** 仅允许修改本次金额；科目、原表字段与原金额由服务端读取。 */
public class TechnicalDataAuxiliaryItemRequest {
  private String itemKey;
  private BigDecimal amount;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();
  public String getItemKey() { return itemKey; }
  public void setItemKey(String value) { itemKey = value; }
  public BigDecimal getAmount() { return amount; }
  public void setAmount(BigDecimal value) { amount = value; }
  @JsonAnySetter
  public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return java.util.Collections.unmodifiableMap(unknownFields); }
}
