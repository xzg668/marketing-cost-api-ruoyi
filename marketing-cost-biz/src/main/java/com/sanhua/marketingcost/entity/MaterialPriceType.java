package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("lp_material_price_type")
public class MaterialPriceType {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Integer rowNo;
  private String billNo;
  private String materialCode;
  private String materialName;
  private String materialSpec;
  private String materialModel;
  private String materialShape;
  private String priceType;
  private String period;
  private String source;

  /** V10 新增：取价优先级（越小越优先），同物料登记多条价格类型时回退用 */
  private Integer priority;

  /** V10 新增：生效起始日期（含），NULL 表示不限早 */
  private LocalDate effectiveFrom;

  /** V10 新增：生效结束日期（含），NULL 表示长期有效 */
  private LocalDate effectiveTo;

  /** V10 新增：数据来源系统（srm/oa/u9/cms/manual），用于多源接入追溯 */
  private String sourceSystem;

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

  public Integer getRowNo() {
    return rowNo;
  }

  public void setRowNo(Integer rowNo) {
    this.rowNo = rowNo;
  }

  public String getBillNo() {
    return billNo;
  }

  public void setBillNo(String billNo) {
    this.billNo = billNo;
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

  public String getMaterialSpec() {
    return materialSpec;
  }

  public void setMaterialSpec(String materialSpec) {
    this.materialSpec = materialSpec;
  }

  public String getMaterialModel() {
    return materialModel;
  }

  public void setMaterialModel(String materialModel) {
    this.materialModel = materialModel;
  }

  public String getMaterialShape() {
    return materialShape;
  }

  public void setMaterialShape(String materialShape) {
    this.materialShape = materialShape;
  }

  public String getPriceType() {
    return priceType;
  }

  public void setPriceType(String priceType) {
    this.priceType = priceType;
  }

  public String getPeriod() {
    return period;
  }

  public void setPeriod(String period) {
    this.period = period;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public Integer getPriority() {
    return priority;
  }

  public void setPriority(Integer priority) {
    this.priority = priority;
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

  public String getSourceSystem() {
    return sourceSystem;
  }

  public void setSourceSystem(String sourceSystem) {
    this.sourceSystem = sourceSystem;
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
