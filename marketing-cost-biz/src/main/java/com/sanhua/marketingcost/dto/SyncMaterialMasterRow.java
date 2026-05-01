package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

/**
 * T15：主档同步用的行级 DTO。
 *
 * <p>{@link com.sanhua.marketingcost.entity.MaterialMaster} entity 被多处 mapper 用，
 * 不在它身上加 33 个新字段。同步路径专用一个独立 POJO 喂 UPSERT mapper 即可。
 *
 * <p>Service 层负责把 staging {@code lp_material_master_raw} 的 VARCHAR 字段类型转换
 * （g→kg / VARCHAR→DECIMAL/INT，转换失败置 null）、推断 business_unit_type，再喂这个对象。
 */
public class SyncMaterialMasterRow {
  private String materialCode;
  private String materialName;
  private String itemSpec;
  private String itemModel;
  private String drawingNo;
  private String shapeAttr;
  private String material;
  private BigDecimal netWeightKg;
  private BigDecimal grossWeightG;
  private String businessUnitType;
  private String bizUnit;
  private String productionDept;
  private String productionWorkshop;
  private String costElement;
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
  private String source;

  public String getMaterialCode() { return materialCode; }
  public void setMaterialCode(String v) { this.materialCode = v; }
  public String getMaterialName() { return materialName; }
  public void setMaterialName(String v) { this.materialName = v; }
  public String getItemSpec() { return itemSpec; }
  public void setItemSpec(String v) { this.itemSpec = v; }
  public String getItemModel() { return itemModel; }
  public void setItemModel(String v) { this.itemModel = v; }
  public String getDrawingNo() { return drawingNo; }
  public void setDrawingNo(String v) { this.drawingNo = v; }
  public String getShapeAttr() { return shapeAttr; }
  public void setShapeAttr(String v) { this.shapeAttr = v; }
  public String getMaterial() { return material; }
  public void setMaterial(String v) { this.material = v; }
  public BigDecimal getNetWeightKg() { return netWeightKg; }
  public void setNetWeightKg(BigDecimal v) { this.netWeightKg = v; }
  public BigDecimal getGrossWeightG() { return grossWeightG; }
  public void setGrossWeightG(BigDecimal v) { this.grossWeightG = v; }
  public String getBusinessUnitType() { return businessUnitType; }
  public void setBusinessUnitType(String v) { this.businessUnitType = v; }
  public String getBizUnit() { return bizUnit; }
  public void setBizUnit(String v) { this.bizUnit = v; }
  public String getProductionDept() { return productionDept; }
  public void setProductionDept(String v) { this.productionDept = v; }
  public String getProductionWorkshop() { return productionWorkshop; }
  public void setProductionWorkshop(String v) { this.productionWorkshop = v; }
  public String getCostElement() { return costElement; }
  public void setCostElement(String v) { this.costElement = v; }
  public String getFinanceCategory() { return financeCategory; }
  public void setFinanceCategory(String v) { this.financeCategory = v; }
  public String getPurchaseCategory() { return purchaseCategory; }
  public void setPurchaseCategory(String v) { this.purchaseCategory = v; }
  public String getProductionCategory() { return productionCategory; }
  public void setProductionCategory(String v) { this.productionCategory = v; }
  public String getSalesCategory() { return salesCategory; }
  public void setSalesCategory(String v) { this.salesCategory = v; }
  public String getMainCategoryCode() { return mainCategoryCode; }
  public void setMainCategoryCode(String v) { this.mainCategoryCode = v; }
  public String getMainCategoryName() { return mainCategoryName; }
  public void setMainCategoryName(String v) { this.mainCategoryName = v; }
  public String getProductPropertyClass() { return productPropertyClass; }
  public void setProductPropertyClass(String v) { this.productPropertyClass = v; }
  public BigDecimal getProductProperty() { return productProperty; }
  public void setProductProperty(BigDecimal v) { this.productProperty = v; }
  public BigDecimal getLossRate() { return lossRate; }
  public void setLossRate(BigDecimal v) { this.lossRate = v; }
  public BigDecimal getDailyCapacity() { return dailyCapacity; }
  public void setDailyCapacity(BigDecimal v) { this.dailyCapacity = v; }
  public Integer getLeadTimeDays() { return leadTimeDays; }
  public void setLeadTimeDays(Integer v) { this.leadTimeDays = v; }
  public String getPackageSize() { return packageSize; }
  public void setPackageSize(String v) { this.packageSize = v; }
  public String getDefaultSupplier() { return defaultSupplier; }
  public void setDefaultSupplier(String v) { this.defaultSupplier = v; }
  public String getDefaultBuyer() { return defaultBuyer; }
  public void setDefaultBuyer(String v) { this.defaultBuyer = v; }
  public String getDefaultPlanner() { return defaultPlanner; }
  public void setDefaultPlanner(String v) { this.defaultPlanner = v; }
  public String getLegacyU9Code() { return legacyU9Code; }
  public void setLegacyU9Code(String v) { this.legacyU9Code = v; }
  public String getImportBatchId() { return importBatchId; }
  public void setImportBatchId(String v) { this.importBatchId = v; }
  public String getSource() { return source; }
  public void setSource(String v) { this.source = v; }
}
