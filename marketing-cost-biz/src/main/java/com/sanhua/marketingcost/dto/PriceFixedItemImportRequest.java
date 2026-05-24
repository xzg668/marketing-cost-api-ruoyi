package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public class PriceFixedItemImportRequest {
  private List<PriceFixedItemImportRow> rows;
  private String sourceBatchNo;
  private String importFileName;
  private String importedBy;

  public List<PriceFixedItemImportRow> getRows() {
    return rows;
  }

  public void setRows(List<PriceFixedItemImportRow> rows) {
    this.rows = rows;
  }

  public String getSourceBatchNo() {
    return sourceBatchNo;
  }

  public void setSourceBatchNo(String sourceBatchNo) {
    this.sourceBatchNo = sourceBatchNo;
  }

  public String getImportFileName() {
    return importFileName;
  }

  public void setImportFileName(String importFileName) {
    this.importFileName = importFileName;
  }

  public String getImportedBy() {
    return importedBy;
  }

  public void setImportedBy(String importedBy) {
    this.importedBy = importedBy;
  }

  public static class PriceFixedItemImportRow {
    private String orgCode;
    private String sourceName;
    private String supplierName;
    private String supplierCode;
    private String purchaseClass;
    private String materialName;
    private String materialCode;
    private String specModel;
    private String unit;
    private String formulaExpr;
    private BigDecimal blankWeight;
    private BigDecimal netWeight;
    private BigDecimal processFee;
    private BigDecimal agentFee;
    private BigDecimal fixedPrice;
    private Boolean taxIncluded;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String orderType;
    private BigDecimal quota;
    /** V46 来源类型 */
    private String sourceType;
    /** V46 采购流程编号 */
    private String processNo;
    /** V46 计划价（SETTLE 来源）*/
    private BigDecimal plannedPrice;
    /** V46 上浮比例（SETTLE 来源）*/
    private BigDecimal markupRatio;
    /** V46 备注 */
    private String remark;
    /** V46 结算期间 YYYY-MM */
    private String pricingMonth;
    /** V47 基准结算价（C5）*/
    private BigDecimal baseSettlePrice;
    /** V47 联动结算价（C6）*/
    private BigDecimal linkedSettlePrice;

    /** 来源系统：OA/SRM/U9/EXCEL/MANUAL */
    private String sourceSystem;
    /** 来源 sheet 名 */
    private String sourceSheetName;
    /** Excel 原始行号 */
    private Integer sourceRowNo;
    /** 行级导入批次号；为空时使用请求级批次号 */
    private String sourceBatchNo;
    /** 行级导入文件名；为空时使用请求级文件名 */
    private String importFileName;
    /** 行级导入人；为空时使用请求级导入人 */
    private String importedBy;
    /** 导入时间 */
    private LocalDateTime importedAt;

    /** 固定采购价5 的 id；非 U9 行后续按该字段幂等 */
    private String externalRowId;
    private String processStatus;
    private String srmDocNo;
    private String materialCategory;
    private BigDecimal taxRate;
    private BigDecimal originalProcessFee;
    private BigDecimal originalProcessFeeTaxIncluded;
    private BigDecimal originalTaxExcludedPrice;
    private BigDecimal originalTaxIncludedPrice;
    private String originalSupplierName;
    private BigDecimal currentProcessFee;
    private BigDecimal currentProcessFeeTaxIncluded;
    /** 固定采购价主价格来源：现不含税价格，导入时应和 fixedPrice 对齐 */
    private BigDecimal currentTaxExcludedPrice;
    private BigDecimal currentTaxIncludedPrice;
    private String currentSupplierName;
    private BigDecimal changeAmount;
    private BigDecimal changeRate;
    private String executionPeriodText;
    private String annualUsageText;
    private String applicant;
    private String applyDept;
    private String marketSituation;
    private String similarCompare;
    private String approvalConclusion;
    private String approvalType;
    private String businessDivision;
    private LocalDateTime generalManagerApprovedAt;
    private LocalDate trackingDate;
    private String printFlag;

    /** 结算固定价最后一列表头 */
    private String settleReferenceHeader;
    /** 最后一列是“价格或备注”混合列：非数字如“不用提供”写这里，不写 fixedPrice。 */
    private String settleReferenceText;
    /** 最后一列是数字时写这里，并同步到 fixedPrice 参与取价。 */
    private BigDecimal settleReferencePrice;

    public BigDecimal getBaseSettlePrice() { return baseSettlePrice; }
    public void setBaseSettlePrice(BigDecimal v) { this.baseSettlePrice = v; }
    public BigDecimal getLinkedSettlePrice() { return linkedSettlePrice; }
    public void setLinkedSettlePrice(BigDecimal v) { this.linkedSettlePrice = v; }

    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
    public String getSourceSheetName() { return sourceSheetName; }
    public void setSourceSheetName(String sourceSheetName) { this.sourceSheetName = sourceSheetName; }
    public Integer getSourceRowNo() { return sourceRowNo; }
    public void setSourceRowNo(Integer sourceRowNo) { this.sourceRowNo = sourceRowNo; }
    public String getSourceBatchNo() { return sourceBatchNo; }
    public void setSourceBatchNo(String sourceBatchNo) { this.sourceBatchNo = sourceBatchNo; }
    public String getImportFileName() { return importFileName; }
    public void setImportFileName(String importFileName) { this.importFileName = importFileName; }
    public String getImportedBy() { return importedBy; }
    public void setImportedBy(String importedBy) { this.importedBy = importedBy; }
    public LocalDateTime getImportedAt() { return importedAt; }
    public void setImportedAt(LocalDateTime importedAt) { this.importedAt = importedAt; }

    public String getExternalRowId() { return externalRowId; }
    public void setExternalRowId(String externalRowId) { this.externalRowId = externalRowId; }
    public String getProcessStatus() { return processStatus; }
    public void setProcessStatus(String processStatus) { this.processStatus = processStatus; }
    public String getSrmDocNo() { return srmDocNo; }
    public void setSrmDocNo(String srmDocNo) { this.srmDocNo = srmDocNo; }
    public String getMaterialCategory() { return materialCategory; }
    public void setMaterialCategory(String materialCategory) { this.materialCategory = materialCategory; }
    public BigDecimal getTaxRate() { return taxRate; }
    public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate; }
    public BigDecimal getOriginalProcessFee() { return originalProcessFee; }
    public void setOriginalProcessFee(BigDecimal originalProcessFee) { this.originalProcessFee = originalProcessFee; }
    public BigDecimal getOriginalProcessFeeTaxIncluded() { return originalProcessFeeTaxIncluded; }
    public void setOriginalProcessFeeTaxIncluded(BigDecimal originalProcessFeeTaxIncluded) { this.originalProcessFeeTaxIncluded = originalProcessFeeTaxIncluded; }
    public BigDecimal getOriginalTaxExcludedPrice() { return originalTaxExcludedPrice; }
    public void setOriginalTaxExcludedPrice(BigDecimal originalTaxExcludedPrice) { this.originalTaxExcludedPrice = originalTaxExcludedPrice; }
    public BigDecimal getOriginalTaxIncludedPrice() { return originalTaxIncludedPrice; }
    public void setOriginalTaxIncludedPrice(BigDecimal originalTaxIncludedPrice) { this.originalTaxIncludedPrice = originalTaxIncludedPrice; }
    public String getOriginalSupplierName() { return originalSupplierName; }
    public void setOriginalSupplierName(String originalSupplierName) { this.originalSupplierName = originalSupplierName; }
    public BigDecimal getCurrentProcessFee() { return currentProcessFee; }
    public void setCurrentProcessFee(BigDecimal currentProcessFee) { this.currentProcessFee = currentProcessFee; }
    public BigDecimal getCurrentProcessFeeTaxIncluded() { return currentProcessFeeTaxIncluded; }
    public void setCurrentProcessFeeTaxIncluded(BigDecimal currentProcessFeeTaxIncluded) { this.currentProcessFeeTaxIncluded = currentProcessFeeTaxIncluded; }
    public BigDecimal getCurrentTaxExcludedPrice() { return currentTaxExcludedPrice; }
    public void setCurrentTaxExcludedPrice(BigDecimal currentTaxExcludedPrice) { this.currentTaxExcludedPrice = currentTaxExcludedPrice; }
    public BigDecimal getCurrentTaxIncludedPrice() { return currentTaxIncludedPrice; }
    public void setCurrentTaxIncludedPrice(BigDecimal currentTaxIncludedPrice) { this.currentTaxIncludedPrice = currentTaxIncludedPrice; }
    public String getCurrentSupplierName() { return currentSupplierName; }
    public void setCurrentSupplierName(String currentSupplierName) { this.currentSupplierName = currentSupplierName; }
    public BigDecimal getChangeAmount() { return changeAmount; }
    public void setChangeAmount(BigDecimal changeAmount) { this.changeAmount = changeAmount; }
    public BigDecimal getChangeRate() { return changeRate; }
    public void setChangeRate(BigDecimal changeRate) { this.changeRate = changeRate; }
    public String getExecutionPeriodText() { return executionPeriodText; }
    public void setExecutionPeriodText(String executionPeriodText) { this.executionPeriodText = executionPeriodText; }
    public String getAnnualUsageText() { return annualUsageText; }
    public void setAnnualUsageText(String annualUsageText) { this.annualUsageText = annualUsageText; }
    public String getApplicant() { return applicant; }
    public void setApplicant(String applicant) { this.applicant = applicant; }
    public String getApplyDept() { return applyDept; }
    public void setApplyDept(String applyDept) { this.applyDept = applyDept; }
    public String getMarketSituation() { return marketSituation; }
    public void setMarketSituation(String marketSituation) { this.marketSituation = marketSituation; }
    public String getSimilarCompare() { return similarCompare; }
    public void setSimilarCompare(String similarCompare) { this.similarCompare = similarCompare; }
    public String getApprovalConclusion() { return approvalConclusion; }
    public void setApprovalConclusion(String approvalConclusion) { this.approvalConclusion = approvalConclusion; }
    public String getApprovalType() { return approvalType; }
    public void setApprovalType(String approvalType) { this.approvalType = approvalType; }
    public String getBusinessDivision() { return businessDivision; }
    public void setBusinessDivision(String businessDivision) { this.businessDivision = businessDivision; }
    public LocalDateTime getGeneralManagerApprovedAt() { return generalManagerApprovedAt; }
    public void setGeneralManagerApprovedAt(LocalDateTime generalManagerApprovedAt) { this.generalManagerApprovedAt = generalManagerApprovedAt; }
    public LocalDate getTrackingDate() { return trackingDate; }
    public void setTrackingDate(LocalDate trackingDate) { this.trackingDate = trackingDate; }
    public String getPrintFlag() { return printFlag; }
    public void setPrintFlag(String printFlag) { this.printFlag = printFlag; }
    public String getSettleReferenceHeader() { return settleReferenceHeader; }
    public void setSettleReferenceHeader(String settleReferenceHeader) { this.settleReferenceHeader = settleReferenceHeader; }
    public String getSettleReferenceText() { return settleReferenceText; }
    public void setSettleReferenceText(String settleReferenceText) { this.settleReferenceText = settleReferenceText; }
    public BigDecimal getSettleReferencePrice() { return settleReferencePrice; }
    public void setSettleReferencePrice(BigDecimal settleReferencePrice) { this.settleReferencePrice = settleReferencePrice; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getProcessNo() { return processNo; }
    public void setProcessNo(String processNo) { this.processNo = processNo; }
    public BigDecimal getPlannedPrice() { return plannedPrice; }
    public void setPlannedPrice(BigDecimal plannedPrice) { this.plannedPrice = plannedPrice; }
    public BigDecimal getMarkupRatio() { return markupRatio; }
    public void setMarkupRatio(BigDecimal markupRatio) { this.markupRatio = markupRatio; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public String getPricingMonth() { return pricingMonth; }
    public void setPricingMonth(String pricingMonth) { this.pricingMonth = pricingMonth; }

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

    public BigDecimal getFixedPrice() {
      return fixedPrice;
    }

    public void setFixedPrice(BigDecimal fixedPrice) {
      this.fixedPrice = fixedPrice;
    }

    public Boolean getTaxIncluded() {
      return taxIncluded;
    }

    public void setTaxIncluded(Boolean taxIncluded) {
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

    public BigDecimal getQuota() {
      return quota;
    }

    public void setQuota(BigDecimal quota) {
      this.quota = quota;
    }
  }
}
