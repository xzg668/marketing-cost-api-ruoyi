package com.sanhua.marketingcost.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/**
 * 行局部变量绑定写入请求 —— 新增 / 更新共用。
 *
 * <p>写入语义由 Service 层的"当前生效版本探测"决定：
 * <ul>
 *   <li>同 (linked_item_id, token_name) 无当前生效行 → 直接 INSERT</li>
 *   <li>同 key 有当前生效行且 effective_date 相同 → 原地 UPDATE</li>
 *   <li>同 key 有当前生效行但 effective_date 更早 → 旧行 {@code expiry_date = new - 1d}，
 *       同时 INSERT 新行（"版本切换"）</li>
 * </ul>
 */
public class PriceVariableBindingRequest {

  /** 联动价行 ID，外键 {@code lp_price_linked_item.id} */
  @NotNull
  private Long linkedItemId;

  /** B 组 token 字面：材料含税价格/材料价格/废料含税价格/废料价格 */
  @NotBlank
  private String tokenName;

  /** 指向 {@code lp_price_variable.variable_code}，Service 会校验目标变量已登记 */
  @NotBlank
  private String factorCode;

  /** 价源：平均价/出厂价/招标价/... null 时由被指变量决定 */
  private String priceSource;

  /** V75：稳定影响因素身份；手工绑定可为空 */
  private Long factorIdentityId;

  /** V75：导入当时解析到的月度价格，仅审计快照 */
  private Long factorMonthlyPriceId;

  /** V75：来源影响因素上传批次 */
  private Long factorUploadBatchId;

  /** V76：料号历史校验关系 id */
  private Long standardBindingId;

  /** V75：Excel 公式引用 sheet */
  private String excelSourceSheetName;

  /** V75：Excel 公式引用单元格，如 E64 */
  private String excelSourceCellRef;

  /** V75：识别来源单价列公式 */
  private String excelFormula;

  /** 是否按 BU 隔离查询；null 时默认 1 */
  private Integer buScoped;

  /** 生效日期；null 时服务端默认当天 */
  private LocalDate effectiveDate;

  /** 来源：EXCEL_INFERRED / SUPPLY_CONFIRMED / MANUAL —— 写接口默认 MANUAL */
  private String source;

  /** 供管部确认人，source=SUPPLY_CONFIRMED 时建议填写 */
  private String confirmedBy;

  /** 备注 */
  private String remark;

  public Long getLinkedItemId() {
    return linkedItemId;
  }

  public void setLinkedItemId(Long linkedItemId) {
    this.linkedItemId = linkedItemId;
  }

  public String getTokenName() {
    return tokenName;
  }

  public void setTokenName(String tokenName) {
    this.tokenName = tokenName;
  }

  public String getFactorCode() {
    return factorCode;
  }

  public void setFactorCode(String factorCode) {
    this.factorCode = factorCode;
  }

  public String getPriceSource() {
    return priceSource;
  }

  public void setPriceSource(String priceSource) {
    this.priceSource = priceSource;
  }

  public Long getFactorIdentityId() {
    return factorIdentityId;
  }

  public void setFactorIdentityId(Long factorIdentityId) {
    this.factorIdentityId = factorIdentityId;
  }

  public Long getFactorMonthlyPriceId() {
    return factorMonthlyPriceId;
  }

  public void setFactorMonthlyPriceId(Long factorMonthlyPriceId) {
    this.factorMonthlyPriceId = factorMonthlyPriceId;
  }

  public Long getFactorUploadBatchId() {
    return factorUploadBatchId;
  }

  public void setFactorUploadBatchId(Long factorUploadBatchId) {
    this.factorUploadBatchId = factorUploadBatchId;
  }

  public Long getStandardBindingId() {
    return standardBindingId;
  }

  public void setStandardBindingId(Long standardBindingId) {
    this.standardBindingId = standardBindingId;
  }

  public String getExcelSourceSheetName() {
    return excelSourceSheetName;
  }

  public void setExcelSourceSheetName(String excelSourceSheetName) {
    this.excelSourceSheetName = excelSourceSheetName;
  }

  public String getExcelSourceCellRef() {
    return excelSourceCellRef;
  }

  public void setExcelSourceCellRef(String excelSourceCellRef) {
    this.excelSourceCellRef = excelSourceCellRef;
  }

  public String getExcelFormula() {
    return excelFormula;
  }

  public void setExcelFormula(String excelFormula) {
    this.excelFormula = excelFormula;
  }

  public Integer getBuScoped() {
    return buScoped;
  }

  public void setBuScoped(Integer buScoped) {
    this.buScoped = buScoped;
  }

  public LocalDate getEffectiveDate() {
    return effectiveDate;
  }

  public void setEffectiveDate(LocalDate effectiveDate) {
    this.effectiveDate = effectiveDate;
  }

  public String getSource() {
    return source;
  }

  public void setSource(String source) {
    this.source = source;
  }

  public String getConfirmedBy() {
    return confirmedBy;
  }

  public void setConfirmedBy(String confirmedBy) {
    this.confirmedBy = confirmedBy;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }
}
