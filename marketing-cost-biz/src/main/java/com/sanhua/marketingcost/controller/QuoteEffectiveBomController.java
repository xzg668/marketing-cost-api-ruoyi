package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomAlternativePreviewRequest;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomResponse;
import com.sanhua.marketingcost.service.QuoteEffectiveBomApplicationService;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomQueryException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 核算工作台第1步的单产品最终有效BOM API。 */
@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/items/{oaFormItemId}/effective-bom")
public class QuoteEffectiveBomController {

  private final QuoteEffectiveBomApplicationService service;
  private final QuoteEffectiveBomErrorMapper errorMapper;

  public QuoteEffectiveBomController(
      QuoteEffectiveBomApplicationService service, QuoteEffectiveBomErrorMapper errorMapper) {
    this.service = service;
    this.errorMapper = errorMapper;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping
  public CommonResult<QuoteEffectiveBomResponse> getEffectiveBom(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId) {
    try {
      return CommonResult.success(service.getEffectiveBom(oaNo, oaFormItemId));
    } catch (QuoteEffectiveBomQueryException | IllegalArgumentException exception) {
      return errorMapper.map(exception);
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('quote:costing:bom:alternative-select')")
  @PostMapping("/rebuild")
  public CommonResult<QuoteEffectiveBomResponse> rebuildPreview(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId) {
    try {
      return CommonResult.success(service.rebuildPreview(oaNo, oaFormItemId));
    } catch (QuoteEffectiveBomQueryException | IllegalArgumentException exception) {
      return errorMapper.map(exception);
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('quote:costing:bom:alternative-select')")
  @PostMapping("/alternative-preview")
  public CommonResult<QuoteEffectiveBomResponse> previewAlternative(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestBody QuoteEffectiveBomAlternativePreviewRequest request) {
    try {
      if (request == null) {
        throw new IllegalArgumentException("预览请求不能为空");
      }
      return CommonResult.success(
          service.previewAlternative(
              oaNo,
              oaFormItemId,
              request.periodMonth(),
              request.alternativeGroupKey(),
              request.selectedMaterialCode()));
    } catch (QuoteEffectiveBomQueryException | IllegalArgumentException exception) {
      return errorMapper.map(exception);
    }
  }
}
