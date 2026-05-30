package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.service.CostRunEngine;
import com.sanhua.marketingcost.service.CostRunObjectCalcService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class CostRunEngineImpl implements CostRunEngine {

  private final CostRunObjectCalcService costRunObjectCalcService;

  public CostRunEngineImpl(CostRunObjectCalcService costRunObjectCalcService) {
    this.costRunObjectCalcService = costRunObjectCalcService;
  }

  @Override
  public CostRunObjectResult run(CostRunContext context) {
    validate(context);
    return costRunObjectCalcService.calculate(context);
  }

  private void validate(CostRunContext context) {
    if (context == null) {
      throw new IllegalArgumentException("成本核算上下文不能为空");
    }
    if (CostRunContext.SCENE_QUOTE.equals(context.getScene())) {
      validateQuote(context);
      return;
    }
    if (CostRunContext.SCENE_MONTHLY_REPRICE.equals(context.getScene())) {
      validateMonthlyReprice(context);
      return;
    }
    throw new IllegalArgumentException("暂不支持的成本核算场景：" + context.getScene());
  }

  private void validateQuote(CostRunContext context) {
    if (!StringUtils.hasText(context.getOaNo())
        || !StringUtils.hasText(context.getProductCode())) {
      throw new IllegalArgumentException("普通 OA 核算上下文缺少必要字段");
    }
  }

  private void validateMonthlyReprice(CostRunContext context) {
    if (!CostRunContext.SCENE_MONTHLY_REPRICE.equals(context.getScene())) {
      throw new IllegalArgumentException("暂不支持的成本核算场景：" + context.getScene());
    }
    if (!StringUtils.hasText(context.getRepriceNo())
        || !StringUtils.hasText(context.getPricingMonth())
        || !StringUtils.hasText(context.getBusinessUnitType())
        || !StringUtils.hasText(context.getOaNo())
        || !StringUtils.hasText(context.getProductCode())
        || !StringUtils.hasText(context.getCalcObjectKey())) {
      throw new IllegalArgumentException("月度调价核算上下文缺少必要字段");
    }
  }
}
