package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public class CostRunPartItemDto {
  private String oaNo;
  private String partName;
  private String partCode;
  private String productCode;
  private String partDrawingNo;
  private BigDecimal partQty;
  private String shapeAttr;
  private String material;
  private String priceType;
  private String priceSource;
  private String remark;
  private BigDecimal unitPrice;
  private BigDecimal amount;

  /** V10：物料形态（采购件/制造件/委外加工件），用于 6 桶分发 */
  private String materialShape;

  /** V10：取价优先级（priority 越小越先用） */
  private Integer priority;

  /** V10：生效起始日期（含），用于过滤过期路由 */
  private LocalDate effectiveFrom;

  /** V10：生效结束日期（含） */
  private LocalDate effectiveTo;

  /** V10：数据来源系统（srm/oa/u9/cms/manual），用于审计追溯 */
  private String sourceSystem;

  /**
   * T12：成本要素分类（来自 lp_material_master.cost_element），如 '主要材料-包装材料' /
   * '主要材料-原材料' / '主要材料-焊料' / '主要材料-零部件(采购件)'。
   * 前端用它做分组 / 染色（如包装件标橙色提示纳入 OTHER_EXP_PACKAGE）。
   */
  private String costElement;

  public String getOaNo() {
    return oaNo;
  }

  public void setOaNo(String oaNo) {
    this.oaNo = oaNo;
  }

  public String getPartName() {
    return partName;
  }

  public void setPartName(String partName) {
    this.partName = partName;
  }

  public String getPartCode() {
    return partCode;
  }

  public void setPartCode(String partCode) {
    this.partCode = partCode;
  }

  public String getProductCode() {
    return productCode;
  }

  public void setProductCode(String productCode) {
    this.productCode = productCode;
  }

  public String getPartDrawingNo() {
    return partDrawingNo;
  }

  public void setPartDrawingNo(String partDrawingNo) {
    this.partDrawingNo = partDrawingNo;
  }

  public BigDecimal getPartQty() {
    return partQty;
  }

  public void setPartQty(BigDecimal partQty) {
    this.partQty = partQty;
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

  public String getPriceType() {
    return priceType;
  }

  public void setPriceType(String priceType) {
    this.priceType = priceType;
  }

  public String getPriceSource() {
    return priceSource;
  }

  public void setPriceSource(String priceSource) {
    this.priceSource = priceSource;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public void setUnitPrice(BigDecimal unitPrice) {
    this.unitPrice = unitPrice;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getMaterialShape() {
    return materialShape;
  }

  public void setMaterialShape(String materialShape) {
    this.materialShape = materialShape;
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

  public String getCostElement() {
    return costElement;
  }

  public void setCostElement(String costElement) {
    this.costElement = costElement;
  }
}
