package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.ingest.QuoteBomBatchSyncRequest;
import com.sanhua.marketingcost.dto.ingest.QuoteBomBatchSyncResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteProductBomBatchOaTaskResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestProductBomListItemResponse;
import com.sanhua.marketingcost.service.ingest.QuoteBomStatusService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.ingest.QuoteRequestProductBomQueryService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quote-request-products/bom")
public class QuoteRequestProductBomController {
  private final QuoteBomStatusService quoteBomStatusService;
  private final QuoteRequestProductBomQueryService quoteRequestProductBomQueryService;

  public QuoteRequestProductBomController(
      QuoteBomStatusService quoteBomStatusService,
      QuoteRequestProductBomQueryService quoteRequestProductBomQueryService) {
    this.quoteBomStatusService = quoteBomStatusService;
    this.quoteRequestProductBomQueryService = quoteRequestProductBomQueryService;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom:list')")
  @GetMapping
  public CommonResult<PageResult<QuoteRequestProductBomListItemResponse>> page(
      @RequestParam(value = "pageNo", required = false) Integer pageNo,
      @RequestParam(value = "pageSize", required = false) Integer pageSize,
      @RequestParam(value = "oaNo", required = false) String oaNo,
      @RequestParam(value = "productCode", required = false) String productCode,
      @RequestParam(value = "customer", required = false) String customer,
      @RequestParam(value = "productType", required = false) String productType,
      @RequestParam(value = "packageMethod", required = false) String packageMethod,
      @RequestParam(value = "businessUnit", required = false) String businessUnit,
      @RequestParam(value = "technicianName", required = false) String technicianName,
      @RequestParam(value = "needTechnicianTask", required = false) Boolean needTechnicianTask,
      @RequestParam(value = "reviewStatus", required = false) String reviewStatus,
      @RequestParam(value = "bomStatuses", required = false) List<String> bomStatuses) {
    return CommonResult.success(
        quoteRequestProductBomQueryService.pageProductBomRows(
            pageNo,
            pageSize,
            oaNo,
            productCode,
            customer,
            productType,
            packageMethod,
            businessUnit,
            technicianName,
            needTechnicianTask,
            reviewStatus,
            bomStatuses));
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom:sync')")
  @PostMapping("/batch-sync")
  public CommonResult<QuoteBomBatchSyncResponse> batchSync(
      @RequestBody QuoteBomBatchSyncRequest request) {
    try {
      return CommonResult.success(
          quoteBomStatusService.batchSyncFromU9Source(
              request == null ? null : request.getOaFormItemIds()));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom:oa-task')")
  @PostMapping("/batch-oa-task")
  public CommonResult<QuoteProductBomBatchOaTaskResponse> batchOaTask(
      @RequestBody QuoteBomBatchSyncRequest request) {
    QuoteProductBomBatchOaTaskResponse response = new QuoteProductBomBatchOaTaskResponse();
    response.setAcceptedCount(0);
    response.setMessage("OA待办推送和BOM补录闭环后续阶段对接");
    return CommonResult.success(response);
  }
}
