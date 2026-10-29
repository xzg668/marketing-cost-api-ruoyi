package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceWorkflowService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/monthly-reprice/1130")
public class MonthlyRepriceWorkflowController {
  private final MonthlyRepriceWorkflowService service;
  public MonthlyRepriceWorkflowController(MonthlyRepriceWorkflowService service) {this.service=service;}
  @GetMapping("/current")
  @PreAuthorize("@ss.hasAnyRole('ADMIN','BU_DIRECTOR') or @ss.hasAnyPermi('price:monthly-reprice:list','ingest:quote:list')")
  public CommonResult<MonthlyRepriceWorkflowService.View> current() {return CommonResult.success(service.current());}
  @PostMapping("/start") @PreAuthorize("@ss.hasAnyRole('ADMIN','BU_DIRECTOR')")
  public CommonResult<MonthlyRepriceWorkflowService.View> start() {return CommonResult.success(service.start());}
  @PostMapping("/{no}/retry") @PreAuthorize("@ss.hasAnyRole('ADMIN','BU_DIRECTOR')")
  public CommonResult<MonthlyRepriceWorkflowService.View> retry(@PathVariable String no) {return CommonResult.success(service.retry(no));}
  public record Confirmation(long checkedRevision) {}
  @PostMapping("/{no}/confirm") @PreAuthorize("@ss.hasAnyRole('ADMIN','BU_DIRECTOR')")
  public CommonResult<MonthlyRepriceWorkflowService.View> confirm(@PathVariable String no,@RequestBody Confirmation request) {
    return CommonResult.success(service.confirm(no,request.checkedRevision()));
  }
}
