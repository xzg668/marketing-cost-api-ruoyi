package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialResolutionRequest;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialResolutionResponse;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialSearchResponse;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingMaterialResolutionException;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingMaterialResolutionService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowOrchestrator;
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

  public ElectronicDrawingMaterialResolutionController(
      ElectronicDrawingMaterialResolutionService service,
      ElectronicDrawingWorkflowOrchestrator workflowOrchestrator) {
    this.service = service;
    this.workflowOrchestrator = workflowOrchestrator;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/{taskId}/material-resolution")
  public CommonResult<ElectronicDrawingMaterialResolutionResponse> state(
      @PathVariable Long taskId) {
    return execute(() -> service.state(taskId));
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  @GetMapping("/{taskId}/material-options")
  public CommonResult<ElectronicDrawingMaterialSearchResponse> search(
      @PathVariable Long taskId,
      @RequestParam Long sourceVersionId,
      @RequestParam String searchType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer limit) {
    return execute(() -> service.search(
        taskId, sourceVersionId, searchType, keyword, limit));
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:cost-run:execute')")
  @PutMapping("/{taskId}/material-resolutions")
  public CommonResult<ElectronicDrawingMaterialResolutionResponse> apply(
      @PathVariable Long taskId,
      @RequestBody ElectronicDrawingMaterialResolutionRequest request) {
    return execute(() -> {
      ElectronicDrawingMaterialResolutionResponse response = service.apply(taskId, request);
      if (!response.complete()) return response;
      workflowOrchestrator.resumeAfterMaterialSelection(taskId);
      return service.state(taskId);
    });
  }

  private static <T> CommonResult<T> execute(Supplier<T> supplier) {
    try {
      return CommonResult.success(supplier.get());
    } catch (ElectronicDrawingMaterialResolutionException exception) {
      int httpCode = switch (exception.code()) {
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
