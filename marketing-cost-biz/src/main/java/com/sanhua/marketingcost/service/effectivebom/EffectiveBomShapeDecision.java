package com.sanhua.marketingcost.service.effectivebom;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeResolution;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeSource;
import com.sanhua.marketingcost.service.materialshape.SupplierRatioResolution;
import java.math.BigDecimal;
import org.springframework.util.StringUtils;

/** 构建器使用的统一形态决定，兼容 QEB-04 和 QEB-05 证据。 */
public record EffectiveBomShapeDecision(
    String materialCode,
    String sourceMaterialShape,
    QuoteMaterialShape effectiveShape,
    MaterialQuoteShapeSource resolutionSource,
    Long shapePolicyId,
    String shapePolicyFingerprint,
    Long selectedSupplierRatioId,
    String selectedSupplierCode,
    String selectedSupplierName,
    BigDecimal selectedSupplyRatio,
    String actionConfigJson,
    String blockingReason) {

  public boolean blocked() {
    return effectiveShape == null || StringUtils.hasText(blockingReason);
  }

  public static EffectiveBomShapeDecision u9(
      String materialCode,
      String sourceShape,
      QuoteMaterialShape effectiveShape) {
    return new EffectiveBomShapeDecision(
        materialCode,
        sourceShape,
        effectiveShape,
        MaterialQuoteShapeSource.U9,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** 顶层产品是原始层级的结构根，不是需要单独取价的 BOM 子件。 */
  public static EffectiveBomShapeDecision structureRoot(String materialCode) {
    return new EffectiveBomShapeDecision(
        materialCode,
        null,
        QuoteMaterialShape.MANUFACTURE,
        MaterialQuoteShapeSource.STRUCTURE_ROOT,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static EffectiveBomShapeDecision fixed(
      String materialCode,
      String sourceShape,
      QuoteMaterialShape effectiveShape,
      Long policyId,
      String policyFingerprint) {
    return new EffectiveBomShapeDecision(
        materialCode,
        sourceShape,
        effectiveShape,
        MaterialQuoteShapeSource.FIXED_POLICY,
        policyId,
        policyFingerprint,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public static EffectiveBomShapeDecision blocked(
      String materialCode, String reason) {
    return new EffectiveBomShapeDecision(
        materialCode,
        null,
        null,
        MaterialQuoteShapeSource.U9,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        reason);
  }

  public static EffectiveBomShapeDecision from(
      MaterialQuoteShapeResolution resolution) {
    return new EffectiveBomShapeDecision(
        resolution.materialCode(),
        resolution.sourceU9Shape(),
        resolution.effectiveShape(),
        resolution.source(),
        resolution.policyId(),
        resolution.policyFingerprint(),
        null,
        null,
        null,
        null,
        resolution.actionConfigJson(),
        resolution.blockingReason());
  }

  public static EffectiveBomShapeDecision from(
      SupplierRatioResolution resolution, String sourceShape) {
    return new EffectiveBomShapeDecision(
        resolution.materialCode(),
        sourceShape,
        resolution.effectiveShape(),
        MaterialQuoteShapeSource.SUPPLIER_RATIO,
        resolution.policyId(),
        resolution.policyFingerprint(),
        resolution.selectedRatioRecordId(),
        resolution.selectedSupplierCode(),
        resolution.selectedSupplierName(),
        resolution.selectedSupplyRatio(),
        resolution.actionConfigJson(),
        resolution.blockingReason());
  }
}
