package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.PriceVariableBinding;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 行局部变量绑定读视图 —— 列表 / 详情 / 历史共用。
 *
 * <p>比 entity 多一列 {@code factorName}（由 Service 层 join {@code lp_price_variable.variable_name}
 * 填入），前端直接展示，不用再二次查询。
 */
public class PriceVariableBindingDto {

  private Long id;
  private Long linkedItemId;
  private String tokenName;
  private String factorCode;
  /** factorCode 对应的中文显示名；Service 层从 PriceVariable.variableName 回填 */
  private String factorName;
  private String priceSource;
  private Long factorIdentityId;
  private Long factorMonthlyPriceId;
  private Long factorUploadBatchId;
  private Long standardBindingId;
  private String excelSourceSheetName;
  private String excelSourceCellRef;
  private String excelFormula;
  private Integer buScoped;
  private LocalDate effectiveDate;
  private LocalDate expiryDate;
  private String source;
  private String confirmedBy;
  private LocalDateTime confirmedAt;
  private String remark;
  private String createdBy;
  private LocalDateTime createdAt;
  private String updatedBy;
  private LocalDateTime updatedAt;

  /** 从 entity 组装 dto —— factorName 需调用方另行填入 */
  public static PriceVariableBindingDto fromEntity(PriceVariableBinding e) {
    PriceVariableBindingDto d = new PriceVariableBindingDto();
    d.id = e.getId();
    d.linkedItemId = e.getLinkedItemId();
    d.tokenName = e.getTokenName();
    d.factorCode = e.getFactorCode();
    d.priceSource = e.getPriceSource();
    d.factorIdentityId = e.getFactorIdentityId();
    d.factorMonthlyPriceId = e.getFactorMonthlyPriceId();
    d.factorUploadBatchId = e.getFactorUploadBatchId();
    d.standardBindingId = e.getStandardBindingId();
    d.excelSourceSheetName = e.getExcelSourceSheetName();
    d.excelSourceCellRef = e.getExcelSourceCellRef();
    d.excelFormula = e.getExcelFormula();
    d.buScoped = e.getBuScoped();
    d.effectiveDate = e.getEffectiveDate();
    d.expiryDate = e.getExpiryDate();
    d.source = e.getSource();
    d.confirmedBy = e.getConfirmedBy();
    d.confirmedAt = e.getConfirmedAt();
    d.remark = e.getRemark();
    d.createdBy = e.getCreatedBy();
    d.createdAt = e.getCreatedAt();
    d.updatedBy = e.getUpdatedBy();
    d.updatedAt = e.getUpdatedAt();
    return d;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

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

  public String getFactorName() {
    return factorName;
  }

  public void setFactorName(String factorName) {
    this.factorName = factorName;
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

  public LocalDate getExpiryDate() {
    return expiryDate;
  }

  public void setExpiryDate(LocalDate expiryDate) {
    this.expiryDate = expiryDate;
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

  public LocalDateTime getConfirmedAt() {
    return confirmedAt;
  }

  public void setConfirmedAt(LocalDateTime confirmedAt) {
    this.confirmedAt = confirmedAt;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
