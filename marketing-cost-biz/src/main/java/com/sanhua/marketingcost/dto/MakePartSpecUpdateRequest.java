package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 自制件工艺规格 创建/修改请求体 (V48 暴露 UI)
 *
 * <p>对应 lp_make_part_spec 表。承载自制件取价所需的全部输入：
 * <ul>
 *   <li>毛重 / 净重 / 废料率</li>
 *   <li>原材料代号 + 规格 + 单价</li>
 *   <li>回收（边角料）代号 + 单价 + 比例</li>
 *   <li>加工费 / 外发费</li>
 *   <li>公式 ID（可选关联 lp_price_variable 里的派生公式）</li>
 * </ul>
 */
public class MakePartSpecUpdateRequest {
  private String materialCode;
  private String materialName;
  private String drawingNo;
  private String period;
  private BigDecimal blankWeight;
  private BigDecimal netWeight;
  private BigDecimal scrapRate;
  private String rawMaterialCode;
  private String rawMaterialSpec;
  private BigDecimal rawUnitPrice;
  private String recycleCode;
  private BigDecimal recycleUnitPrice;
  private BigDecimal recycleRatio;
  private BigDecimal processFee;
  private BigDecimal outsourceFee;
  private Long formulaId;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String remark;

  public String getMaterialCode() { return materialCode; }
  public void setMaterialCode(String materialCode) { this.materialCode = materialCode; }
  public String getMaterialName() { return materialName; }
  public void setMaterialName(String materialName) { this.materialName = materialName; }
  public String getDrawingNo() { return drawingNo; }
  public void setDrawingNo(String drawingNo) { this.drawingNo = drawingNo; }
  public String getPeriod() { return period; }
  public void setPeriod(String period) { this.period = period; }
  public BigDecimal getBlankWeight() { return blankWeight; }
  public void setBlankWeight(BigDecimal blankWeight) { this.blankWeight = blankWeight; }
  public BigDecimal getNetWeight() { return netWeight; }
  public void setNetWeight(BigDecimal netWeight) { this.netWeight = netWeight; }
  public BigDecimal getScrapRate() { return scrapRate; }
  public void setScrapRate(BigDecimal scrapRate) { this.scrapRate = scrapRate; }
  public String getRawMaterialCode() { return rawMaterialCode; }
  public void setRawMaterialCode(String rawMaterialCode) { this.rawMaterialCode = rawMaterialCode; }
  public String getRawMaterialSpec() { return rawMaterialSpec; }
  public void setRawMaterialSpec(String rawMaterialSpec) { this.rawMaterialSpec = rawMaterialSpec; }
  public BigDecimal getRawUnitPrice() { return rawUnitPrice; }
  public void setRawUnitPrice(BigDecimal rawUnitPrice) { this.rawUnitPrice = rawUnitPrice; }
  public String getRecycleCode() { return recycleCode; }
  public void setRecycleCode(String recycleCode) { this.recycleCode = recycleCode; }
  public BigDecimal getRecycleUnitPrice() { return recycleUnitPrice; }
  public void setRecycleUnitPrice(BigDecimal recycleUnitPrice) { this.recycleUnitPrice = recycleUnitPrice; }
  public BigDecimal getRecycleRatio() { return recycleRatio; }
  public void setRecycleRatio(BigDecimal recycleRatio) { this.recycleRatio = recycleRatio; }
  public BigDecimal getProcessFee() { return processFee; }
  public void setProcessFee(BigDecimal processFee) { this.processFee = processFee; }
  public BigDecimal getOutsourceFee() { return outsourceFee; }
  public void setOutsourceFee(BigDecimal outsourceFee) { this.outsourceFee = outsourceFee; }
  public Long getFormulaId() { return formulaId; }
  public void setFormulaId(Long formulaId) { this.formulaId = formulaId; }
  public LocalDate getEffectiveFrom() { return effectiveFrom; }
  public void setEffectiveFrom(LocalDate effectiveFrom) { this.effectiveFrom = effectiveFrom; }
  public LocalDate getEffectiveTo() { return effectiveTo; }
  public void setEffectiveTo(LocalDate effectiveTo) { this.effectiveTo = effectiveTo; }
  public String getRemark() { return remark; }
  public void setRemark(String remark) { this.remark = remark; }
}
