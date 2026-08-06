package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeFeatureStatusResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionHistoryResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionRequest;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSummaryResponse;
import com.sanhua.marketingcost.service.QuoteBomAlternativeApplicationService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeFeatureSwitch;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionException;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 报价物料明细标准/替代选择 API。 */
@RestController
@RequestMapping(
    "/api/v1/quote-requests/{oaNo}/items/{oaFormItemId}"
        + "/costing-bom/alternative-groups")
public class QuoteBomAlternativeController {

  private final QuoteBomAlternativeApplicationService service;
  private final QuoteBomAlternativeErrorMapper errorMapper;
  private final QuoteBomAlternativeFeatureSwitch featureSwitch;

  public QuoteBomAlternativeController(
      QuoteBomAlternativeApplicationService service,
      QuoteBomAlternativeErrorMapper errorMapper,
      QuoteBomAlternativeFeatureSwitch featureSwitch) {
    this.service = service;
    this.errorMapper = errorMapper;
    this.featureSwitch = featureSwitch;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/feature-status")
  public CommonResult<QuoteBomAlternativeFeatureStatusResponse>
      getFeatureStatus() {
    return CommonResult.success(
        new QuoteBomAlternativeFeatureStatusResponse(
            featureSwitch.isEnabled()));
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping
  public CommonResult<QuoteBomAlternativeSummaryResponse>
      getAlternativeGroups(
          @PathVariable("oaNo") String oaNo,
          @PathVariable("oaFormItemId") Long oaFormItemId,
          @RequestParam("periodMonth") String periodMonth) {
    try {
      return CommonResult.success(
          service.getAlternativeGroups(
              oaNo, oaFormItemId, periodMonth));
    } catch (QuoteBomAlternativeSelectionException
        | IllegalArgumentException exception) {
      return errorMapper.map(exception);
    }
  }

  @PreAuthorize(
      "@ss.hasAnyPermi('quote:costing:bom:alternative-select')")
  @PutMapping("/{groupKey}/selection")
  public CommonResult<QuoteBomAlternativeSelectionResponse> saveSelection(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @PathVariable("groupKey") String groupKey,
      @RequestBody QuoteBomAlternativeSelectionRequest request,
      Authentication authentication) {
    try {
      featureSwitch.requireEnabled();
      return CommonResult.success(
          service.saveSelection(
              oaNo,
              oaFormItemId,
              groupKey,
              request,
              currentUsername(authentication)));
    } catch (QuoteBomAlternativeSelectionException
        | IllegalArgumentException exception) {
      return errorMapper.map(exception);
    }
  }

  @PreAuthorize(
      "@ss.hasAnyPermi('quote:costing:bom:alternative-select')")
  @GetMapping("/{groupKey}/history")
  public CommonResult<List<QuoteBomAlternativeSelectionHistoryResponse>>
      getSelectionHistory(
          @PathVariable("oaNo") String oaNo,
          @PathVariable("oaFormItemId") Long oaFormItemId,
          @PathVariable("groupKey") String groupKey,
          @RequestParam("periodMonth") String periodMonth) {
    try {
      return CommonResult.success(
          service.getSelectionHistory(
              oaNo,
              oaFormItemId,
              groupKey,
              periodMonth));
    } catch (QuoteBomAlternativeSelectionException
        | IllegalArgumentException exception) {
      return errorMapper.map(exception);
    }
  }

  private static String currentUsername(Authentication authentication) {
    return authentication == null
            || authentication.getName() == null
            || authentication.getName().isBlank()
        ? "system"
        : authentication.getName().trim();
  }
}
