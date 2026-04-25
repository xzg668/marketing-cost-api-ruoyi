package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 制造件工艺规格 entity (Task #8) —— 对应 lp_make_part_spec 表。
 *
 * <p>一行代表一个 (material_code, period) 的取价配置：原材料 / 回收 / 加工费 / formula。
 */
@TableName("lp_make_part_spec")
public class MakePartSpec {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String materialCode;
  private String materialName;
  private String drawingNo;
  private String period;
  private BigDecimal blankWeight;
  private BigDecimal netWeight;
  private BigDecimal scrapRate;
  private String rawMaterialCode;
  private String rawMaterialSpec;
  private BigDecimal rawUnitPrice;
  private String recycleCode;
  private BigDecimal recycleUnitPrice;
  private BigDecimal recycleRatio;
  private BigDecimal processFee;
  private BigDecimal outsourceFee;
  private Long formulaId;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String remark;

  /** V21 业务单元数据隔离：COMMERCIAL / HOUSEHOLD */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

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
  public String getMaterialCode() {
    return materialCode;
  }
  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }
  public String getMaterialName() {
    return materialName;
  }
  public void setMaterialName(String materialName) {
    this.materialName = materialName;
  }
  public String getDrawingNo() {
    return drawingNo;
  }
  public void setDrawingNo(String drawingNo) {
    this.drawingNo = drawingNo;
  }
  public String getPeriod() {
    return period;
  }
  public void setPeriod(String period) {
    this.period = period;
  }
  public BigDecimal getBlankWeight() {
    return blankWeight;
  }
  public void setBlankWeight(BigDecimal blankWeight) {
    this.blankWeight = blankWeight;
  }
  public BigDecimal getNetWeight() {
    return netWeight;
  }
  public void setNetWeight(BigDecimal netWeight) {
    this.netWeight = netWeight;
  }
  public BigDecimal getScrapRate() {
    return scrapRate;
  }
  public void setScrapRate(BigDecimal scrapRate) {
    this.scrapRate = scrapRate;
  }
  public String getRawMaterialCode() {
    return rawMaterialCode;
  }
  public void setRawMaterialCode(String rawMaterialCode) {
    this.rawMaterialCode = rawMaterialCode;
  }
  public String getRawMaterialSpec() {
    return rawMaterialSpec;
  }
  public void setRawMaterialSpec(String rawMaterialSpec) {
    this.rawMaterialSpec = rawMaterialSpec;
  }
  public BigDecimal getRawUnitPrice() {
    return rawUnitPrice;
  }
  public void setRawUnitPrice(BigDecimal rawUnitPrice) {
    this.rawUnitPrice = rawUnitPrice;
  }
  public String getRecycleCode() {
    return recycleCode;
  }
  public void setRecycleCode(String recycleCode) {
    this.recycleCode = recycleCode;
  }
  public BigDecimal getRecycleUnitPrice() {
    return recycleUnitPrice;
  }
  public void setRecycleUnitPrice(BigDecimal recycleUnitPrice) {
    this.recycleUnitPrice = recycleUnitPrice;
  }
  public BigDecimal getRecycleRatio() {
    return recycleRatio;
  }
  public void setRecycleRatio(BigDecimal recycleRatio) {
    this.recycleRatio = recycleRatio;
  }
  public BigDecimal getProcessFee() {
    return processFee;
  }
  public void setProcessFee(BigDecimal processFee) {
    this.processFee = processFee;
  }
  public BigDecimal getOutsourceFee() {
    return outsourceFee;
  }
  public void setOutsourceFee(BigDecimal outsourceFee) {
    this.outsourceFee = outsourceFee;
  }
  public Long getFormulaId() {
    return formulaId;
  }
  public void setFormulaId(Long formulaId) {
    this.formulaId = formulaId;
  }
  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }
  public void setEffectiveFrom(LocalDate effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }
  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }
  public void setEffectiveTo(LocalDate effectiveTo) {
    this.effectiveTo = effectiveTo;
  }
  public String getRemark() {
    return remark;
  }
  public void setRemark(String remark) {
    this.remark = remark;
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
