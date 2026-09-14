package com.sanhua.marketingcost.dto.technicaldata;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 产品基本信息只允许修改型号、属性和新品标识。
 * 未声明字段会被收集并由领域服务拒绝，避免客户端借 PATCH 修改 OA 快照字段。
 */
public final class TechnicalDataProfileUpdateRequest {
  private String productModel;
  private String productProperty;
  private Boolean newProduct;
  private Integer expectedVersion;
  private final Map<String, Object> unknownFields = new LinkedHashMap<>();

  public String getProductModel() {
    return productModel;
  }

  public void setProductModel(String productModel) {
    this.productModel = productModel;
  }

  public String getProductProperty() {
    return productProperty;
  }

  public void setProductProperty(String productProperty) {
    this.productProperty = productProperty;
  }

  public Boolean getNewProduct() {
    return newProduct;
  }

  public void setNewProduct(Boolean newProduct) {
    this.newProduct = newProduct;
  }

  public Integer getExpectedVersion() {
    return expectedVersion;
  }

  public void setExpectedVersion(Integer expectedVersion) {
    this.expectedVersion = expectedVersion;
  }

  @JsonAnySetter
  public void captureUnknownField(String name, Object value) {
    unknownFields.put(name, value);
  }

  public Map<String, Object> getUnknownFields() {
    return Collections.unmodifiableMap(unknownFields);
  }
}
