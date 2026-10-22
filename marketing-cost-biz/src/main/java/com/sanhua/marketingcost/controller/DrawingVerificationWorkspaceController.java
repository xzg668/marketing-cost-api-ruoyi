package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.electronicdrawing.DrawingVerificationWorkspace;
import com.sanhua.marketingcost.service.electronicdrawing.DrawingVerificationWorkspaceService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceImportException;
import java.util.function.Supplier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 登录报价员入口；外部人工确认仍只使用 integration/drawing 的接收接口。 */
@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/items/{itemId}/drawing-verifications")
public class DrawingVerificationWorkspaceController {
  private final DrawingVerificationWorkspaceService service;

  public DrawingVerificationWorkspaceController(DrawingVerificationWorkspaceService service) {
    this.service = service;
  }

  @GetMapping
  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list','ingest:quote:cost-run:execute')")
  public CommonResult<DrawingVerificationWorkspace> state(
      @PathVariable String oaNo, @PathVariable Long itemId, @RequestParam String accountingMonth) {
    return execute(() -> service.state(oaNo, itemId, accountingMonth));
  }

  @GetMapping("/results/{resultId}")
  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list','ingest:quote:cost-run:execute')")
  public CommonResult<DrawingVerificationWorkspace.Detail> detail(
      @PathVariable String oaNo,
      @PathVariable Long itemId,
      @PathVariable Long resultId,
      @RequestParam String accountingMonth) {
    return execute(() -> service.detail(oaNo, itemId, accountingMonth, resultId));
  }

  @PostMapping
  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:cost-run:execute')")
  public CommonResult<DrawingVerificationWorkspace> operate(
      @PathVariable String oaNo,
      @PathVariable Long itemId,
      @RequestParam String accountingMonth,
      @RequestBody DrawingVerificationWorkspaceService.Operation request) {
    return execute(() -> service.operate(oaNo, itemId, accountingMonth, request));
  }

  private <T> CommonResult<T> execute(Supplier<T> work) {
    try {
      return CommonResult.success(work.get());
    } catch (IllegalArgumentException
        | IllegalStateException
        | ElectronicDrawingSourceImportException invalid) {
      return CommonResult.error(400, invalid.getMessage());
    }
  }
}
