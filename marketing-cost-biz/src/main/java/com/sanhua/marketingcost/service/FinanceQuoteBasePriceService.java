package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceAdjustRequest;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceInitializeRequest;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceInitializeResponse;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceResponse;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import java.util.List;

/** 财务报价场景专用的 Cu 月度基准维护服务。 */
public interface FinanceQuoteBasePriceService {

  List<FinanceQuoteBasePriceResponse> list(String startMonth, String endMonth);

  /** 核算入口使用的精确月份查询；缺少配置必须阻断，不做任何回退。 */
  FinanceBasePrice getRequired(String pricingMonth);

  FinanceQuoteBasePriceInitializeResponse initialize(
      FinanceQuoteBasePriceInitializeRequest request);

  FinanceQuoteBasePriceResponse adjust(
      Long id, FinanceQuoteBasePriceAdjustRequest request);
}
