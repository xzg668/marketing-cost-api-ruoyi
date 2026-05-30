package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * U9 物料主档 staging 表。
 *
 * <p>T15：试算入口自动同步用。Python `scripts/sync_material_master.py` 移植到 Java 后只读这张表的
 * 字段，写入 {@code lp_material_master}。本 entity 只声明同步真正用到的列，避免几十个 VARCHAR
 * 字段全展开成 setter 占位。
 */
@TableName("lp_material_master_raw")
public class MaterialMasterRaw {
  @TableId(type = IdType.AUTO)
  private Long id;

  private String materialCode;
  private String materialName;
  private String materialSpec;
  private String materialModel;
  private String drawingNo;
  private String bareCode;
  private String unit;
  private String shapeAttr;
  private String minEcoBatch;
  private String departmentCode;
  private String departmentName;
  private String productionDivision;
  private String purchaseLeadTime;
  private String purchasePostLeadTime;
  private String costElement;
  private String financeCategory;
  private String purchaseCategory;
  private String productionCategory;
  private String salesCategory;
  private String mainCategoryCode;
  private String mainCategoryName;
  private String legacyU9Code;
  private String defaultSupplier;
  private String defaultBuyer;
  private String defaultPlanner;
  private String importBatchId;
  private String sourceType;
  private String sourceBatchNo;
  private String mappingVersion;
  private Integer activeFlag;

