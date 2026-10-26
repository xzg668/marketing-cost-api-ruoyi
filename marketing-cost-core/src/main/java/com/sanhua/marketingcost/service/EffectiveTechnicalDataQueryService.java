package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;

/** The only costing read port for reviewed quote technical data. */
public interface EffectiveTechnicalDataQueryService {

  /**
   * Returns the exact effective version for the quote product and month, or {@code null} when the
   * product has no technical-data task and the existing CMS/standard-package rules must apply.
   */
  EffectiveTechnicalDataInput resolve(Long oaFormItemId, String accountingMonth);

  /** 准备 BOM/检查缺价时可读本任务已保存明细；此结果不代表可以生成成本版本。 */
  default java.util.List<EffectiveTechnicalDataInput.MaterialLine> preparationMaterials(Long oaFormItemId, String accountingMonth) {
    var input = resolve(oaFormItemId, accountingMonth);
    return input == null ? java.util.List.of() : input.materialItems();
  }

  default String effectiveFingerprint(Long oaFormItemId, String accountingMonth) {
    EffectiveTechnicalDataInput input = resolve(oaFormItemId, accountingMonth);
    return input == null
        ? "NO_EFFECTIVE_TECHNICAL_DATA"
        : input.versionId() + ":" + input.contentFingerprint();
  }

  /** Public material prices have their own adoption revision; product modules remain current inputs. */
  default String effectiveNonPriceFingerprint(Long oaFormItemId, String accountingMonth) {
    var input = resolve(oaFormItemId, accountingMonth);
    if (input == null) return "NO_EFFECTIVE_TECHNICAL_DATA";
    var codec = new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec(
        new com.fasterxml.jackson.databind.ObjectMapper());
    var modules = input.moduleSources().stream().filter(source -> !"PRICE".equals(source.moduleType()))
        .map(source -> java.util.Arrays.asList(source.moduleType(), source.productId(), source.approvedModuleVersionId()))
        .sorted(java.util.Comparator.comparing(Object::toString)).toList();
    // PRICE-only resubmission also changes the composed anchor and whole-version hash. Neither is
    // a non-price input; module approval identities and actual product values provide this boundary.
    return com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec.sha256(codec.unwrap(codec.encode(
        java.util.Arrays.asList(input.accountingMonth(), modules, input.packageRequired(), input.auxiliaryRequired(),
            input.salaryRequired(), input.packageTotalAmount(), input.auxiliaryTotalAmount(), input.salaryTotalAmount(),
            input.packageItems(), input.auxiliaryItems(), input.salaryItems(), input.productFees(), input.netLossRate(),
            input.productProperty(), input.materialItems(), input.plateMaterial()))));
  }
}
