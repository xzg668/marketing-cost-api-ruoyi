package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_price_linked_calc_item")
public class PriceLinkedCalcItem {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String oaNo;
  private String itemCode;
  private String shapeAttr;
  private BigDecimal bomQty;
  private BigDecimal partUnitPrice;
  private BigDecimal partAmount;

  /** V21 业务单元数据隔离：COMMERCIAL / HOUSEHOLD */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  /** V26 计算 trace JSON —— 存 {normalizedExpr, variables, steps, result/error} */
  @TableField("trace_json")
  private String traceJson;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public String getItemCode() {
    return itemCode;
  }

  public void setItemCode(String itemCode) {
    this.itemCode = itemCode;
  }

  public String getShapeAttr() {
    return shapeAttr;
  }

  public void setShapeAttr(String shapeAttr) {
    this.shapeAttr = shapeAttr;
  }

  public BigDecimal getBomQty() {
    return bomQty;
  }

  public void setBomQty(BigDecimal bomQty) {
    this.bomQty = bomQty;
  }

  public BigDecimal getPartUnitPrice() {
    return partUnitPrice;
  }

  public void setPartUnitPrice(BigDecimal partUnitPrice) {
    this.partUnitPrice = partUnitPrice;
  }

  public BigDecimal getPartAmount() {
    return partAmount;
  }

  public void setPartAmount(BigDecimal partAmount) {
    this.partAmount = partAmount;
  }

  public String getTraceJson() {
    return traceJson;
  }

  public void setTraceJson(String traceJson) {
    this.traceJson = traceJson;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
