package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.service.QuoteEffectiveBomCostingService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 单产品当前计价 BOM 自动生成 API。 */
@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/items/{oaFormItemId}")
public class QuoteEffectiveBomCostingController {

  private final QuoteEffectiveBomCostingService service;
  private final QuoteEffectiveBomErrorMapper errorMapper;

  public QuoteEffectiveBomCostingController(
      QuoteEffectiveBomCostingService service,
      QuoteEffectiveBomErrorMapper errorMapper) {
    this.service = service;
    this.errorMapper = errorMapper;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @PostMapping("/effective-bom/prepare-costing")
  public CommonResult<QuoteBomCostingBuildResponse> prepareCostingBom(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId) {
    try {
      return CommonResult.success(service.prepareCurrent(oaNo, oaFormItemId));
    } catch (RuntimeException exception) {
      return errorMapper.map(exception);
    }
  }
}