  /**
   * U9 全局段：物料字符串（如"铜"/"不锈钢"），灌进 master.material。
   * <p>列名带下划线在数字前（global_seg_4_material），mybatisplus 默认 camel→snake
   * 不会在数字前加下划线，所以全部数字段都要 {@code @TableField} 显式指定。
   */
  @TableField("global_seg_4_material")
  private String globalSeg4Material;
  @TableField("global_seg_2_logistics_type")
  private String globalSeg2LogisticsType;
  @TableField("global_seg_3_status")
  private String globalSeg3Status;
  @TableField("global_seg_5_net_weight")
  private String globalSeg5NetWeight;
  @TableField("global_seg_6_valid_period")
  private String globalSeg6ValidPeriod;
  @TableField("global_seg_3_theoretical_net_weight")
  private String globalSeg3TheoreticalNetWeight;
  @TableField("global_seg_7_product_property_class")
  private String globalSeg7ProductPropertyClass;
  @TableField("global_seg_8_loss_rate")
  private String globalSeg8LossRate;
  @TableField("global_seg_9_gross_weight")
  private String globalSeg9GrossWeight;
  @TableField("global_seg_14_customs_unit")
  private String globalSeg14CustomsUnit;
  @TableField("global_seg_15_package_size")
  private String globalSeg15PackageSize;
  @TableField("global_seg_17_replace_strategy")
  private String globalSeg17ReplaceStrategy;
  @TableField("global_seg_18_purchase_type")
  private String globalSeg18PurchaseType;
  @TableField("global_seg_19_in_out_ratio")
  private String globalSeg19InOutRatio;
  @TableField("global_seg_20_internal_threshold")
  private String globalSeg20InternalThreshold;
  @TableField("private_seg_21_customs_name")
  private String privateSeg21CustomsName;
  @TableField("private_seg_22_customs_code")
  private String privateSeg22CustomsCode;
  @TableField("private_seg_23_customs_desc")
  private String privateSeg23CustomsDesc;
  @TableField("private_seg_24_product_property")
  private String privateSeg24ProductProperty;
  @TableField("private_seg_25_daily_capacity")
  private String privateSeg25DailyCapacity;
  @TableField("private_seg_26_lead_time")
  private String privateSeg26LeadTime;
  private String purchaseMultiple;
  private String minOrderQty;
  private String planMethod;
  private String forecastControlType;
  private String demandTrace;
  private String demandCategoryControl;
  private String demandCategoryCompareRule;
  private String engineeringChangeControl;
  private String allowOverPick;
  private String prepareOverType;
  private String overCompleteType;
  private String overCompleteRatio;
  private String inventoryPlanningMethod;
  private String codeInventoryAccount;
  private String producible;
  private String purchaseReceivePrinciple;
  private String mrpPurchasePreLeadTime;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getMaterialCode() { return materialCode; }
  public void setMaterialCode(String v) { this.materialCode = v; }
  public String getMaterialName() { return materialName; }
  public void setMaterialName(String v) { this.materialName = v; }
  public String getMaterialSpec() { return materialSpec; }
  public void setMaterialSpec(String v) { this.materialSpec = v; }
  public String getMaterialModel() { return materialModel; }
  public void setMaterialModel(String v) { this.materialModel = v; }
  public String getDrawingNo() { return drawingNo; }
  public void setDrawingNo(String v) { this.drawingNo = v; }
  public String getBareCode() { return bareCode; }
  public void setBareCode(String v) { this.bareCode = v; }
  public String getUnit() { return unit; }
  public void setUnit(String v) { this.unit = v; }
  public String getShapeAttr() { return shapeAttr; }
  public void setShapeAttr(String v) { this.shapeAttr = v; }
  public String getMinEcoBatch() { return minEcoBatch; }
  public void setMinEcoBatch(String v) { this.minEcoBatch = v; }
  public String getDepartmentCode() { return departmentCode; }
  public void setDepartmentCode(String v) { this.departmentCode = v; }
  public String getDepartmentName() { return departmentName; }
  public void setDepartmentName(String v) { this.departmentName = v; }
  public String getProductionDivision() { return productionDivision; }
  public void setProductionDivision(String v) { this.productionDivision = v; }
  public String getPurchaseLeadTime() { return purchaseLeadTime; }
  public void setPurchaseLeadTime(String v) { this.purchaseLeadTime = v; }
  public String getPurchasePostLeadTime() { return purchasePostLeadTime; }
  public void setPurchasePostLeadTime(String v) { this.purchasePostLeadTime = v; }
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
  public String getLegacyU9Code() { return legacyU9Code; }
  public void setLegacyU9Code(String v) { this.legacyU9Code = v; }
  public String getDefaultSupplier() { return defaultSupplier; }
  public void setDefaultSupplier(String v) { this.defaultSupplier = v; }
  public String getDefaultBuyer() { return defaultBuyer; }
  public void setDefaultBuyer(String v) { this.defaultBuyer = v; }
  public String getDefaultPlanner() { return defaultPlanner; }
  public void setDefaultPlanner(String v) { this.defaultPlanner = v; }
  public String getImportBatchId() { return importBatchId; }
  public void setImportBatchId(String v) { this.importBatchId = v; }
  public String getSourceType() { return sourceType; }
  public void setSourceType(String v) { this.sourceType = v; }
  public String getSourceBatchNo() { return sourceBatchNo; }
  public void setSourceBatchNo(String v) { this.sourceBatchNo = v; }
  public String getMappingVersion() { return mappingVersion; }
  public void setMappingVersion(String v) { this.mappingVersion = v; }
  public Integer getActiveFlag() { return activeFlag; }
  public void setActiveFlag(Integer v) { this.activeFlag = v; }
  public String getGlobalSeg4Material() { return globalSeg4Material; }
  public void setGlobalSeg4Material(String v) { this.globalSeg4Material = v; }
  public String getGlobalSeg2LogisticsType() { return globalSeg2LogisticsType; }
  public void setGlobalSeg2LogisticsType(String v) { this.globalSeg2LogisticsType = v; }
  public String getGlobalSeg3Status() { return globalSeg3Status; }
  public void setGlobalSeg3Status(String v) { this.globalSeg3Status = v; }
  public String getGlobalSeg5NetWeight() { return globalSeg5NetWeight; }
  public void setGlobalSeg5NetWeight(String v) { this.globalSeg5NetWeight = v; }
  public String getGlobalSeg6ValidPeriod() { return globalSeg6ValidPeriod; }
  public void setGlobalSeg6ValidPeriod(String v) { this.globalSeg6ValidPeriod = v; }
  public String getGlobalSeg3TheoreticalNetWeight() { return globalSeg3TheoreticalNetWeight; }
  public void setGlobalSeg3TheoreticalNetWeight(String v) { this.globalSeg3TheoreticalNetWeight = v; }
  public String getGlobalSeg7ProductPropertyClass() { return globalSeg7ProductPropertyClass; }
  public void setGlobalSeg7ProductPropertyClass(String v) { this.globalSeg7ProductPropertyClass = v; }
  public String getGlobalSeg8LossRate() { return globalSeg8LossRate; }
  public void setGlobalSeg8LossRate(String v) { this.globalSeg8LossRate = v; }
  public String getGlobalSeg9GrossWeight() { return globalSeg9GrossWeight; }
  public void setGlobalSeg9GrossWeight(String v) { this.globalSeg9GrossWeight = v; }
  public String getGlobalSeg14CustomsUnit() { return globalSeg14CustomsUnit; }
  public void setGlobalSeg14CustomsUnit(String v) { this.globalSeg14CustomsUnit = v; }
  public String getGlobalSeg15PackageSize() { return globalSeg15PackageSize; }
  public void setGlobalSeg15PackageSize(String v) { this.globalSeg15PackageSize = v; }
  public String getGlobalSeg17ReplaceStrategy() { return globalSeg17ReplaceStrategy; }
  public void setGlobalSeg17ReplaceStrategy(String v) { this.globalSeg17ReplaceStrategy = v; }
  public String getGlobalSeg18PurchaseType() { return globalSeg18PurchaseType; }
  public void setGlobalSeg18PurchaseType(String v) { this.globalSeg18PurchaseType = v; }
  public String getGlobalSeg19InOutRatio() { return globalSeg19InOutRatio; }
  public void setGlobalSeg19InOutRatio(String v) { this.globalSeg19InOutRatio = v; }
  public String getGlobalSeg20InternalThreshold() { return globalSeg20InternalThreshold; }
  public void setGlobalSeg20InternalThreshold(String v) { this.globalSeg20InternalThreshold = v; }
  public String getPrivateSeg21CustomsName() { return privateSeg21CustomsName; }
  public void setPrivateSeg21CustomsName(String v) { this.privateSeg21CustomsName = v; }
  public String getPrivateSeg22CustomsCode() { return privateSeg22CustomsCode; }
  public void setPrivateSeg22CustomsCode(String v) { this.privateSeg22CustomsCode = v; }
  public String getPrivateSeg23CustomsDesc() { return privateSeg23CustomsDesc; }
  public void setPrivateSeg23CustomsDesc(String v) { this.privateSeg23CustomsDesc = v; }
  public String getPrivateSeg24ProductProperty() { return privateSeg24ProductProperty; }
  public void setPrivateSeg24ProductProperty(String v) { this.privateSeg24ProductProperty = v; }
  public String getPrivateSeg25DailyCapacity() { return privateSeg25DailyCapacity; }
  public void setPrivateSeg25DailyCapacity(String v) { this.privateSeg25DailyCapacity = v; }
  public String getPrivateSeg26LeadTime() { return privateSeg26LeadTime; }
  public void setPrivateSeg26LeadTime(String v) { this.privateSeg26LeadTime = v; }
  public String getPurchaseMultiple() { return purchaseMultiple; }
  public void setPurchaseMultiple(String v) { this.purchaseMultiple = v; }
  public String getMinOrderQty() { return minOrderQty; }
  public void setMinOrderQty(String v) { this.minOrderQty = v; }
  public String getPlanMethod() { return planMethod; }
  public void setPlanMethod(String v) { this.planMethod = v; }
  public String getForecastControlType() { return forecastControlType; }
  public void setForecastControlType(String v) { this.forecastControlType = v; }
  public String getDemandTrace() { return demandTrace; }
  public void setDemandTrace(String v) { this.demandTrace = v; }
  public String getDemandCategoryControl() { return demandCategoryControl; }
  public void setDemandCategoryControl(String v) { this.demandCategoryControl = v; }
  public String getDemandCategoryCompareRule() { return demandCategoryCompareRule; }
  public void setDemandCategoryCompareRule(String v) { this.demandCategoryCompareRule = v; }
  public String getEngineeringChangeControl() { return engineeringChangeControl; }
  public void setEngineeringChangeControl(String v) { this.engineeringChangeControl = v; }
  public String getAllowOverPick() { return allowOverPick; }
  public void setAllowOverPick(String v) { this.allowOverPick = v; }
  public String getPrepareOverType() { return prepareOverType; }
  public void setPrepareOverType(String v) { this.prepareOverType = v; }
  public String getOverCompleteType() { return overCompleteType; }
  public void setOverCompleteType(String v) { this.overCompleteType = v; }
  public String getOverCompleteRatio() { return overCompleteRatio; }
  public void setOverCompleteRatio(String v) { this.overCompleteRatio = v; }
  public String getInventoryPlanningMethod() { return inventoryPlanningMethod; }
  public void setInventoryPlanningMethod(String v) { this.inventoryPlanningMethod = v; }
  public String getCodeInventoryAccount() { return codeInventoryAccount; }
  public void setCodeInventoryAccount(String v) { this.codeInventoryAccount = v; }
  public String getProducible() { return producible; }
  public void setProducible(String v) { this.producible = v; }
  public String getPurchaseReceivePrinciple() { return purchaseReceivePrinciple; }
  public void setPurchaseReceivePrinciple(String v) { this.purchaseReceivePrinciple = v; }
  public String getMrpPurchasePreLeadTime() { return mrpPurchasePreLeadTime; }
  public void setMrpPurchasePreLeadTime(String v) { this.mrpPurchasePreLeadTime = v; }
}
