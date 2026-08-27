package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;

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

  /** 型号或图号存在时使用跨报价稳定键；两者都没有才回落到产品行主键。 */
  public static String fromQuoteProduct(OaFormItem item) {
    if (item == null) {
      throw new IllegalArgumentException("报价产品行不能为空");
    }
    String identity = QuoteProductIdentityUtils.resolveCostingCode(item);
    if (!QuoteProductIdentityUtils.hasFormalMaterialNo(item) && identity != null) {
      return identity;
    }
    return fromQuoteItem(item.getId());
  }
}
