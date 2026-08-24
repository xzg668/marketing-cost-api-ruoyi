package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult.PriceGap;
import org.springframework.util.StringUtils;

/** 将所有现有取价来源规范化为稳定、可追溯的协作价格缺口。 */
public final class CollaborationPriceGapCommandFactory {

  private CollaborationPriceGapCommandFactory() {
  }

  public static GapUpsertCommand create(
      Long oaFormItemId, String productCode, PriceGap gap) {
    return create(oaFormItemId, productCode, gap == null ? null : gap.accountingMonth(), gap);
  }

  public static GapUpsertCommand create(
      Long oaFormItemId, String productCode, String accountingMonth, PriceGap gap) {
    if (gap == null || !StringUtils.hasText(gap.materialCode())) {
      throw new IllegalArgumentException("真实缺价缺少底层物料料号");
    }
    String type = firstText(gap.gapType(), "MISSING_PRICE");
    String reason = firstText(gap.reason(), "当前报价条件下无法取得有效价格");
    String sourceType = firstText(gap.sourceType(), "PRICE_PREPARE");
    String positionIdentity = StringUtils.hasText(gap.bomPath())
        ? gap.bomPath().trim()
        : StringUtils.hasText(gap.bomNodeKey())
            ? gap.bomNodeKey().trim()
            : text(gap.sourceId());
    String sourceFingerprint = String.join("|",
        firstText(productCode, "TEMP"), sourceType, text(gap.sourceTable()),
        firstText(gap.materialRole(), "NORMAL"), text(gap.applicableOrgCode()));
    String fingerprint = CollaborationGapFingerprintFactory.create(
        oaFormItemId, accountingMonth, "PRICE", type,
        gap.materialCode(), positionIdentity, sourceFingerprint);
    return new GapUpsertCommand(
        "PRICE", type, sourceType, gap.sourceId(), fingerprint,
        gap.bomNodeKey(), gap.bomPath(), gap.materialCode().trim(), gap.materialName(),
        gap.materialSpec(), gap.materialModel(), firstText(gap.materialRole(), "NORMAL"),
        gap.existingOfficialPriceType(), type, reason, gap.bomQuantity(), gap.bomUnit(),
        accountingMonth, gap.applicableOrgCode());
  }

  private static String firstText(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  private static String text(Object value) {
    return value == null ? "" : value.toString().trim();
  }
}
