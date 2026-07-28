package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;

/**
 * 见机表上卷父件的子件成本分项。
 *
 * <p>数据由已固化的上卷子件引用与本次核算实际采用的制造件价格批次共同确定，确保展示拆分
 * 使用的分项成本与父件结算金额属于同一个价格快照。
 */
public class RollupPartComponentDto {
  private Long partItemId;
  private Long bomRowId;
  private String childMaterialCode;
  private String childMaterialName;
  private String childMaterialSpec;
  private BigDecimal childQtyPerTop;
  private BigDecimal childUnitCost;
  private String childRawPriceType;

  public Long getPartItemId() {
    return partItemId;
  }

  public void setPartItemId(Long partItemId) {
    this.partItemId = partItemId;
  }

  public Long getBomRowId() {
    return bomRowId;
  }

  public void setBomRowId(Long bomRowId) {
    this.bomRowId = bomRowId;
  }

  public String getChildMaterialCode() {
    return childMaterialCode;
  }

  public void setChildMaterialCode(String childMaterialCode) {
    this.childMaterialCode = childMaterialCode;
  }

  public String getChildMaterialName() {
    return childMaterialName;
  }

  public void setChildMaterialName(String childMaterialName) {
    this.childMaterialName = childMaterialName;
  }

  public String getChildMaterialSpec() {
    return childMaterialSpec;
  }

  public void setChildMaterialSpec(String childMaterialSpec) {
    this.childMaterialSpec = childMaterialSpec;
  }

  public BigDecimal getChildQtyPerTop() {
    return childQtyPerTop;
  }

  public void setChildQtyPerTop(BigDecimal childQtyPerTop) {
    this.childQtyPerTop = childQtyPerTop;
  }

  public BigDecimal getChildUnitCost() {
    return childUnitCost;
  }

  public void setChildUnitCost(BigDecimal childUnitCost) {
    this.childUnitCost = childUnitCost;
  }

  public String getChildRawPriceType() {
    return childRawPriceType;
  }

  public void setChildRawPriceType(String childRawPriceType) {
    this.childRawPriceType = childRawPriceType;
  }
}
