package com.sanhua.marketingcost.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Immutable, reviewed technical-data input selected for one costing object and month. */
public record EffectiveTechnicalDataInput(
    Long productId,
    Long versionId,
    Integer versionNo,
    String accountingMonth,
    String sourceType,
    String contentFingerprint,
    LocalDateTime retrievedAt,
    boolean packageRequired,
    boolean auxiliaryRequired,
    boolean salaryRequired,
    BigDecimal packageTotalAmount,
    BigDecimal auxiliaryTotalAmount,
    BigDecimal salaryTotalAmount,
    List<PackageLine> packageItems,
    List<AuxiliaryLine> auxiliaryItems,
    List<SalaryLine> salaryItems,
    ProductFees productFees,
    BigDecimal netLossRate,
    String productProperty,
    List<ModuleSource> moduleSources,
    List<MaterialLine> materialItems,
    List<PriceSource> priceSources) {

  public static final String SOURCE_EFFECTIVE_VERSION = "QUOTE_TECH_EFFECTIVE_VERSION";

  public EffectiveTechnicalDataInput {
    packageItems = packageItems == null ? List.of() : List.copyOf(packageItems);
    auxiliaryItems = auxiliaryItems == null ? List.of() : List.copyOf(auxiliaryItems);
    salaryItems = salaryItems == null ? List.of() : List.copyOf(salaryItems);
    moduleSources = moduleSources == null ? List.of() : List.copyOf(moduleSources);
    materialItems = materialItems == null ? List.of() : List.copyOf(materialItems);
    priceSources = priceSources == null ? List.of() : List.copyOf(priceSources);
  }

  /** 旧四模块有效版本仍可读取；新九模块调用完整构造器，记录各模块实际来源。 */
  public EffectiveTechnicalDataInput(Long productId, Long versionId, Integer versionNo,
      String accountingMonth, String sourceType, String contentFingerprint, LocalDateTime retrievedAt,
      boolean packageRequired, boolean auxiliaryRequired, boolean salaryRequired,
      BigDecimal packageTotalAmount, BigDecimal auxiliaryTotalAmount, BigDecimal salaryTotalAmount,
      List<PackageLine> packageItems, List<AuxiliaryLine> auxiliaryItems, List<SalaryLine> salaryItems,
      ProductFees productFees, BigDecimal netLossRate) {
    this(productId,versionId,versionNo,accountingMonth,sourceType,contentFingerprint,retrievedAt,
        packageRequired,auxiliaryRequired,salaryRequired,packageTotalAmount,auxiliaryTotalAmount,salaryTotalAmount,
        packageItems,auxiliaryItems,salaryItems,productFees,netLossRate,null,List.of(),List.of());
  }

  public EffectiveTechnicalDataInput(Long productId, Long versionId, Integer versionNo,
      String accountingMonth, String sourceType, String contentFingerprint, LocalDateTime retrievedAt,
      boolean packageRequired, boolean auxiliaryRequired, boolean salaryRequired,
      BigDecimal packageTotalAmount, BigDecimal auxiliaryTotalAmount, BigDecimal salaryTotalAmount,
      List<PackageLine> packageItems, List<AuxiliaryLine> auxiliaryItems, List<SalaryLine> salaryItems,
      ProductFees productFees, BigDecimal netLossRate, String productProperty,
      List<ModuleSource> moduleSources, List<MaterialLine> materialItems) {
    this(productId, versionId, versionNo, accountingMonth, sourceType, contentFingerprint, retrievedAt,
        packageRequired, auxiliaryRequired, salaryRequired, packageTotalAmount, auxiliaryTotalAmount,
        salaryTotalAmount, packageItems, auxiliaryItems, salaryItems, productFees, netLossRate,
        productProperty, moduleSources, materialItems, List.of());
  }

  public record PriceSource(String materialNo, String priceType, Long priceRecordId,
      Long productId, Long versionId, Long approvedModuleVersionId, String contentFingerprint) {}

  /** 汇总版本仅作旧数据回退；共享模块记录其真正的批准来源。 */
  public Long sourceVersionId(String moduleType) {
    return moduleSources.stream().filter(source -> moduleType.equals(source.moduleType()))
        .map(ModuleSource::versionId).findFirst().orElse(versionId);
  }

  public record ModuleSource(String moduleType, Long productId, Long versionId,
      Long approvedModuleVersionId, String contentFingerprint) {}

  /** 待进入正常 BOM/部品计价的数量，不在资料读取阶段复制参考单价或预算金额。 */
  public record MaterialLine(String moduleType, String itemKey, String materialNo, String name,
      BigDecimal quantityPerProduct, String unit, Long versionId) {}

  /** 已核对的三项单件金额；0 表示明确无费用，不按年用量再分摊。 */
  public record ProductFees(
      boolean hasAdditionalFees,
      BigDecimal unitToolingFee,
      BigDecimal unitMouldFee,
      BigDecimal unitCertificationFee,
      String currency) {}

  public record PackageLine(
      Long id,
      Integer lineNo,
      String materialNo,
      String name,
      BigDecimal quantity,
      String unit,
      BigDecimal unitPrice,
      BigDecimal amount) {}

  public record AuxiliaryLine(
      Long id,
      Integer lineNo,
      String subjectCode,
      String subjectName,
      String name,
      BigDecimal standardQuantity,
      String standardUnit,
      BigDecimal unitPrice,
      BigDecimal lossRate,
      BigDecimal amount) {}

  public record SalaryLine(
      Long id,
      Integer lineNo,
      String laborType,
      BigDecimal amount) {}
}
