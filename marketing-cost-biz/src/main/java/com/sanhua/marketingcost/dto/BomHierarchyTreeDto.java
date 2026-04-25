package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 嵌套树 DTO —— {@code GET /api/v1/bom/hierarchy/{topProductCode}} 返回给前端（T6 树查看器）。
 *
 * <p>结构：顶层产品为根，每个节点带自己的业务属性 + {@link #children} 子列表，由
 * Builder 从扁平 lp_bom_raw_hierarchy 行组装（按 parent_code 二次遍历）。
 */
public class BomHierarchyTreeDto {

  private String materialCode;
  private String materialName;
  private String materialSpec;
  private Integer level;
  private String path;
  private BigDecimal qtyPerParent;
  private BigDecimal qtyPerTop;
  private String shapeAttr;
  private String sourceCategory;
  private String bomPurpose;
  private String bomVersion;
  private Integer isLeaf;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;

  private List<BomHierarchyTreeDto> children = new ArrayList<>();

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

  public Integer getLevel() {
    return level;
  }

  public void setLevel(Integer level) {
    this.level = level;
  }

  public String getPath() {
    return path;
  }

  public void setPath(String path) {
    this.path = path;
  }

  public BigDecimal getQtyPerParent() {
    return qtyPerParent;
  }

  public void setQtyPerParent(BigDecimal qtyPerParent) {
    this.qtyPerParent = qtyPerParent;
  }

  public BigDecimal getQtyPerTop() {
    return qtyPerTop;
  }

  public void setQtyPerTop(BigDecimal qtyPerTop) {
    this.qtyPerTop = qtyPerTop;
  }

  public String getShapeAttr() {
    return shapeAttr;
  }

  public void setShapeAttr(String shapeAttr) {
    this.shapeAttr = shapeAttr;
  }

  public String getSourceCategory() {
    return sourceCategory;
  }

  public void setSourceCategory(String sourceCategory) {
    this.sourceCategory = sourceCategory;
  }

  public String getBomPurpose() {
    return bomPurpose;
  }

  public void setBomPurpose(String bomPurpose) {
    this.bomPurpose = bomPurpose;
  }

  public String getBomVersion() {
    return bomVersion;
  }

  public void setBomVersion(String bomVersion) {
    this.bomVersion = bomVersion;
  }

  public Integer getIsLeaf() {
    return isLeaf;
  }

  public void setIsLeaf(Integer isLeaf) {
    this.isLeaf = isLeaf;
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

  public List<BomHierarchyTreeDto> getChildren() {
    return children;
  }

  public void setChildren(List<BomHierarchyTreeDto> children) {
    this.children = children;
  }
}
