package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TechnicalDataSolderSaveRequest {
  private Integer expectedVersion;
  private String entryMode;
  private String referenceMaterialNo;
  private String referenceFingerprint;
  private List<Item> items;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public Integer getExpectedVersion() { return expectedVersion; }
  public void setExpectedVersion(Integer value) { expectedVersion = value; }
  public String getEntryMode() { return entryMode; }
  public void setEntryMode(String value) { entryMode = value; }
  public String getReferenceMaterialNo() { return referenceMaterialNo; }
  public void setReferenceMaterialNo(String value) { referenceMaterialNo = value; }
  public String getReferenceFingerprint() { return referenceFingerprint; }
  public void setReferenceFingerprint(String value) { referenceFingerprint = value; }
  public List<Item> getItems() { return items; }
  public void setItems(List<Item> value) { items = value; }
  @JsonAnySetter public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
  public Map<String, Object> getUnknownFields() { return java.util.Collections.unmodifiableMap(unknownFields); }

  public static class Item {
    private String itemKey;
    private String materialNo;
    private String materialFingerprint;
    private BigDecimal quantityPerProduct;
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();
    public String getItemKey() { return itemKey; }
    public void setItemKey(String value) { itemKey = value; }
    public String getMaterialNo() { return materialNo; }
    public void setMaterialNo(String value) { materialNo = value; }
    public String getMaterialFingerprint() { return materialFingerprint; }
    public void setMaterialFingerprint(String value) { materialFingerprint = value; }
    public BigDecimal getQuantityPerProduct() { return quantityPerProduct; }
    public void setQuantityPerProduct(BigDecimal value) { quantityPerProduct = value; }
    @JsonAnySetter public void addUnknownField(String name, Object value) { unknownFields.put(name, value); }
    public Map<String, Object> getUnknownFields() { return java.util.Collections.unmodifiableMap(unknownFields); }
  }
}
