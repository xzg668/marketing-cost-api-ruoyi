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
  private String shapeAttr;
  private String departmentName;
  private String productionDivision;
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

  /**
   * U9 全局段：物料字符串（如"铜"/"不锈钢"），灌进 master.material。
   * <p>列名带下划线在数字前（global_seg_4_material），mybatisplus 默认 camel→snake
   * 不会在数字前加下划线，所以全部数字段都要 {@code @TableField} 显式指定。
   */
  @TableField("global_seg_4_material")
  private String globalSeg4Material;
  @TableField("global_seg_5_net_weight")
  private String globalSeg5NetWeight;
  @TableField("global_seg_7_product_property_class")
  private String globalSeg7ProductPropertyClass;
  @TableField("global_seg_8_loss_rate")
  private String globalSeg8LossRate;
  @TableField("global_seg_9_gross_weight")
  private String globalSeg9GrossWeight;
  @TableField("global_seg_15_package_size")
  private String globalSeg15PackageSize;
  @TableField("private_seg_24_product_property")
  private String privateSeg24ProductProperty;
  @TableField("private_seg_25_daily_capacity")
  private String privateSeg25DailyCapacity;
  @TableField("private_seg_26_lead_time")
  private String privateSeg26LeadTime;

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
  public String getShapeAttr() { return shapeAttr; }
  public void setShapeAttr(String v) { this.shapeAttr = v; }
  public String getDepartmentName() { return departmentName; }
  public void setDepartmentName(String v) { this.departmentName = v; }
  public String getProductionDivision() { return productionDivision; }
  public void setProductionDivision(String v) { this.productionDivision = v; }
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
  public String getGlobalSeg4Material() { return globalSeg4Material; }
  public void setGlobalSeg4Material(String v) { this.globalSeg4Material = v; }
  public String getGlobalSeg5NetWeight() { return globalSeg5NetWeight; }
  public void setGlobalSeg5NetWeight(String v) { this.globalSeg5NetWeight = v; }
  public String getGlobalSeg7ProductPropertyClass() { return globalSeg7ProductPropertyClass; }
  public void setGlobalSeg7ProductPropertyClass(String v) { this.globalSeg7ProductPropertyClass = v; }
  public String getGlobalSeg8LossRate() { return globalSeg8LossRate; }
  public void setGlobalSeg8LossRate(String v) { this.globalSeg8LossRate = v; }
  public String getGlobalSeg9GrossWeight() { return globalSeg9GrossWeight; }
  public void setGlobalSeg9GrossWeight(String v) { this.globalSeg9GrossWeight = v; }
  public String getGlobalSeg15PackageSize() { return globalSeg15PackageSize; }
  public void setGlobalSeg15PackageSize(String v) { this.globalSeg15PackageSize = v; }
  public String getPrivateSeg24ProductProperty() { return privateSeg24ProductProperty; }
  public void setPrivateSeg24ProductProperty(String v) { this.privateSeg24ProductProperty = v; }
  public String getPrivateSeg25DailyCapacity() { return privateSeg25DailyCapacity; }
  public void setPrivateSeg25DailyCapacity(String v) { this.privateSeg25DailyCapacity = v; }
  public String getPrivateSeg26LeadTime() { return privateSeg26LeadTime; }
  public void setPrivateSeg26LeadTime(String v) { this.privateSeg26LeadTime = v; }
}
