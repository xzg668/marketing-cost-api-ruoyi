package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@TableName("lp_price_fixed_item")
public class PriceFixedItem {
  @TableId(type = IdType.AUTO)
  private Long id;
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
  private Integer taxIncluded;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String orderType;
  private BigDecimal quota;

  /** V46 来源类型：PURCHASE 采购件 / MAKE 自制件 / SETTLE 结算价 / SCRAP 废料 */
  private String sourceType;

  /** V46 采购流程编号（PURCHASE 来源专用） */
  private String processNo;

  /** V46 计划价（SETTLE 来源专用；fixed_price = planned × markup） */
  private BigDecimal plannedPrice;

  /** V46 上浮比例（SETTLE 来源专用） */
  private BigDecimal markupRatio;

  /** V46 备注 */
  private String remark;

  /** V46 结算期间 YYYY-MM（按月切版本快照） */
  private String pricingMonth;

  /** V47 SETTLE 来源专用：基准结算价（家用结算价9 C5 = 计划价×上浮比例） */
  private BigDecimal baseSettlePrice;

  /** V47 SETTLE 来源专用：联动结算价（家用结算价9 C6 = 按金属价公式联动算出） */
  private BigDecimal linkedSettlePrice;

  /** 来源系统：OA/SRM/U9/EXCEL/MANUAL，用于固定采购价和结算固定价强隔离 */
  private String sourceSystem;

  /** 来源 Excel sheet 名 */
  private String sourceSheetName;

  /** 来源 Excel 原始行号，便于定位导入问题 */
  private Integer sourceRowNo;

  /** 导入批次号，同一文件多次导入时用于追溯，不作为取价条件 */
  private String sourceBatchNo;

  /** 导入文件名 */
  private String importFileName;

  /** 导入人 */
  private String importedBy;

  /** 导入时间 */
  private LocalDateTime importedAt;

  /** 外部行 id；固定采购价非 U9 行按该字段幂等更新 */
  private String externalRowId;

  /** 固定采购价流程状态 */
  private String processStatus;

  /** SRM 单据编号 */
  private String srmDocNo;

  /** 固定采购价物料类别 */
  private String materialCategory;

  /** 税率 */
  private BigDecimal taxRate;

  /** 原不含税加工费 */
  private BigDecimal originalProcessFee;

  /** 原含税加工费 */
  private BigDecimal originalProcessFeeTaxIncluded;

  /** 原不含税价格 */
  private BigDecimal originalTaxExcludedPrice;

  /** 原含税价格 */
  private BigDecimal originalTaxIncludedPrice;

  /** 原供方名称 */
  private String originalSupplierName;

  /** 现不含税加工费 */
  private BigDecimal currentProcessFee;

  /** 现含税加工费 */
  private BigDecimal currentProcessFeeTaxIncluded;

  /** 现不含税价格；固定采购价主价格来源，对齐 fixed_price */
  private BigDecimal currentTaxExcludedPrice;

  /** 现含税价格 */
  private BigDecimal currentTaxIncludedPrice;

  /** 现供方名称 */
  private String currentSupplierName;

  /** 上涨额 */
  private BigDecimal changeAmount;

  /** 幅度 */
  private BigDecimal changeRate;

  /** 执行日期原始文本 */
  private String executionPeriodText;

  /** 预计年用量原始文本 */
  private String annualUsageText;

  /** 申请人 */
  private String applicant;

  /** 申请部门 */
  private String applyDept;

  /** 市场行情 */
  private String marketSituation;

  /** 类似物比较 */
  private String similarCompare;

  /** 结论 */
  private String approvalConclusion;

  /** 审批表类型 */
  private String approvalType;

  /** 涉及事业部 */
  private String businessDivision;

  /** 总经理批准时间 */
  private LocalDateTime generalManagerApprovedAt;

  /** 板分法跟踪日期 */
  private LocalDate trackingDate;

  /** 是否打印 */
  private String printFlag;

  /** 结算固定价最后一列表头 */
  private String settleReferenceHeader;

  /** 结算固定价最后一列非数字备注，例如“不用提供”；这类行 fixed_price 为空，不参与取价 */
  private String settleReferenceText;

  /** 结算固定价最后一列数字价格，和 fixed_price 保持一致用于追溯 */
  private BigDecimal settleReferencePrice;

  /** V21 业务单元数据隔离：COMMERCIAL / HOUSEHOLD */
  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

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

  public Integer getTaxIncluded() {
    return taxIncluded;
  }

  public void setTaxIncluded(Integer taxIncluded) {
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

  public BigDecimal getBaseSettlePrice() { return baseSettlePrice; }
  public void setBaseSettlePrice(BigDecimal baseSettlePrice) { this.baseSettlePrice = baseSettlePrice; }
  public BigDecimal getLinkedSettlePrice() { return linkedSettlePrice; }
  public void setLinkedSettlePrice(BigDecimal linkedSettlePrice) { this.linkedSettlePrice = linkedSettlePrice; }

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
