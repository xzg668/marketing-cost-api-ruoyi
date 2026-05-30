package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("lp_u9_bom_byproduct_master")
public class U9BomByproductMaster {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String parentMaterialNo;
  private String parentMaterialName;
  private String parentMaterialSpec;
  private String bomPurpose;
  private String versionNo;
  private String outputType;
  private String byproductMaterialNo;
  private String byproductMaterialName;
  private String operationNo;
  private BigDecimal outputQty;
  private String unit;
  private String status;
  private String productionDeptCode;
  private String productionDeptName;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String u9CreatedBy;
  private LocalDateTime u9CreatedTime;
  private String sourceType;
  private String sourceFileName;
  private String importedBy;
  private LocalDateTime importedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getParentMaterialNo() { return parentMaterialNo; }
  public void setParentMaterialNo(String parentMaterialNo) { this.parentMaterialNo = parentMaterialNo; }
  public String getParentMaterialName() { return parentMaterialName; }
  public void setParentMaterialName(String parentMaterialName) { this.parentMaterialName = parentMaterialName; }
  public String getParentMaterialSpec() { return parentMaterialSpec; }
  public void setParentMaterialSpec(String parentMaterialSpec) { this.parentMaterialSpec = parentMaterialSpec; }
  public String getBomPurpose() { return bomPurpose; }
  public void setBomPurpose(String bomPurpose) { this.bomPurpose = bomPurpose; }
  public String getVersionNo() { return versionNo; }
  public void setVersionNo(String versionNo) { this.versionNo = versionNo; }
  public String getOutputType() { return outputType; }
  public void setOutputType(String outputType) { this.outputType = outputType; }
  public String getByproductMaterialNo() { return byproductMaterialNo; }
  public void setByproductMaterialNo(String byproductMaterialNo) { this.byproductMaterialNo = byproductMaterialNo; }
  public String getByproductMaterialName() { return byproductMaterialName; }
  public void setByproductMaterialName(String byproductMaterialName) { this.byproductMaterialName = byproductMaterialName; }
  public String getOperationNo() { return operationNo; }
  public void setOperationNo(String operationNo) { this.operationNo = operationNo; }
  public BigDecimal getOutputQty() { return outputQty; }
  public void setOutputQty(BigDecimal outputQty) { this.outputQty = outputQty; }
  public String getUnit() { return unit; }
  public void setUnit(String unit) { this.unit = unit; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getProductionDeptCode() { return productionDeptCode; }
  public void setProductionDeptCode(String productionDeptCode) { this.productionDeptCode = productionDeptCode; }
  public String getProductionDeptName() { return productionDeptName; }
  public void setProductionDeptName(String productionDeptName) { this.productionDeptName = productionDeptName; }
  public LocalDate getEffectiveFrom() { return effectiveFrom; }
  public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
  public LocalDate getEffectiveTo() { return effectiveTo; }
  public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }
  public String getU9CreatedBy() { return u9CreatedBy; }
  public void setU9CreatedBy(String u9CreatedBy) { this.u9CreatedBy = u9CreatedBy; }
  public LocalDateTime getU9CreatedTime() { return u9CreatedTime; }
  public void setU9CreatedTime(LocalDateTime u9CreatedTime) { this.u9CreatedTime = u9CreatedTime; }
  public String getSourceType() { return sourceType; }
  public void setSourceType(String sourceType) { this.sourceType = sourceType; }
  public String getSourceFileName() { return sourceFileName; }
  public void setSourceFileName(String sourceFileName) { this.sourceFileName = sourceFileName; }
  public String getImportedBy() { return importedBy; }
  public void setImportedBy(String importedBy) { this.importedBy = importedBy; }
  public LocalDateTime getImportedAt() { return importedAt; }
  public void setImportedAt(LocalDateTime importedAt) { this.importedAt = importedAt; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
