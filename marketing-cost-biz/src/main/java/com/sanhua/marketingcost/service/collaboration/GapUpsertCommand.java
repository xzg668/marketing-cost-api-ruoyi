package com.sanhua.marketingcost.service.collaboration;

import java.math.BigDecimal;

public record GapUpsertCommand(
    String gapCategory,
    String gapType,
    String sourceType,
    Long sourceId,
    String gapFingerprint,
    String bomNodeKey,
    String bomPath,
    String materialCode,
    String materialName,
    String materialSpec,
    String materialModel,
    String materialRole,
    String suggestedPriceType,
    String reasonCode,
    String reasonMessage,
    BigDecimal bomQuantity,
    String bomUnit,
    String accountingMonth,
    String applicableOrgCode) {

  public GapUpsertCommand(
      String gapCategory,
      String gapType,
      String sourceType,
      Long sourceId,
      String gapFingerprint,
      String bomNodeKey,
      String bomPath,
      String materialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String materialRole,
      String suggestedPriceType,
      String reasonCode,
      String reasonMessage) {
    this(gapCategory, gapType, sourceType, sourceId, gapFingerprint, bomNodeKey, bomPath,
        materialCode, materialName, materialSpec, materialModel, materialRole,
        suggestedPriceType, reasonCode, reasonMessage, null, null, null, null);
  }

  public GapUpsertCommand {
    gapCategory = CollaborationScope.requireText(gapCategory, "缺口分类");
    gapType = CollaborationScope.requireText(gapType, "缺口类型");
    gapFingerprint = CollaborationScope.requireText(gapFingerprint, "缺口指纹");
    reasonCode = CollaborationScope.requireText(reasonCode, "缺口原因码");
    reasonMessage = CollaborationScope.requireText(reasonMessage, "缺口原因");
  }
}
