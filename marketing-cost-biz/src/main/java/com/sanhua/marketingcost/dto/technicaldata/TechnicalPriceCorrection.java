package com.sanhua.marketingcost.dto.technicaldata;

import java.util.List;

/** 当前取价仍未解决的自行公式；批准内容单独保留，不以下载或导入条数判断完成。 */
public record TechnicalPriceCorrection(
    String oaNo,
    Long oaFormItemId,
    String periodMonth,
    String businessUnitType,
    Long technicalVersionId,
    String contentFingerprint,
    boolean canDownload,
    List<Item> items) {
  public record Item(String itemKey, String materialNo, String reasonCode, String message) {}
}
