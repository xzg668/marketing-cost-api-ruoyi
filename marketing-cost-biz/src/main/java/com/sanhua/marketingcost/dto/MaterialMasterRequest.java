package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

public class MaterialMasterRequest {
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
}
