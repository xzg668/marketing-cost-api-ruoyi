package com.sanhua.marketingcost.dto;

/** 类型 2 公式版本及导入依据写入结果。 */
public record PriceLinkedImportBasisSaveResult(
    String action,
    Long linkedItemId,
    Long previousVersionId,
    int factorBindingCount) {

  public static final String ACTION_CREATED = "CREATED_NEW_VERSION";
  public static final String ACTION_DUPLICATE_SKIPPED = "DUPLICATE_SKIPPED";
}
