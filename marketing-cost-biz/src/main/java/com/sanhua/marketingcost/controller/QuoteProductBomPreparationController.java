package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.ingest.QuoteBomBatchSyncRequest;
import com.sanhua.marketingcost.dto.quotebom.OaTodoCallbackRequest;
import com.sanhua.marketingcost.dto.quotebom.OaTodoPushResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationBatchResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTaskCreateRequest;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomTaskCreateResponse;
import com.sanhua.marketingcost.service.OaTodoPushService;
import com.sanhua.marketingcost.service.QuoteBomSupplementCollaborationService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/quote-product-bom-preparation")
public class QuoteProductBomPreparationController {

  private final QuoteBomSupplementCollaborationService collaborationService;
  private final QuoteProductBomPreparationService preparationService;
  private final QuoteProductBomCostingBuildService costingBuildService;
  private final OaTodoPushService oaTodoPushService;

  public QuoteProductBomPreparationController(
      QuoteBomSupplementCollaborationService collaborationService,
      QuoteProductBomPreparationService preparationService,
      QuoteProductBomCostingBuildService costingBuildService,
      OaTodoPushService oaTodoPushService) {
    this.collaborationService = collaborationService;
    this.preparationService = preparationService;
    this.costingBuildService = costingBuildService;
    this.oaTodoPushService = oaTodoPushService;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom-preparation:prepare','ingest:quote-product-bom:sync')")
  @PostMapping("/{oaFormItemId}/prepare")
  public CommonResult<QuoteProductBomPreparationPreview> prepare(
      @PathVariable Long oaFormItemId) {
    try {
      return CommonResult.success(preparationService.prepareByOaFormItem(oaFormItemId));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom-preparation:prepare','ingest:quote-product-bom:sync')")
  @PostMapping("/batch-prepare")
  public CommonResult<QuoteProductBomPreparationBatchResult> batchPrepare(
      @RequestBody QuoteBomBatchSyncRequest request) {
    try {
      return CommonResult.success(
          preparationService.batchPrepare(request == null ? null : request.getOaFormItemIds()));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom-preparation:list','ingest:quote-product-bom:list','ingest:quote-product-bom:oa-task')")
  @GetMapping("/{oaFormItemId}/preview")
  public CommonResult<QuoteProductBomPreparationPreview> preview(
      @PathVariable Long oaFormItemId) {
    try {
      return CommonResult.success(preparationService.getPreparationPreview(oaFormItemId));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom:oa-task')")
  @PostMapping("/tasks")
  public CommonResult<QuoteProductBomTaskCreateResponse> createTasks(
      @RequestBody QuoteProductBomTaskCreateRequest request) {
    try {
      return CommonResult.success(collaborationService.createTasks(request));
    } catch (QuoteIngestException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom:oa-task')")
  @PostMapping("/tasks/{taskId}/oa-todo/push")
  public CommonResult<OaTodoPushResponse> pushOaTodo(@PathVariable Long taskId) {
    try {
      return CommonResult.success(oaTodoPushService.pushBomSupplementTask(taskId));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom:list','ingest:quote-product-bom:oa-task')")
  @GetMapping("/tasks/{taskId}/oa-todo/status")
  public CommonResult<OaTodoPushResponse> queryOaTodoStatus(@PathVariable Long taskId) {
    try {
      return CommonResult.success(oaTodoPushService.queryTodoStatus(taskId));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom:oa-task')")
  @PostMapping("/tasks/{taskId}/oa-todo/close")
  public CommonResult<OaTodoPushResponse> closeOaTodo(@PathVariable Long taskId) {
    try {
      return CommonResult.success(oaTodoPushService.closeTodo(taskId));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PostMapping("/oa-todo/callback")
  public CommonResult<OaTodoPushResponse> oaTodoCallback(
      @RequestBody OaTodoCallbackRequest request) {
    try {
      return CommonResult.success(oaTodoPushService.handleCallback(request));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote-product-bom-preparation:build-costing','ingest:quote-product-bom:oa-task')")
  @PostMapping("/{oaFormItemId}/build-costing-rows")
  public CommonResult<QuoteBomCostingBuildResponse> buildCostingRows(
      @PathVariable Long oaFormItemId) {
    try {
      return CommonResult.success(costingBuildService.buildByOaFormItem(oaFormItemId));
    } catch (QuoteIngestException | IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }
}
