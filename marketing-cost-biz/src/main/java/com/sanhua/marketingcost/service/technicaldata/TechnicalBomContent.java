package com.sanhua.marketingcost.service.technicaldata;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Immutable complete technical BOM; separate from electronic-drawing node/manufacturing content. */
public record TechnicalBomContent(int schemaVersion, String entryMode, Scope scope,
    TechnicalBomRows.Product product, List<Node> nodes, Reference reference, Upload upload,
    String absenceEvidence, LocalDateTime checkedAt) {
  public TechnicalBomContent { nodes = List.copyOf(nodes); }
  /** Observation time is audit evidence, not a changed costing input. */
  public InputBasis inputBasis() {
    return new InputBasis(schemaVersion, entryMode, scope, product, nodes, reference, upload);
  }
  public record InputBasis(int schemaVersion, String entryMode, Scope scope,
      TechnicalBomRows.Product product, List<Node> nodes, Reference reference, Upload upload) {}
  public record Scope(Long oaFormItemId, String oaNo, String accountingMonth, String businessUnit,
      String priceOrgCode, String materialOrgCode, String costingProductCode) {}
  public record Node(TechnicalBomRows.Row input, String resolvedMaterialCode, String categoryCode,
      String categoryName, String costElementCode, String purchasingUnit, BigDecimal quantityPerParent,
      BigDecimal quantityPerTop, ReferenceLine reference) {}
  public record Reference(String productCode, String productName, String model, String specification,
      String materialOrgCode, String priceOrgCode, String purpose, List<String> versions,
      LocalDate effectiveDate, String fingerprint) {
    public Reference { versions = List.copyOf(versions); }
  }
  public record ReferenceLine(Long hierarchyId, Long u9RowId, String sourcePath, String bomVersion,
      BigDecimal sourceChildQuantity, BigDecimal parentBaseQuantity, BigDecimal originalQuantityPerParent,
      String priceOrgCode, String materialOrgCode) {
    public ReferenceLine(Long hierarchyId, Long u9RowId, String sourcePath, String bomVersion,
        BigDecimal sourceChildQuantity, BigDecimal parentBaseQuantity, BigDecimal originalQuantityPerParent) {
      this(hierarchyId, u9RowId, sourcePath, bomVersion, sourceChildQuantity, parentBaseQuantity, originalQuantityPerParent, null, null);
    }
  }
  public record Upload(String fileName, String sha256, long size, String sheetName) {}
}
