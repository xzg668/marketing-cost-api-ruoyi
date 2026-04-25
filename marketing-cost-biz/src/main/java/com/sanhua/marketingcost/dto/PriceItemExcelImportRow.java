package com.sanhua.marketingcost.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 联动/固定价格行 Excel 模型 —— T18 新增。
 *
 * <p>覆盖两张源表：
 * <ul>
 *   <li>"原材料(联动+固定-7）" —— 含 orderType 列，"联动"/"固定" 分流</li>
 *   <li>"联动价-部品6" —— 无 orderType 列，Service 视为全部联动</li>
 * </ul>
 * 列顺序差异用 {@link ExcelProperty} 按表头名绑定，允许 Excel 额外多空白列。
 */
public class PriceItemExcelImportRow {

  @ExcelProperty("组织")
  private String orgCode;

  @ExcelProperty("来源")
  private String sourceName;

  @ExcelProperty("供应商名称")
  private String supplierName;

  @ExcelProperty("供应商代码")
  private String supplierCode;

  @ExcelProperty("采购分类")
  private String purchaseClass;

  @ExcelProperty("物料名称")
  private String materialName;

  @ExcelProperty("物料代码")
  private String materialCode;

  @ExcelProperty("规格型号")
  private String specModel;

  @ExcelProperty("单位")
  private String unit;

  /** 联动公式列（"原材料(联动+固定)" 用）。 */
  @ExcelProperty("联动公式")
  private String formulaExpr;

  @ExcelProperty("下料重")
  private BigDecimal blankWeight;

  @ExcelProperty("净重")
  private BigDecimal netWeight;

  @ExcelProperty("加工费")
  private BigDecimal processFee;

  @ExcelProperty("代理费")
  private BigDecimal agentFee;

  /** 单价列；联动行作为前端参考/手工价，固定行作为 fixed_price 入库。 */
  @ExcelProperty("单价")
  private BigDecimal unitPrice;

  /** 是否含税：0/1 或 true/false；空则默认 1（含税）。 */
  @ExcelProperty("是否含税")
  private String taxIncluded;

  @ExcelProperty("生效日期")
  private LocalDate effectiveFrom;

  @ExcelProperty("失效日期")
  private LocalDate effectiveTo;

  /** "联动" / "固定" / 空（空视为联动，适配"联动价-部品6"）。 */
  @ExcelProperty("订单类型")
  private String orderType;

  public String getOrgCode() {
    return orgCode;
  }

  public void setOrgCode(String orgCode) {
    this.orgCode = orgCode;
  }

  public String getSourceName() {
    return sourceName;
  }

  public void setSourceName(String sourceName) {
    this.sourceName = sourceName;
  }

  public String getSupplierName() {
    return supplierName;
  }

  public void setSupplierName(String supplierName) {
    this.supplierName = supplierName;
  }

  public String getSupplierCode() {
    return supplierCode;
  }

  public void setSupplierCode(String supplierCode) {
    this.supplierCode = supplierCode;
  }

  public String getPurchaseClass() {
    return purchaseClass;
  }

  public void setPurchaseClass(String purchaseClass) {
    this.purchaseClass = purchaseClass;
  }

  public String getMaterialName() {
    return materialName;
  }

  public void setMaterialName(String materialName) {
    this.materialName = materialName;
  }

  public String getMaterialCode() {
    return materialCode;
  }

  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }

  public String getSpecModel() {
    return specModel;
  }

  public void setSpecModel(String specModel) {
    this.specModel = specModel;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public String getFormulaExpr() {
    return formulaExpr;
  }

  public void setFormulaExpr(String formulaExpr) {
    this.formulaExpr = formulaExpr;
  }

  public BigDecimal getBlankWeight() {
    return blankWeight;
  }

  public void setBlankWeight(BigDecimal blankWeight) {
    this.blankWeight = blankWeight;
  }

  public BigDecimal getNetWeight() {
    return netWeight;
  }

  public void setNetWeight(BigDecimal netWeight) {
    this.netWeight = netWeight;
  }

  public BigDecimal getProcessFee() {
    return processFee;
  }

  public void setProcessFee(BigDecimal processFee) {
    this.processFee = processFee;
  }

  public BigDecimal getAgentFee() {
    return agentFee;
  }

  public void setAgentFee(BigDecimal agentFee) {
    this.agentFee = agentFee;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public void setUnitPrice(BigDecimal unitPrice) {
    this.unitPrice = unitPrice;
  }

  public String getTaxIncluded() {
    return taxIncluded;
  }

  public void setTaxIncluded(String taxIncluded) {
    this.taxIncluded = taxIncluded;
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

  public String getOrderType() {
    return orderType;
  }

  public void setOrderType(String orderType) {
    this.orderType = orderType;
  }
}
