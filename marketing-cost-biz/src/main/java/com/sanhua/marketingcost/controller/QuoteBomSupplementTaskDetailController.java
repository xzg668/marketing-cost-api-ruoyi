package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskDetailResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskQueryRequest;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskQueryResponse;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskReviewRequest;
import com.sanhua.marketingcost.dto.quotebom.BomSupplementTaskReviewResponse;
import com.sanhua.marketingcost.service.QuoteBomSupplementCollaborationService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/bom-supplement/tasks")
public class QuoteBomSupplementTaskDetailController {

  private final QuoteBomSupplementCollaborationService collaborationService;
  private final QuoteProductBomCostingBuildService costingBuildService;

  public QuoteBomSupplementTaskDetailController(
      QuoteBomSupplementCollaborationService collaborationService,
      QuoteProductBomCostingBuildService costingBuildService) {
    this.collaborationService = collaborationService;
    this.costingBuildService = costingBuildService;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom:oa-task','ingest:quote-product-bom:list')")
  @GetMapping
  public CommonResult<BomSupplementTaskQueryResponse> listTasks(
      @RequestParam(required = false) String taskNo,
      @RequestParam(required = false) String oaNo,
      @RequestParam(required = false) String productCode,
      @RequestParam(required = false) String taskStatus,
      @RequestParam(required = false) String reviewStatus,
      @RequestParam(required = false) Integer pageNo,
      @RequestParam(required = false) Integer pageSize) {
    try {
      return CommonResult.success(
          collaborationService.listTasks(
              new BomSupplementTaskQueryRequest(
                  taskNo, oaNo, productCode, taskStatus, reviewStatus, pageNo, pageSize)));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom:oa-task','ingest:quote-product-bom:list')")
  @GetMapping("/{taskId}")
  public CommonResult<BomSupplementTaskDetailResponse> getTask(@PathVariable Long taskId) {
    try {
      return CommonResult.success(collaborationService.getTaskDetail(taskId));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom:oa-task','ingest:quote-product-bom:list')")
  @PostMapping("/{taskId}/review")
  public CommonResult<BomSupplementTaskReviewResponse> review(
      @PathVariable Long taskId, @RequestBody(required = false) BomSupplementTaskReviewRequest request) {
    try {
      return CommonResult.success(collaborationService.review(taskId, request));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom:oa-task','ingest:quote-product-bom:list')")
  @PostMapping("/{taskId}/return")
  public CommonResult<BomSupplementTaskReviewResponse> returnForRevision(
      @PathVariable Long taskId, @RequestBody(required = false) BomSupplementTaskReviewRequest request) {
    try {
      return CommonResult.success(collaborationService.returnForRevision(taskId, request));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom-preparation:build-costing','ingest:quote-product-bom:oa-task','ingest:quote-product-bom:list')")
  @PostMapping("/{taskId}/build-costing-rows")
  public CommonResult<QuoteBomCostingBuildResponse> buildCostingRows(@PathVariable Long taskId) {
    try {
      return CommonResult.success(costingBuildService.buildByTask(taskId));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }
}
