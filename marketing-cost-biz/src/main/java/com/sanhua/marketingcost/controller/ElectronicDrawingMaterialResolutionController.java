package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingBomCorrectionRequest;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialResolutionRequest;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialResolutionResponse;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialSearchResponse;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingBomCorrectionService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingMaterialResolutionException;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingMaterialResolutionService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowOrchestrator;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowStage;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 报价核算流程内的电子图库待确认物料接口；不创建独立菜单或上传入口。 */
@RestController
@RequestMapping("/api/v1/quote-requests/electronic-drawing/tasks")
public class ElectronicDrawingMaterialResolutionController {
  private final ElectronicDrawingMaterialResolutionService service;
  private final ElectronicDrawingWorkflowOrchestrator workflowOrchestrator;
  private final ElectronicDrawingBomCorrectionService corrections;
  private final ProductCostingPipeline pipeline;
  private final ElectronicDrawingWorkflowContextPort contexts;

  public ElectronicDrawingMaterialResolutionController(
      ElectronicDrawingMaterialResolutionService service,
      ElectronicDrawingWorkflowOrchestrator workflowOrchestrator,
      ElectronicDrawingBomCorrectionService corrections,
      ProductCostingPipeline pipeline,
      ElectronicDrawingWorkflowContextPort contexts) {
    this.service = service;
    this.workflowOrchestrator = workflowOrchestrator;
    this.corrections = corrections;
    this.pipeline = pipeline;
    this.contexts = contexts;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list','ingest:quote:cost-run:execute')")
  @GetMapping("/{taskId}/material-resolution")
  public CommonResult<ElectronicDrawingMaterialResolutionResponse> state(
      @PathVariable Long taskId, @RequestParam(required = false) String accountingMonth) {
    return execute(() -> service.state(taskId, accountingMonth));
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list','ingest:quote:cost-run:execute')")
  @GetMapping("/{taskId}/material-options")
  public CommonResult<ElectronicDrawingMaterialSearchResponse> search(
      @PathVariable Long taskId,
      @RequestParam Long sourceVersionId,
      @RequestParam String searchType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer limit,
      @RequestParam(required = false) String accountingMonth) {
    return execute(
        () -> service.search(taskId, sourceVersionId, searchType, keyword, limit, accountingMonth));
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:cost-run:execute')")
  @PutMapping("/{taskId}/material-resolutions")
  public CommonResult<ElectronicDrawingMaterialResolutionResponse> apply(
      @PathVariable Long taskId,
      @RequestBody ElectronicDrawingMaterialResolutionRequest request,
      @RequestParam(required = false) String accountingMonth) {
    return execute(
        () -> {
          ElectronicDrawingMaterialResolutionResponse response =
              service.apply(taskId, request, accountingMonth);
          if (!response.complete()) return response;
          continuePreparation(taskId, response.accountingMonth());
          // 料号保存已提交；组树若发现新的资料缺口，在状态中返回，不能伪装成料号保存失败。
          return service.state(taskId, response.accountingMonth());
        });
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:cost-run:execute')")
  @PutMapping("/{taskId}/bom-correction")
  public CommonResult<ElectronicDrawingMaterialResolutionResponse> correctBom(
      @PathVariable Long taskId,
      @RequestBody ElectronicDrawingBomCorrectionRequest request,
      @RequestParam(required = false) String accountingMonth) {
    return execute(
        () -> {
          var result = corrections.save(taskId, request, accountingMonth);
          if (!result.complete()) return result;
          continuePreparation(taskId, result.accountingMonth());
          return service.state(taskId, result.accountingMonth());
        });
  }

  private void continuePreparation(Long taskId, String month) {
    var result = workflowOrchestrator.resumeAfterMaterialSelection(taskId, month);
    if (result.costingCanContinue()
        || ElectronicDrawingWorkflowStage.COMPOSED.equals(result.stage())) {
      // 只接续 BOM/价格/资料检查；不生成成本版本，也不提交 OA。
      var context = contexts.loadForCurrentBusinessUnit(taskId, month);
      pipeline.prepare(
          new ProductCostingRequest(context.oaNo(), taskId, month, "财务BOM修正检查", false));
    }
  }

  private static <T> CommonResult<T> execute(Supplier<T> supplier) {
    try {
      return CommonResult.success(supplier.get());
    } catch (ElectronicDrawingMaterialResolutionException exception) {
      int httpCode =
          switch (exception.code()) {
            case ElectronicDrawingMaterialResolutionException.TASK_NOT_FOUND ->
                GlobalErrorCodeConstants.NOT_FOUND.getCode();
            case ElectronicDrawingMaterialResolutionException.TASK_VERSION_CONFLICT -> 409;
            default -> GlobalErrorCodeConstants.BAD_REQUEST.getCode();
          };
      return CommonResult.error(httpCode, exception.code() + ": " + exception.getMessage());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), exception.getMessage());
    }
  }

  @FunctionalInterface
  private interface Supplier<T> {
    T get();
  }
}
