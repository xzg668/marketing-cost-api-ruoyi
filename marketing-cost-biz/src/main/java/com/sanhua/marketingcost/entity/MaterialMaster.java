package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("lp_material_master")
public class MaterialMaster {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String materialCode;
  private String materialName;
  private String itemSpec;
  private String itemModel;
  private String drawingNo;
  private String shapeAttr;
  private String material;
  /**
   * T11：成本要素分类（U9 cost_element），含 '主要材料-包装材料' / '主要材料-原材料' /
   * '主要材料-焊料' / '主要材料-零部件(采购件)' 等。包装材料用于自动归集到 OTHER_EXP_PACKAGE。
   */
  private String costElement;
  private BigDecimal theoreticalWeightG;
  private BigDecimal netWeightKg;
  private BigDecimal grossWeightG;
  private String bizUnit;
  private String productionDept;
  private String productionWorkshop;
  private String source;
  private String financeCategory;
  private String purchaseCategory;
  private String productionCategory;
  private String salesCategory;
  private String mainCategoryCode;
  private String mainCategoryName;
  private String productPropertyClass;
  private BigDecimal productProperty;
  private BigDecimal lossRate;
  private BigDecimal dailyCapacity;
  private Integer leadTimeDays;
  private String packageSize;
  private String defaultSupplier;
  private String defaultBuyer;
  private String defaultPlanner;
  private String legacyU9Code;
  private String importBatchId;
  /** 业务单元租户口径：COMMERCIAL / HOUSEHOLD（V22 补齐） */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

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

  public String getItemSpec() {
    return itemSpec;
  }

  public void setItemSpec(String itemSpec) {
    this.itemSpec = itemSpec;
  }

  public String getItemModel() {
    return itemModel;
  }

  public void setItemModel(String itemModel) {
    this.itemModel = itemModel;
  }

  public String getDrawingNo() {
    return drawingNo;
  }

  public void setDrawingNo(String drawingNo) {
    this.drawingNo = drawingNo;
  }

  public String getShapeAttr() {
    return shapeAttr;
  }

  public void setShapeAttr(String shapeAttr) {
    this.shapeAttr = shapeAttr;
  }

  public String getMaterial() {
    return material;
  }

  public void setMaterial(String material) {
    this.material = material;
  }

  public String getCostElement() {
    return costElement;
  }

  public void setCostElement(String costElement) {
    this.costElement = costElement;
  }

  public BigDecimal getTheoreticalWeightG() {
    return theoreticalWeightG;
  }

  public void setTheoreticalWeightG(BigDecimal theoreticalWeightG) {
    this.theoreticalWeightG = theoreticalWeightG;
  }

  public BigDecimal getNetWeightKg() {
    return netWeightKg;
  }

  public void setNetWeightKg(BigDecimal netWeightKg) {
    this.netWeightKg = netWeightKg;
  }

  public BigDecimal getGrossWeightG() {
    return grossWeightG;
  }

  public void setGrossWeightG(BigDecimal grossWeightG) {
    this.grossWeightG = grossWeightG;
  }

  public String getBizUnit() {
    return bizUnit;
  }

  public void setBizUnit(String bizUnit) {
    this.bizUnit = bizUnit;
  }

  public String getProductionDept() {
    return productionDept;
  }

  public void setProductionDept(String productionDept) {
    this.productionDept = productionDept;
  }

  public String getProductionWorkshop() {
    return productionWorkshop;
  }

  public void setProductionWorkshop(String productionWorkshop) {
    this.productionWorkshop = productionWorkshop;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getFinanceCategory() {
    return financeCategory;
  }

  public void setFinanceCategory(String financeCategory) {
    this.financeCategory = financeCategory;
  }

  public String getPurchaseCategory() {
    return purchaseCategory;
  }

  public void setPurchaseCategory(String purchaseCategory) {
    this.purchaseCategory = purchaseCategory;
  }

  public String getProductionCategory() {
    return productionCategory;
  }

  public void setProductionCategory(String productionCategory) {
    this.productionCategory = productionCategory;
  }

  public String getSalesCategory() {
    return salesCategory;
  }

  public void setSalesCategory(String salesCategory) {
    this.salesCategory = salesCategory;
  }

  public String getMainCategoryCode() {
    return mainCategoryCode;
  }

  public void setMainCategoryCode(String mainCategoryCode) {
    this.mainCategoryCode = mainCategoryCode;
  }

  public String getMainCategoryName() {
    return mainCategoryName;
  }

  public void setMainCategoryName(String mainCategoryName) {
    this.mainCategoryName = mainCategoryName;
  }

  public String getProductPropertyClass() {
    return productPropertyClass;
  }

  public void setProductPropertyClass(String productPropertyClass) {
    this.productPropertyClass = productPropertyClass;
  }

  public BigDecimal getProductProperty() {
    return productProperty;
  }

  public void setProductProperty(BigDecimal productProperty) {
    this.productProperty = productProperty;
  }

  public BigDecimal getLossRate() {
    return lossRate;
  }

  public void setLossRate(BigDecimal lossRate) {
    this.lossRate = lossRate;
  }

  public BigDecimal getDailyCapacity() {
    return dailyCapacity;
  }

  public void setDailyCapacity(BigDecimal dailyCapacity) {
    this.dailyCapacity = dailyCapacity;
  }

  public Integer getLeadTimeDays() {
    return leadTimeDays;
  }

  public void setLeadTimeDays(Integer leadTimeDays) {
    this.leadTimeDays = leadTimeDays;
  }

  public String getPackageSize() {
    return packageSize;
  }

  public void setPackageSize(String packageSize) {
    this.packageSize = packageSize;
  }

  public String getDefaultSupplier() {
    return defaultSupplier;
  }

  public void setDefaultSupplier(String defaultSupplier) {
    this.defaultSupplier = defaultSupplier;
  }

  public String getDefaultBuyer() {
    return defaultBuyer;
  }

  public void setDefaultBuyer(String defaultBuyer) {
    this.defaultBuyer = defaultBuyer;
  }

  public String getDefaultPlanner() {
    return defaultPlanner;
  }

  public void setDefaultPlanner(String defaultPlanner) {
    this.defaultPlanner = defaultPlanner;
  }

  public String getLegacyU9Code() {
    return legacyU9Code;
  }

  public void setLegacyU9Code(String legacyU9Code) {
    this.legacyU9Code = legacyU9Code;
  }

  public String getImportBatchId() {
    return importBatchId;
  }

  public void setImportBatchId(String importBatchId) {
    this.importBatchId = importBatchId;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
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
