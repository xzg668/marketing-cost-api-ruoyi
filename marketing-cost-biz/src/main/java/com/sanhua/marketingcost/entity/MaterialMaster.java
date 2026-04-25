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
  private BigDecimal theoreticalWeightG;
  private BigDecimal netWeightKg;
  private String bizUnit;
  private String productionDept;
  private String productionWorkshop;
  private String source;
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
