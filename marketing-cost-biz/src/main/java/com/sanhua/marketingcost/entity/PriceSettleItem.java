package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_price_settle_item")
public class PriceSettleItem {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long settleId;
  private String materialCode;
  private String materialName;
  private String model;
  private BigDecimal plannedPrice;
  private BigDecimal markupRatio;
  private BigDecimal baseSettlePrice;
  private BigDecimal linkedSettlePrice;
  private String remark;

  /** V21 业务单元数据隔离：COMMERCIAL / HOUSEHOLD */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  public String getBusinessUnitType() { return businessUnitType; }
  public void setBusinessUnitType(String businessUnitType) { this.businessUnitType = businessUnitType; }
  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getSettleId() { return settleId; }
  public void setSettleId(Long settleId) { this.settleId = settleId; }
  public String getMaterialCode() { return materialCode; }
  public void setMaterialCode(String materialCode) { this.materialCode = materialCode; }
  public String getMaterialName() { return materialName; }
  public void setMaterialName(String materialName) { this.materialName = materialName; }
  public String getModel() { return model; }
  public void setModel(String model) { this.model = model; }
  public BigDecimal getPlannedPrice() { return plannedPrice; }
  public void setPlannedPrice(BigDecimal plannedPrice) { this.plannedPrice = plannedPrice; }
  public BigDecimal getMarkupRatio() { return markupRatio; }
  public void setMarkupRatio(BigDecimal markupRatio) { this.markupRatio = markupRatio; }
  public BigDecimal getBaseSettlePrice() { return baseSettlePrice; }
  public void setBaseSettlePrice(BigDecimal baseSettlePrice) { this.baseSettlePrice = baseSettlePrice; }
  public BigDecimal getLinkedSettlePrice() { return linkedSettlePrice; }
  public void setLinkedSettlePrice(BigDecimal linkedSettlePrice) { this.linkedSettlePrice = linkedSettlePrice; }
  public String getRemark() { return remark; }
  public void setRemark(String remark) { this.remark = remark; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
