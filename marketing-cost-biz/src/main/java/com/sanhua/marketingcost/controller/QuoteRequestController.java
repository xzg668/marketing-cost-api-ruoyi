package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestConfirmClassificationRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestDetailResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestListItemResponse;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingResult;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBatchCostRunRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBatchCostRunResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteProductCostRunRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteProductCostRunTaskResponse;
import com.sanhua.marketingcost.service.BusinessUnitRepriceLockGuard;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import com.sanhua.marketingcost.service.QuoteBatchCostRunService;
import com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService;
import com.sanhua.marketingcost.service.QuoteCostingWorkbenchService;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.QuotePriceTypeRecognitionService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quote-requests")
public class QuoteRequestController {
  private final QuoteRequestQueryService quoteRequestQueryService;
  private final QuoteCostingWorkbenchService quoteCostingWorkbenchService;
  private final QuotePriceTypeRecognitionService quotePriceTypeRecognitionService;
  private final QuotePricePrepareWorkbenchService quotePricePrepareWorkbenchService;
  private final QuoteCostRunWorkbenchService quoteCostRunWorkbenchService;
  private final ProductCostingPipeline productCostingPipeline;
  private final QuoteBatchCostRunService quoteBatchCostRunService;
  private final BusinessUnitRepriceLockGuard repriceLockGuard;

