package com.sanhua.marketingcost.dto.technicaldata;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 新模块的固定版本内容。来源标识和本次值分开，null 表示尚未填写，不能等同于零。 */
public final class TechnicalDataSupplementContent {
  private TechnicalDataSupplementContent() {}

  public record ProductFees(
      Boolean includesNewToolingMouldCertificationFee,
      BigDecimal unitToolingFee, BigDecimal unitMouldFee,
      BigDecimal unitCertificationFee, String currency) {}

  public record DrawingBom(
      Long sourceVersionId, Long supplementVersionId, String materialMatchStatus,
      List<DrawingNode> nodes,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      DrawingEvidence evidence) {
    public DrawingBom { nodes = immutable(nodes); }
  }

  public record DrawingEvidence(Long oaFormItemId, String accountingMonth, String drawingNo,
      String fileSha256, String requestId, LocalDateTime acquiredAt) {}

  public record DrawingNode(
      String itemKey, String parentItemKey, String sourceNodeId,
      String materialNo, String drawingNo, String name, String specification,
      BigDecimal quantityPerParent, String unit, String matchStatus,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      BigDecimal sourceWeight,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      String sourceWeightUnit,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      String sourceMaterial,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      String sourceRemark) {}

  public record Manufacturing(List<RawMaterial> items,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      ManufacturingEvidence evidence) {
    public Manufacturing { items = immutable(items); }
  }

  public record ManufacturingEvidence(Long oaFormItemId, String accountingMonth,
      Long drawingSourceVersionId, String sourceFingerprint, LocalDateTime checkedAt) {}

  public record ManufacturingNodeEvidence(String parentName, String parentDrawingNo,
      BigDecimal parentQuantity, BigDecimal sourceNetWeight, String sourceNetWeightUnit,
      String rawMaterialName, String rawMaterialSpec, String scrapStatus,
      List<ManufacturingScrap> scrapMappings) {
    public ManufacturingNodeEvidence { scrapMappings = immutable(scrapMappings); }
  }

  public record ManufacturingScrap(String materialNo, String name, String unit) {}

  public record RawMaterial(
      String itemKey, String parentSourceNodeId, Long sourceVersionId,
      String parentMaterialNo, String rawMaterialNo, String rawMaterialDrawingNo,
      BigDecimal netWeightKg, BigDecimal quantityPerParent, String unit,
      String sourceReference,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      BigDecimal grossWeightKg,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      BigDecimal netLengthMm,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      ManufacturingNodeEvidence evidence) {}

  public record Packaging(
      String referenceMaterialNo, String parentMaterialNo,
      BigDecimal sourceParentQuantity, BigDecimal parentQuantity,
      String parentQuantityUnit, String sourceReference,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      String entryMode,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      TechnicalDataPackageReferenceResponse.Evidence source) {}

  public record PackageItemEvidence(String kind, String model, Long parentNodeId, Long sourceNodeId,
      String sourceTopProductCode, String sourceVersion, String sourcePath, String sourceFingerprint,
      BigDecimal sourceQuantity, String sourceUnit) {}

  public record Solder(List<SolderItem> items,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      String entryMode,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      TechnicalDataSolderSource.ReferenceEvidence reference) {
    public Solder { items = immutable(items); }
  }

  public record SolderItem(
      String itemKey, String materialNo, String drawingNo,
      BigDecimal quantityPerProduct, String unit, String sourceReference,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      String name,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      TechnicalDataSolderSource.ItemEvidence evidence) {}

  public record NetLoss(
      String bareMaterialNo, BigDecimal rate, String entryMode, String sourceReference,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      TechnicalDataNetLossReference reference) {}

  public record Prices(List<PriceItem> items) {
    public Prices { items = immutable(items); }
  }

  public record PriceItem(
      String itemKey, String materialNo, String organizationCode,
      String unit, String currency, String entryMode, BigDecimal unitPrice,
      String formula, String referenceMaterialNo, String sourceReference,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      PriceParameters parameters,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      TechnicalDataPriceReference reference,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
      String notes) {}

  public record PriceParameters(BigDecimal blankWeight, BigDecimal netWeight,
      String weightUnit, BigDecimal processFee, BigDecimal agentFee, String feeUnit) {}

  public record SourceFactSnapshot(
      String moduleType, String availability, String reasonCode, String reason,
      String sourceReference, LocalDateTime checkedAt) {}

  public record Dependency(String moduleType, String sourceModuleType, Long sourceVersionId, String contentHash) {}

  public record SourceSnapshot(String productSourceJson, List<SourceFactSnapshot> modules,
      @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
      List<Dependency> dependencies) {
    public SourceSnapshot(String productSourceJson, List<SourceFactSnapshot> modules) { this(productSourceJson, modules, List.of()); }
  }

  public record SupplementSnapshot(
      TechnicalDataSupplementContent.ProductFees productFees,
      TechnicalDataSupplementContent.DrawingBom drawingBom,
      TechnicalDataSupplementContent.Manufacturing manufacturing,
      TechnicalDataSupplementContent.Packaging packaging,
      TechnicalDataSupplementContent.Solder solder,
      TechnicalDataSupplementContent.NetLoss netLoss,
      TechnicalDataSupplementContent.Prices prices,
      SourceSnapshot sources) {}

  private static <T> List<T> immutable(List<T> values) {
    return values == null ? null : List.copyOf(values);
  }
}
