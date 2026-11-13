package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryTrialService;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryFailure;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/household-inquiries")
public class HouseholdInquiryTrialController {
  private final HouseholdInquiryTrialService trials;
  public HouseholdInquiryTrialController(HouseholdInquiryTrialService trials) { this.trials = trials; }

  @PostMapping("/{id}/trial")
  @PreAuthorize("@ss.hasPermi('price:household-inquiry:query')")
  public CommonResult<HouseholdInquiryTrialService.Result> trial(@PathVariable long id,
      @RequestBody HouseholdInquiryTrialService.Request request) {
    return CommonResult.success(trials.trial(BusinessUnitContext.getCurrentBusinessUnitType(), id, request));
  }

  @ExceptionHandler(HouseholdInquiryFailure.class)
  public ResponseEntity<CommonResult<Void>> failure(HouseholdInquiryFailure failure) {
    return ResponseEntity.status(failure.status())
        .body(CommonResult.error(failure.status(), failure.code()+": "+failure.getMessage()));
  }
}
