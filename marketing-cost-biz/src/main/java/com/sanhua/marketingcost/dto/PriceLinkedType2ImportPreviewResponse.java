package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 联动价 Excel 只读预检结果；该对象本身不承载任何数据库实体。 */
public final class PriceLinkedType2ImportPreviewResponse {

  private String fileSha256;
  private String templateType;
  private boolean canConfirm;
  private String detectionMessage;
  private String businessSheetName;
  private String importDataSheetName;
  private int factorRowCount;
  private int businessRowCount;
  private int matchedRowCount;
  private int unmatchedRowCount;
  private int duplicateRowCount;
  private int formulaConvertedCount;
  private int formulaMismatchCount;
  private int taxModeWarningCount;
  private int canonicalFactorReusedCount;
  private int canonicalFactorCreatedCount;
  private int canonicalFactorConflictCount;
  private final List<FactorPreview> factors = new ArrayList<>();
  private final List<RowPreview> rows = new ArrayList<>();
  private final List<PriceItemImportResponse.ErrorRow> errors = new ArrayList<>();

  public String getFileSha256() {
    return fileSha256;
  }

  public void setFileSha256(String fileSha256) {
    this.fileSha256 = fileSha256;
  }

  public String getTemplateType() {
    return templateType;
  }

  public void setTemplateType(String templateType) {
    this.templateType = templateType;
  }

  public boolean isCanConfirm() {
    return canConfirm;
  }

  public void setCanConfirm(boolean canConfirm) {
    this.canConfirm = canConfirm;
  }

  public String getDetectionMessage() {
    return detectionMessage;
  }

  public void setDetectionMessage(String detectionMessage) {
    this.detectionMessage = detectionMessage;
  }

  public String getBusinessSheetName() {
    return businessSheetName;
  }

  public void setBusinessSheetName(String businessSheetName) {
    this.businessSheetName = businessSheetName;
  }

  public String getImportDataSheetName() {
    return importDataSheetName;
  }

  public void setImportDataSheetName(String importDataSheetName) {
    this.importDataSheetName = importDataSheetName;
  }

  public int getFactorRowCount() {
    return factorRowCount;
  }

  public void setFactorRowCount(int factorRowCount) {
    this.factorRowCount = factorRowCount;
  }

  public int getBusinessRowCount() {
    return businessRowCount;
  }

  public void setBusinessRowCount(int businessRowCount) {
    this.businessRowCount = businessRowCount;
  }

  public int getMatchedRowCount() {
    return matchedRowCount;
  }

  public void setMatchedRowCount(int matchedRowCount) {
    this.matchedRowCount = matchedRowCount;
  }

  public int getUnmatchedRowCount() {
    return unmatchedRowCount;
  }

  public void setUnmatchedRowCount(int unmatchedRowCount) {
    this.unmatchedRowCount = unmatchedRowCount;
  }

  public int getDuplicateRowCount() {
    return duplicateRowCount;
  }

  public void setDuplicateRowCount(int duplicateRowCount) {
    this.duplicateRowCount = duplicateRowCount;
  }

  public int getFormulaConvertedCount() {
    return formulaConvertedCount;
  }

  public void setFormulaConvertedCount(int formulaConvertedCount) {
    this.formulaConvertedCount = formulaConvertedCount;
  }

  public int getFormulaMismatchCount() {
    return formulaMismatchCount;
  }

  public void setFormulaMismatchCount(int formulaMismatchCount) {
    this.formulaMismatchCount = formulaMismatchCount;
  }

  public int getTaxModeWarningCount() {
    return taxModeWarningCount;
  }

  public void setTaxModeWarningCount(int taxModeWarningCount) {
    this.taxModeWarningCount = taxModeWarningCount;
  }

  public int getCanonicalFactorReusedCount() {
    return canonicalFactorReusedCount;
  }

  public void setCanonicalFactorReusedCount(int canonicalFactorReusedCount) {
    this.canonicalFactorReusedCount = canonicalFactorReusedCount;
  }

  public int getCanonicalFactorCreatedCount() {
    return canonicalFactorCreatedCount;
  }

  public void setCanonicalFactorCreatedCount(int canonicalFactorCreatedCount) {
    this.canonicalFactorCreatedCount = canonicalFactorCreatedCount;
  }

  public int getCanonicalFactorConflictCount() {
    return canonicalFactorConflictCount;
  }

  public void setCanonicalFactorConflictCount(int canonicalFactorConflictCount) {
    this.canonicalFactorConflictCount = canonicalFactorConflictCount;
  }

  public List<FactorPreview> getFactors() {
    return factors;
  }

  public List<RowPreview> getRows() {
    return rows;
  }

  public List<PriceItemImportResponse.ErrorRow> getErrors() {
    return errors;
  }

  public static final class FactorPreview {

    private String sourceSheetName;
    private Integer sourceRowNumber;
    private String originalName;
    private String shortName;
    private String priceSource;
    private BigDecimal importedPrice;
    private String status;
    private Long factorIdentityId;
    private boolean previewIdentity;
    private String message;

    public String getSourceSheetName() {
      return sourceSheetName;
    }

