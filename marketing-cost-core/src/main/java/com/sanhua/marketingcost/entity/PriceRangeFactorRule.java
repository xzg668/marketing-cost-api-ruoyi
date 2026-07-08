package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("lp_price_range_factor_rule")
public class PriceRangeFactorRule {
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 业务单元数据隔离：COMMERCIAL / HOUSEHOLD */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  private String materialCode;
  private String materialName;
  private String specModel;
  private String factorCode;
  private String factorName;
  private String factorUnit;
  private String priceUnit;
  private Integer versionNo;
  private String importBatchNo;
  private String sourceFile;
  private String sourceSheet;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private Integer currentFlag;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getBusinessUnitType() { return businessUnitType; }
  public void setBusinessUnitType(String businessUnitType) { this.businessUnitType = businessUnitType; }
  public String getMaterialCode() { return materialCode; }
  public void setMaterialCode(String materialCode) { this.materialCode = materialCode; }
  public String getMaterialName() { return materialName; }
  public void setMaterialName(String materialName) { this.materialName = materialName; }
  public String getSpecModel() { return specModel; }
  public void setSpecModel(String specModel) { this.specModel = specModel; }
  public String getFactorCode() { return factorCode; }
  public void setFactorCode(String factorCode) { this.factorCode = factorCode; }
  public String getFactorName() { return factorName; }
  public void setFactorName(String factorName) { this.factorName = factorName; }
  public String getFactorUnit() { return factorUnit; }
  public void setFactorUnit(String factorUnit) { this.factorUnit = factorUnit; }
  public String getPriceUnit() { return priceUnit; }
  public void setPriceUnit(String priceUnit) { this.priceUnit = priceUnit; }
  public Integer getVersionNo() { return versionNo; }
  public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
  public String getImportBatchNo() { return importBatchNo; }
  public void setImportBatchNo(String importBatchNo) { this.importBatchNo = importBatchNo; }
  public String getSourceFile() { return sourceFile; }
  public void setSourceFile(String sourceFile) { this.sourceFile = sourceFile; }
  public String getSourceSheet() { return sourceSheet; }
  public void setSourceSheet(String sourceSheet) { this.sourceSheet = sourceSheet; }
  public LocalDate getEffectiveFrom() { return effectiveFrom; }
  public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
  public LocalDate getEffectiveTo() { return effectiveTo; }
  public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }
  public Integer getCurrentFlag() { return currentFlag; }
  public void setCurrentFlag(Integer currentFlag) { this.currentFlag = currentFlag; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
