package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.quotefinal.QuoteFinalSubmissionService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/final-submission")
@PreAuthorize("@ss.hasAnyPermi('ingest:quote:cost-run:execute')")
public class QuoteFinalSubmissionController {
  public record Confirmation(String periodMonth, String fingerprint, String requestKey) {}
  private final QuoteFinalSubmissionService service;
  private final TechnicalDataActorProvider actors;
  public QuoteFinalSubmissionController(QuoteFinalSubmissionService service, TechnicalDataActorProvider actors) {
    this.service=service; this.actors=actors;
  }
  @GetMapping
  public CommonResult<QuoteFinalSubmissionService.Status> status(@PathVariable String oaNo,
      @RequestParam(required=false) String periodMonth) {
    return CommonResult.success(service.status(oaNo,periodMonth,actors.current()));
  }
  @PostMapping
  public CommonResult<QuoteFinalSubmissionService.Status> confirm(@PathVariable String oaNo,@RequestBody Confirmation body) {
    return CommonResult.success(service.confirm(oaNo,body.periodMonth(),body.fingerprint(),body.requestKey(),actors.current()));
  }
}