    public void setSourceSheetName(String sourceSheetName) {
      this.sourceSheetName = sourceSheetName;
    }

    public Integer getSourceRowNumber() {
      return sourceRowNumber;
    }

    public void setSourceRowNumber(Integer sourceRowNumber) {
      this.sourceRowNumber = sourceRowNumber;
    }

    public String getOriginalName() {
      return originalName;
    }

    public void setOriginalName(String originalName) {
      this.originalName = originalName;
    }

    public String getShortName() {
      return shortName;
    }

    public void setShortName(String shortName) {
      this.shortName = shortName;
    }

    public String getPriceSource() {
      return priceSource;
    }

    public void setPriceSource(String priceSource) {
      this.priceSource = priceSource;
    }

    public BigDecimal getImportedPrice() {
      return importedPrice;
    }

    public void setImportedPrice(BigDecimal importedPrice) {
      this.importedPrice = importedPrice;
    }

    public String getStatus() {
      return status;
    }

    public void setStatus(String status) {
      this.status = status;
    }

    public Long getFactorIdentityId() {
      return factorIdentityId;
    }

    public void setFactorIdentityId(Long factorIdentityId) {
      this.factorIdentityId = factorIdentityId;
    }

    public boolean isPreviewIdentity() {
      return previewIdentity;
    }

    public void setPreviewIdentity(boolean previewIdentity) {
      this.previewIdentity = previewIdentity;
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }
  }

  public static final class RowPreview {

    private String sourceSheetName;
    private Integer sourceRowNumber;
    private String materialCode;
    private String supplierName;
    private String supplierCode;
    private String matchStatus;
    private boolean importable;
    private String sourceFormula;
    private String systemFormula;
    private Integer taxIncluded;
    private BigDecimal excelTaxIncludedPrice;
    private BigDecimal systemTaxIncludedPrice;
    private BigDecimal taxIncludedDifference;
    private BigDecimal excelTaxExcludedPrice;
    private BigDecimal systemTaxExcludedPrice;
    private BigDecimal taxExcludedDifference;
    private String message;

    public String getSourceSheetName() {
      return sourceSheetName;
    }

    public void setSourceSheetName(String sourceSheetName) {
      this.sourceSheetName = sourceSheetName;
    }

    public Integer getSourceRowNumber() {
      return sourceRowNumber;
    }

    public void setSourceRowNumber(Integer sourceRowNumber) {
      this.sourceRowNumber = sourceRowNumber;
    }

    public String getMaterialCode() {
      return materialCode;
    }

    public void setMaterialCode(String materialCode) {
      this.materialCode = materialCode;
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

    public String getMatchStatus() {
      return matchStatus;
    }

    public void setMatchStatus(String matchStatus) {
      this.matchStatus = matchStatus;
    }

    public boolean isImportable() {
      return importable;
    }

    public void setImportable(boolean importable) {
      this.importable = importable;
    }

    public String getSourceFormula() {
      return sourceFormula;
    }

    public void setSourceFormula(String sourceFormula) {
      this.sourceFormula = sourceFormula;
    }

    public String getSystemFormula() {
      return systemFormula;
    }

    public void setSystemFormula(String systemFormula) {
      this.systemFormula = systemFormula;
    }

    public Integer getTaxIncluded() {
      return taxIncluded;
    }

    public void setTaxIncluded(Integer taxIncluded) {
      this.taxIncluded = taxIncluded;
    }

    public BigDecimal getExcelTaxIncludedPrice() {
      return excelTaxIncludedPrice;
    }

    public void setExcelTaxIncludedPrice(BigDecimal excelTaxIncludedPrice) {
      this.excelTaxIncludedPrice = excelTaxIncludedPrice;
    }

    public BigDecimal getSystemTaxIncludedPrice() {
      return systemTaxIncludedPrice;
    }

    public void setSystemTaxIncludedPrice(BigDecimal systemTaxIncludedPrice) {
      this.systemTaxIncludedPrice = systemTaxIncludedPrice;
    }

    public BigDecimal getTaxIncludedDifference() {
      return taxIncludedDifference;
    }

    public void setTaxIncludedDifference(BigDecimal taxIncludedDifference) {
      this.taxIncludedDifference = taxIncludedDifference;
    }

    public BigDecimal getExcelTaxExcludedPrice() {
      return excelTaxExcludedPrice;
    }

    public void setExcelTaxExcludedPrice(BigDecimal excelTaxExcludedPrice) {
      this.excelTaxExcludedPrice = excelTaxExcludedPrice;
    }

    public BigDecimal getSystemTaxExcludedPrice() {
      return systemTaxExcludedPrice;
    }

    public void setSystemTaxExcludedPrice(BigDecimal systemTaxExcludedPrice) {
      this.systemTaxExcludedPrice = systemTaxExcludedPrice;
    }

    public BigDecimal getTaxExcludedDifference() {
      return taxExcludedDifference;
    }

    public void setTaxExcludedDifference(BigDecimal taxExcludedDifference) {
      this.taxExcludedDifference = taxExcludedDifference;
    }

    public String getMessage() {
      return message;
    }

    public void setMessage(String message) {
      this.message = message;
    }
  }
}
