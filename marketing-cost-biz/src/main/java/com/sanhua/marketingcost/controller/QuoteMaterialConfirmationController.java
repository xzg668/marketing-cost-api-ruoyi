package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.quoteconfirmation.QuoteMaterialConfirmationService;
import com.sanhua.marketingcost.service.quoteconfirmation.QuoteMaterialConfirmationService.Request;
import com.sanhua.marketingcost.service.quoteconfirmation.QuoteMaterialConfirmationService.State;
import com.sanhua.marketingcost.service.quoteconfirmation.QuoteMaterialConfirmationService.Outcome;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/material-confirmation")
public class QuoteMaterialConfirmationController {
  private final QuoteMaterialConfirmationService service;
  public QuoteMaterialConfirmationController(QuoteMaterialConfirmationService service) { this.service = service; }

  @GetMapping
  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list','ingest:quote:cost-run:execute')")
  public CommonResult<State> state(@PathVariable String oaNo) {
    return CommonResult.success(service.state(oaNo));
  }

  @PostMapping
  @PreAuthorize("@ss.hasPermi('ingest:quote:cost-run:execute')")
  public CommonResult<Outcome> confirmAndCost(@PathVariable String oaNo, @RequestBody Request request,
      Authentication authentication) {
    try {
      return CommonResult.success(service.confirmAndCost(oaNo, request, authentication.getName()));
    } catch (IllegalArgumentException | IllegalStateException error) {
      return CommonResult.error(400, error.getMessage());
    }
  }
}
