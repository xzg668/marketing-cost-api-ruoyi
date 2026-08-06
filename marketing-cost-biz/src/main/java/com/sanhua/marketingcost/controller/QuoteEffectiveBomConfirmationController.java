package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomConfirmResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;
import com.sanhua.marketingcost.service.QuoteEffectiveBomConfirmationService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** QEB-12单产品最终BOM确认和第2步生成API。 */
@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/items/{oaFormItemId}")
public class QuoteEffectiveBomConfirmationController {

  private final QuoteEffectiveBomConfirmationService service;
  private final QuoteEffectiveBomErrorMapper errorMapper;

  public QuoteEffectiveBomConfirmationController(
      QuoteEffectiveBomConfirmationService service,
      QuoteEffectiveBomErrorMapper errorMapper) {
    this.service = service;
    this.errorMapper = errorMapper;
  }

  @PreAuthorize("@ss.hasAnyPermi('quote:costing:bom:confirm')")
  @PostMapping("/effective-bom/prepare-costing")
  public CommonResult<QuoteBomCostingBuildResponse> prepareCostingBom(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId) {
    try {
      return CommonResult.success(service.prepareCostingBom(oaNo, oaFormItemId));
    } catch (RuntimeException exception) {
      return errorMapper.map(exception);
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('quote:costing:bom:confirm')")
  @PostMapping("/effective-bom/confirm")
  public CommonResult<QuoteEffectiveBomConfirmResponse> confirm(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestBody(required = false) QuoteBomConfirmRequest request) {
    try {
      return CommonResult.success(service.confirm(oaNo, oaFormItemId, request));
    } catch (RuntimeException exception) {
      return errorMapper.map(exception);
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('quote:costing:bom:confirm')")
  @PostMapping("/costing-bom/rebuild-from-effective")
  public CommonResult<QuoteBomCostingBuildResponse> rebuildFromEffective(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId) {
    try {
      return CommonResult.success(
          service.rebuildCostingFromEffective(oaNo, oaFormItemId));
    } catch (RuntimeException exception) {
      return errorMapper.map(exception);
    }
  }
}
