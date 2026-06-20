package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestConfirmClassificationRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestDetailResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestListItemResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomCancelConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingBomRowUpdateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchBomRowResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunTrialRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeAdjustRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationActionResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeImportMissingRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareWorkbenchResponse;
import com.sanhua.marketingcost.service.QuoteBomConfirmationService;
import com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService;
import com.sanhua.marketingcost.service.QuoteCostingWorkbenchService;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.QuotePriceTypeConfirmationService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.prepost.PreAuthorize;
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
  private final QuoteBomConfirmationService quoteBomConfirmationService;
  private final QuotePriceTypeConfirmationService quotePriceTypeConfirmationService;
  private final QuotePricePrepareWorkbenchService quotePricePrepareWorkbenchService;
  private final QuoteCostRunWorkbenchService quoteCostRunWorkbenchService;

  public QuoteRequestController(
      QuoteRequestQueryService quoteRequestQueryService,
      QuoteCostingWorkbenchService quoteCostingWorkbenchService,
      QuoteBomConfirmationService quoteBomConfirmationService,
      QuotePriceTypeConfirmationService quotePriceTypeConfirmationService,
      QuotePricePrepareWorkbenchService quotePricePrepareWorkbenchService,
      QuoteCostRunWorkbenchService quoteCostRunWorkbenchService) {
    this.quoteRequestQueryService = quoteRequestQueryService;
    this.quoteCostingWorkbenchService = quoteCostingWorkbenchService;
    this.quoteBomConfirmationService = quoteBomConfirmationService;
    this.quotePriceTypeConfirmationService = quotePriceTypeConfirmationService;
    this.quotePricePrepareWorkbenchService = quotePricePrepareWorkbenchService;
    this.quoteCostRunWorkbenchService = quoteCostRunWorkbenchService;
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
      @RequestParam(value = "periodMonth", required = false) String periodMonth) {
    try {
      return CommonResult.success(
          quoteCostRunWorkbenchService.getCostRun(oaNo, oaFormItemId, periodMonth));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @PostMapping("/{oaNo}/items/{oaFormItemId}/cost-run/trial")
  public CommonResult<QuoteCostRunWorkbenchResponse> trialCostRun(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestBody(required = false) QuoteCostRunTrialRequest request) {
    try {
      return CommonResult.success(quoteCostRunWorkbenchService.trial(oaNo, oaFormItemId, request));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @PostMapping("/{oaNo}/items/{oaFormItemId}/cost-run/{costRunNo}/confirm")
  public CommonResult<QuoteCostRunSummaryResponse> confirmCostRun(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @PathVariable("costRunNo") String costRunNo,
      @RequestBody(required = false) QuoteCostRunConfirmRequest request) {
    try {
      return CommonResult.success(
          quoteCostRunWorkbenchService.confirm(oaNo, oaFormItemId, costRunNo, request));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/{oaNo}/items/{oaFormItemId}/cost-run/versions/{versionId}/export")
  public void exportCostRunVersion(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @PathVariable("versionId") Long versionId,
      HttpServletResponse response)
      throws IOException {
    String fileName = "cost-run_" + oaNo + "_" + oaFormItemId + "_" + versionId + ".csv";
    String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    response.setContentType("text/csv;charset=UTF-8");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
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
  @PutMapping("/{oaNo}/items/{oaFormItemId}/costing-bom/rows/{rowId}")
  public CommonResult<QuoteCostingWorkbenchBomRowResponse> updateCostingBomRow(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @PathVariable("rowId") Long rowId,
      @RequestBody QuoteCostingBomRowUpdateRequest request) {
    try {
      return CommonResult.success(
          quoteCostingWorkbenchService.updateBomRow(oaNo, oaFormItemId, rowId, request));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @PostMapping("/{oaNo}/items/{oaFormItemId}/costing-bom/confirm")
  public CommonResult<QuoteBomConfirmResponse> confirmCostingBom(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestBody(required = false) QuoteBomConfirmRequest request) {
    try {
      return CommonResult.success(quoteBomConfirmationService.confirm(oaNo, oaFormItemId, request));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @PostMapping("/{oaNo}/items/{oaFormItemId}/costing-bom/cancel-confirm")
  public CommonResult<QuoteBomConfirmResponse> cancelCostingBomConfirm(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestBody(required = false) QuoteBomCancelConfirmRequest request) {
    try {
      return CommonResult.success(
          quoteBomConfirmationService.cancelConfirm(oaNo, oaFormItemId, request));
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

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
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
  @GetMapping("/{oaNo}/items/{oaFormItemId}/price-type-confirmation")
  public CommonResult<QuotePriceTypeConfirmationResponse> priceTypeConfirmation(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestParam(value = "periodMonth", required = false) String periodMonth) {
    try {
      return CommonResult.success(
          quotePriceTypeConfirmationService.getConfirmation(oaNo, oaFormItemId, periodMonth));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @PostMapping("/{oaNo}/items/{oaFormItemId}/price-type-confirmation/import-missing")
  public CommonResult<QuotePriceTypeConfirmationActionResponse> importMissingPriceType(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestBody QuotePriceTypeImportMissingRequest request) {
    try {
      return CommonResult.success(
          quotePriceTypeConfirmationService.importMissing(oaNo, oaFormItemId, request));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @PostMapping("/{oaNo}/items/{oaFormItemId}/price-type-confirmation/adjust")
  public CommonResult<QuotePriceTypeConfirmationActionResponse> adjustPriceType(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestBody QuotePriceTypeAdjustRequest request) {
    try {
      return CommonResult.success(
          quotePriceTypeConfirmationService.adjustPriceType(oaNo, oaFormItemId, request));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @PostMapping("/{oaNo}/items/{oaFormItemId}/price-type-confirmation/confirm")
  public CommonResult<QuotePriceTypeConfirmationActionResponse> confirmPriceType(
      @PathVariable("oaNo") String oaNo,
      @PathVariable("oaFormItemId") Long oaFormItemId,
      @RequestBody(required = false) QuotePriceTypeConfirmRequest request) {
    try {
      return CommonResult.success(
          quotePriceTypeConfirmationService.confirm(oaNo, oaFormItemId, request));
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
