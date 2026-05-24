package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBatchQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBulkGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBulkGenerateResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareBulkGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCandidatePageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareCandidateQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGapQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateTarget;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareItemQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareOaSummaryResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryQueryRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryResponse;
import com.sanhua.marketingcost.service.PricePrepareQueryService;
import com.sanhua.marketingcost.service.PricePrepareService;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cost/price-prepare")
public class PricePrepareController {

  private final PricePrepareService pricePrepareService;
  private final PricePrepareQueryService queryService;

  public PricePrepareController(
      PricePrepareService pricePrepareService, PricePrepareQueryService queryService) {
    this.pricePrepareService = pricePrepareService;
    this.queryService = queryService;
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:generate')")
  @PostMapping("/generate")
  public CommonResult<PricePrepareGenerateResult> generate(
      @RequestBody PricePrepareGenerateRequest request) {
    try {
      return CommonResult.success(pricePrepareService.generate(request));
    } catch (IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:generate')")
  @PostMapping("/generate-bulk")
  public CommonResult<PricePrepareBulkGenerateResponse> generateBulk(
      @RequestBody PricePrepareBulkGenerateRequest request) {
    List<GenerateScope> scopes = normalizeGenerateScopes(request);
    if (scopes.isEmpty()) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "oaNos or targets is required");
    }
    PricePrepareBulkGenerateResponse response = new PricePrepareBulkGenerateResponse();
    response.setTotalCount(scopes.size());
    List<PricePrepareBulkGenerateResult> records = new ArrayList<>();
    int successCount = 0;
    int failedCount = 0;
    for (GenerateScope scope : scopes) {
      PricePrepareBulkGenerateResult row = new PricePrepareBulkGenerateResult();
      row.setOaNo(scope.oaNo());
      row.setTopProductCode(scope.singleTopProductCode());
      row.setPeriodMonth(request == null ? null : request.getPeriodMonth());
      try {
        PricePrepareGenerateRequest single = new PricePrepareGenerateRequest();
        single.setOaNo(scope.oaNo());
        single.setTopProductCodes(scope.topProductCodes());
        single.setPeriodMonth(request == null ? null : request.getPeriodMonth());
        single.setBomPurpose(request == null ? null : request.getBomPurpose());
        single.setSourceType(request == null ? null : request.getSourceType());
        PricePrepareGenerateResult generated = pricePrepareService.generate(single);
        fillBulkRow(row, generated);
        fillSummary(row, scope);
        successCount++;
      } catch (RuntimeException ex) {
        row.setStatus("FAILED");
        row.setMessage(ex.getMessage());
        failedCount++;
      }
      records.add(row);
    }
    response.setSuccessCount(successCount);
    response.setFailedCount(failedCount);
    response.setRecords(records);
    return CommonResult.success(response);
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:list')"
      + " and (#request == null or #request.ownerScope == null"
      + " or !#request.ownerScope.equalsIgnoreCase('ALL')"
      + " or @ss.hasPermi('cost:price-prepare:list-all'))")
  @GetMapping("/candidates")
  public CommonResult<PricePrepareCandidatePageResponse> candidates(
      @ModelAttribute PricePrepareCandidateQueryRequest request) {
    return CommonResult.success(queryService.pageCandidates(request));
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:list')")
  @GetMapping("/oa-summary")
  public CommonResult<PricePrepareOaSummaryPageResponse> oaSummary(
      @ModelAttribute PricePrepareOaSummaryQueryRequest request) {
    return CommonResult.success(queryService.pageOaSummaries(request));
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:list')")
  @GetMapping("/top-product-summary")
  public CommonResult<PricePrepareTopProductSummaryPageResponse> topProductSummary(
      @ModelAttribute PricePrepareTopProductSummaryQueryRequest request) {
    return CommonResult.success(queryService.pageTopProductSummaries(request));
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:list')")
  @GetMapping("/batches")
  public CommonResult<PricePrepareBatchPageResponse> batches(
      @ModelAttribute PricePrepareBatchQueryRequest request) {
    return CommonResult.success(queryService.pageBatches(request));
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:detail')")
  @GetMapping("/items")
  public CommonResult<PricePrepareItemPageResponse> items(
      @ModelAttribute PricePrepareItemQueryRequest request) {
    return CommonResult.success(queryService.pageItems(request));
  }

  @PreAuthorize("@ss.hasPermi('cost:price-prepare:gap')")
  @GetMapping("/gaps")
  public CommonResult<PricePrepareGapPageResponse> gaps(
      @ModelAttribute PricePrepareGapQueryRequest request) {
    return CommonResult.success(queryService.pageGaps(request));
  }

  private List<String> normalizeOaNos(PricePrepareBulkGenerateRequest request) {
    if (request == null || request.getOaNos() == null) {
      return List.of();
    }
    LinkedHashSet<String> oaNos = new LinkedHashSet<>();
    for (String oaNo : request.getOaNos()) {
      if (oaNo != null && !oaNo.trim().isEmpty()) {
        oaNos.add(oaNo.trim());
      }
    }
    return List.copyOf(oaNos);
  }

  private List<GenerateScope> normalizeGenerateScopes(PricePrepareBulkGenerateRequest request) {
    List<GenerateScope> targetScopes = normalizeTargetScopes(request);
    if (!targetScopes.isEmpty()) {
      return targetScopes;
    }
    List<GenerateScope> scopes = new ArrayList<>();
    for (String oaNo : normalizeOaNos(request)) {
      scopes.add(new GenerateScope(oaNo, List.of()));
    }
    return scopes;
  }

  private List<GenerateScope> normalizeTargetScopes(PricePrepareBulkGenerateRequest request) {
    if (request == null || request.getTargets() == null || request.getTargets().isEmpty()) {
      return List.of();
    }
    Map<String, LinkedHashSet<String>> topProductsByOa = new LinkedHashMap<>();
    for (PricePrepareGenerateTarget target : request.getTargets()) {
      if (target == null || isBlank(target.getOaNo()) || isBlank(target.getTopProductCode())) {
        continue;
      }
      topProductsByOa
          .computeIfAbsent(target.getOaNo().trim(), ignored -> new LinkedHashSet<>())
          .add(target.getTopProductCode().trim());
    }
    List<GenerateScope> scopes = new ArrayList<>();
    for (Map.Entry<String, LinkedHashSet<String>> entry : topProductsByOa.entrySet()) {
      scopes.add(new GenerateScope(entry.getKey(), List.copyOf(entry.getValue())));
    }
    return scopes;
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private void fillBulkRow(PricePrepareBulkGenerateResult row, PricePrepareGenerateResult generated) {
    row.setPeriodMonth(generated.getPeriodMonth());
    row.setTotalCount(generated.getTotalCount());
    row.setReadyCount(generated.getSuccessCount());
    row.setGapCount(generated.getGapCount());
    row.setStatus(generated.getStatus());
    row.setMessage(generated.getMessage());
  }

  private void fillSummary(PricePrepareBulkGenerateResult row, GenerateScope scope) {
    if (scope.hasSingleTopProduct()) {
      fillTopProductSummary(row, scope.oaNo(), scope.singleTopProductCode());
      return;
    }
    fillOaSummary(row, scope.oaNo());
  }

  private void fillTopProductSummary(
      PricePrepareBulkGenerateResult row, String oaNo, String topProductCode) {
    PricePrepareTopProductSummaryQueryRequest summaryRequest =
        new PricePrepareTopProductSummaryQueryRequest();
    summaryRequest.setOaNo(oaNo);
    summaryRequest.setTopProductCode(topProductCode);
    summaryRequest.setPage(1);
    summaryRequest.setPageSize(1);
    PricePrepareTopProductSummaryPageResponse page =
        queryService.pageTopProductSummaries(summaryRequest);
    if (page == null || page.getRecords() == null || page.getRecords().isEmpty()) {
      return;
    }
    PricePrepareTopProductSummaryResponse summary = page.getRecords().get(0);
    row.setTopProductCode(summary.getTopProductCode());
    row.setTopProductCount(1);
    row.setReadyTopProductCount("READY".equals(summary.getStatus()) ? 1 : 0);
    row.setTotalCount(summary.getTotalCount());
    row.setReadyCount(summary.getReadyCount());
    row.setGapCount(summary.getGapCount());
    row.setStatus(summary.getStatus());
  }

  private void fillOaSummary(PricePrepareBulkGenerateResult row, String oaNo) {
    PricePrepareOaSummaryQueryRequest summaryRequest = new PricePrepareOaSummaryQueryRequest();
    summaryRequest.setOaNo(oaNo);
    summaryRequest.setPage(1);
    summaryRequest.setPageSize(1);
    PricePrepareOaSummaryPageResponse page = queryService.pageOaSummaries(summaryRequest);
    if (page == null || page.getRecords() == null || page.getRecords().isEmpty()) {
      return;
    }
    PricePrepareOaSummaryResponse summary = page.getRecords().get(0);
    row.setTopProductCount(summary.getTopProductCount());
    row.setReadyTopProductCount(summary.getReadyTopProductCount());
    row.setTotalCount(summary.getTotalCount());
    row.setReadyCount(summary.getReadyCount());
    row.setGapCount(summary.getGapCount());
    row.setStatus(summary.getStatus());
  }

  private record GenerateScope(String oaNo, List<String> topProductCodes) {
    private boolean hasSingleTopProduct() {
      return topProductCodes != null && topProductCodes.size() == 1;
    }

    private String singleTopProductCode() {
      return hasSingleTopProduct() ? topProductCodes.get(0) : null;
    }
  }
}