  public QuoteRequestController(
      QuoteRequestQueryService quoteRequestQueryService,
      QuoteCostingWorkbenchService quoteCostingWorkbenchService,
      QuotePriceTypeRecognitionService quotePriceTypeRecognitionService,
      QuotePricePrepareWorkbenchService quotePricePrepareWorkbenchService,
      QuoteCostRunWorkbenchService quoteCostRunWorkbenchService,
      ProductCostingPipeline productCostingPipeline,
      QuoteBatchCostRunService quoteBatchCostRunService,
      BusinessUnitRepriceLockGuard repriceLockGuard) {
    this.quoteRequestQueryService = quoteRequestQueryService;
    this.quoteCostingWorkbenchService = quoteCostingWorkbenchService;
    this.quotePriceTypeRecognitionService = quotePriceTypeRecognitionService;
    this.quotePricePrepareWorkbenchService = quotePricePrepareWorkbenchService;
    this.quoteCostRunWorkbenchService = quoteCostRunWorkbenchService;
    this.productCostingPipeline = productCostingPipeline;
    this.quoteBatchCostRunService = quoteBatchCostRunService;
    this.repriceLockGuard = repriceLockGuard;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:cost-run:execute')")
  @PostMapping("/{oaNo}/cost-runs")
  public CommonResult<QuoteBatchCostRunResponse> submitBatchCostRun(
      @PathVariable("oaNo") String oaNo,
      @RequestBody(required = false) QuoteBatchCostRunRequest request) {
    try {
      return CommonResult.success(
          quoteBatchCostRunService.submit(oaNo, request, currentUsername()));
    } catch (QuoteIngestException | IllegalArgumentException | IllegalStateException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/{oaNo}/cost-runs/current")
  public CommonResult<QuoteBatchCostRunResponse> currentBatchCostRun(
      @PathVariable("oaNo") String oaNo,
      @RequestParam(value = "periodMonth", required = false) String periodMonth) {
    try {
      return CommonResult.success(quoteBatchCostRunService.getCurrent(oaNo, periodMonth));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/{oaNo}/items/{oaFormItemId}/cost-runs/current")
  public CommonResult<QuoteProductCostRunTaskResponse> currentProductCostRunTask(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestParam(value = "periodMonth", required = false) String periodMonth) {
    try {
      return CommonResult.success(
          quoteBatchCostRunService.getCurrentItem(oaNo, oaFormItemId, periodMonth));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping
  public CommonResult<PageResult<QuoteRequestListItemResponse>> page(
      @RequestParam(value = "pageNo", required = false) Integer pageNo,
      @RequestParam(value = "pageSize", required = false) Integer pageSize,
      @RequestParam(value = "oaNo", required = false) String oaNo,
      @RequestParam(value = "processCode", required = false) String processCode,
      @RequestParam(value = "sourceType", required = false) String sourceType,
      @RequestParam(value = "classificationStatus", required = false) String classificationStatus) {
    return CommonResult.success(
        quoteRequestQueryService.pageRequests(
            pageNo, pageSize, oaNo, processCode, sourceType, classificationStatus));
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/{oaNo}/items/{oaFormItemId}/cost-run")
  public CommonResult<QuoteCostRunWorkbenchResponse> costRun(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestParam(value = "periodMonth", required = false) String periodMonth,
      @RequestParam(value = "versionId", required = false) Long versionId) {
    try {
      return CommonResult.success(
          quoteCostRunWorkbenchService.getCostRun(oaNo, oaFormItemId, periodMonth, versionId));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:cost-run:execute')")
  @PostMapping("/{oaNo}/items/{oaFormItemId}/cost-runs")
  public CommonResult<ProductCostingResult> submitProductCostRun(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestBody(required = false) QuoteProductCostRunRequest request) {
    try {
      repriceLockGuard.assertCostRunAllowed(oaNo);
      return CommonResult.success(
          productCostingPipeline.execute(
              new ProductCostingRequest(
                  oaNo,
                  oaFormItemId,
                  request == null ? null : request.getPeriodMonth(),
                  currentUsername(),
                  false)));
    } catch (QuoteIngestException | IllegalArgumentException | IllegalStateException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  private String currentUsername() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    return authentication == null || authentication.getName() == null
        ? "system"
        : authentication.getName();
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/{oaNo}/items/{oaFormItemId}/cost-run/versions/{versionId}/export")
  public void exportCostRunVersion(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @PathVariable("versionId") Long versionId,
      HttpServletResponse response)
      throws IOException {
    String fileName = "cost-run_" + oaNo + "_" + oaFormItemId + "_" + versionId + ".xlsx";
    String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    response.setContentType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
    try {
      quoteCostRunWorkbenchService.exportVersion(
          oaNo, oaFormItemId, versionId, response.getOutputStream());
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      response.reset();
      response.sendError(HttpServletResponse.SC_BAD_REQUEST, ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/{oaNo}")
  public CommonResult<QuoteRequestDetailResponse> detail(@PathVariable("oaNo") String oaNo) {
    try {
      return CommonResult.success(quoteRequestQueryService.getRequestDetail(oaNo));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/{oaNo}/items/{oaFormItemId}/costing-workbench")
  public CommonResult<QuoteCostingWorkbenchResponse> costingWorkbench(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId) {
    try {
      return CommonResult.success(quoteCostingWorkbenchService.getWorkbench(oaNo, oaFormItemId));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/{oaNo}/items/{oaFormItemId}/price-prepare")
  public CommonResult<QuotePricePrepareWorkbenchResponse> pricePrepare(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestParam(value = "periodMonth", required = false) String periodMonth) {
    try {
      return CommonResult.success(
          quotePricePrepareWorkbenchService.getPricePrepare(oaNo, oaFormItemId, periodMonth));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:cost-run:execute')")
  @PostMapping("/{oaNo}/items/{oaFormItemId}/price-prepare/check")
  public CommonResult<QuotePricePrepareWorkbenchResponse> checkPriceSources(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestBody(required = false) QuotePricePrepareGenerateRequest request) {
    try {
      return CommonResult.success(
          quotePricePrepareWorkbenchService.checkPriceSources(oaNo, oaFormItemId, request));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:cost-run:execute')")
  @PostMapping("/{oaNo}/items/{oaFormItemId}/price-prepare/generate")
  public CommonResult<QuotePricePrepareWorkbenchResponse> generatePricePrepare(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestBody(required = false) QuotePricePrepareGenerateRequest request) {
    try {
      return CommonResult.success(
          quotePricePrepareWorkbenchService.generate(oaNo, oaFormItemId, request));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/{oaNo}/items/{oaFormItemId}/price-type-recognition")
  public CommonResult<QuotePriceTypeRecognitionResponse> priceTypeRecognition(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestParam(value = "periodMonth", required = false) String periodMonth) {
    try {
      return CommonResult.success(
          quotePriceTypeRecognitionService.getRecognition(oaNo, oaFormItemId, periodMonth));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:confirm')")
  @PostMapping("/{oaNo}/confirm-classification")
  public CommonResult<QuoteRequestDetailResponse> confirmClassification(
      @PathVariable("oaNo") String oaNo,
      @RequestBody QuoteRequestConfirmClassificationRequest request) {
    try {
      return CommonResult.success(quoteRequestQueryService.confirmClassification(oaNo, request));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }
}
