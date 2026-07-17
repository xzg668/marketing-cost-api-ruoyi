package com.sanhua.marketingcost.service;

import org.springframework.util.StringUtils;

/** 财务报价 Cu 基准在通用财务基价表中的固定身份。 */
public final class FinanceQuoteBasePriceConstants {

  public static final String FACTOR_NAME = "财务报价电解铜基准价";
  public static final String SHORT_NAME = "报价Cu基准";
  public static final String FACTOR_CODE = "Cu";
  public static final String PRICE_SOURCE = "财务报价基准";
  public static final String UNIT = "公斤";
  public static final String LINK_TYPE = "固定";

  private FinanceQuoteBasePriceConstants() {
  }

  /** 普通影响因素维护入口不得接收财务报价基准的专用身份。 */
  public static boolean usesProtectedIdentity(String shortName, String priceSource) {
    return equalsTrimmed(PRICE_SOURCE, priceSource) || equalsTrimmed(SHORT_NAME, shortName);
  }

  private static boolean equalsTrimmed(String expected, String actual) {
    return StringUtils.hasText(actual) && expected.equals(actual.trim());
  }
}
