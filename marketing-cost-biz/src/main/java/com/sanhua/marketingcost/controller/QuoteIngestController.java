package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.ingest.QuoteBomBatchSyncRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteBomBatchSyncResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusCheckRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportCommitResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteExcelImportPreviewResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestLogDetailResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestLogListItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestResponse;
import com.sanhua.marketingcost.service.ingest.QuoteBomStatusService;
import com.sanhua.marketingcost.service.ingest.QuoteExcelImportService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.ingest.QuoteIngestService;
import com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService;
import java.io.IOException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/quote-ingest")
public class QuoteIngestController {
  private final QuoteIngestService quoteIngestService;
  private final QuoteExcelImportService quoteExcelImportService;
  private final QuoteBomStatusService quoteBomStatusService;
  private final QuoteRequestQueryService quoteRequestQueryService;

  public QuoteIngestController(
      QuoteIngestService quoteIngestService,
      QuoteExcelImportService quoteExcelImportService,
      QuoteBomStatusService quoteBomStatusService,
      QuoteRequestQueryService quoteRequestQueryService) {
    this.quoteIngestService = quoteIngestService;
    this.quoteExcelImportService = quoteExcelImportService;
    this.quoteBomStatusService = quoteBomStatusService;
    this.quoteRequestQueryService = quoteRequestQueryService;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:import','ingest:quote:mock-create')")
  @PostMapping("/requests")
  public CommonResult<QuoteIngestResponse> ingest(@RequestBody QuoteIngestRequest request) {
    try {
      return CommonResult.success(quoteIngestService.ingest(request));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:import')")
  @PostMapping("/excel/preview")
  public CommonResult<QuoteExcelImportPreviewResponse> previewExcel(
      @RequestParam("file") MultipartFile file) {
    try {
      return CommonResult.success(
          quoteExcelImportService.preview(file.getInputStream(), file.getOriginalFilename()));
    } catch (IOException | QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:import')")
  @PostMapping("/excel/commit")
  public CommonResult<QuoteExcelImportCommitResponse> commitExcel(
      @RequestParam("file") MultipartFile file) {
    try {
      return CommonResult.success(
          quoteExcelImportService.commit(file.getInputStream(), file.getOriginalFilename()));
    } catch (IOException | QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:bom-check','ingest:quote:list')")
  @GetMapping("/bom-status")
  public CommonResult<QuoteBomStatusResponse> bomStatus(@RequestParam("oaNo") String oaNo) {
    try {
      return CommonResult.success(quoteBomStatusService.listByOaNo(oaNo));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:bom-check')")
  @PostMapping("/bom-status/check")
  public CommonResult<QuoteBomStatusResponse> checkBomStatus(
      @RequestBody QuoteBomStatusCheckRequest request) {
    try {
      return CommonResult.success(
          quoteBomStatusService.checkByOaNo(request == null ? null : request.getOaNo()));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:bom-check')")
  @PostMapping("/bom-status/batch-sync")
  public CommonResult<QuoteBomBatchSyncResponse> batchSyncBomStatus(
      @RequestBody QuoteBomBatchSyncRequest request) {
    try {
      return CommonResult.success(
          quoteBomStatusService.batchSyncFromU9Source(
              request == null ? null : request.getOaFormItemIds()));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-log:list')")
  @GetMapping("/logs")
  public CommonResult<PageResult<QuoteIngestLogListItemResponse>> logs(
      @RequestParam(value = "pageNo", required = false) Integer pageNo,
      @RequestParam(value = "pageSize", required = false) Integer pageSize,
      @RequestParam(value = "oaNo", required = false) String oaNo,
      @RequestParam(value = "sourceType", required = false) String sourceType,
      @RequestParam(value = "ingestStatus", required = false) String ingestStatus) {
    return CommonResult.success(
        quoteRequestQueryService.pageLogs(pageNo, pageSize, oaNo, sourceType, ingestStatus));
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-log:list','ingest:quote:raw')")
  @GetMapping("/logs/{id}")
  public CommonResult<QuoteIngestLogDetailResponse> logDetail(@PathVariable("id") Long id) {
    try {
      return CommonResult.success(quoteRequestQueryService.getLogDetail(id));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }
}
