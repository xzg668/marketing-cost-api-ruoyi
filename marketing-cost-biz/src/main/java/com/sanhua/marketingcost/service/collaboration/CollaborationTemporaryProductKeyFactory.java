package com.sanhua.marketingcost.service.collaboration;

/** 新品尚无正式料号时，使用报价产品行主键形成扫描与发起一致的临时产品键。 */
public final class CollaborationTemporaryProductKeyFactory {

  private static final String PREFIX = "OA_FORM_ITEM:";

  private CollaborationTemporaryProductKeyFactory() {}

  public static String fromQuoteItem(Long oaFormItemId) {
    if (oaFormItemId == null || oaFormItemId <= 0) {
      throw new IllegalArgumentException("报价产品行ID必须为正数");
    }
    return PREFIX + oaFormItemId;
  }
}
