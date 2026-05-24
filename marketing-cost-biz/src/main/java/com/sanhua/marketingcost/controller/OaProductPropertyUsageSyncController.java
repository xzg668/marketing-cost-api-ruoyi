package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.OaProductPropertyUsageSyncRequest;
import com.sanhua.marketingcost.dto.ProductPropertyAnnualSyncResult;
import com.sanhua.marketingcost.service.ProductPropertyAnnualUsageService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/oa/product-properties")
public class OaProductPropertyUsageSyncController {
  private final ProductPropertyAnnualUsageService productPropertyAnnualUsageService;

  public OaProductPropertyUsageSyncController(
      ProductPropertyAnnualUsageService productPropertyAnnualUsageService) {
    this.productPropertyAnnualUsageService = productPropertyAnnualUsageService;
  }

  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:import','base:product-property:import')")
  @PostMapping("/usage-sync")
  public CommonResult<ProductPropertyAnnualSyncResult> syncUsage(
      @RequestBody OaProductPropertyUsageSyncRequest request) {
    return CommonResult.success(productPropertyAnnualUsageService.syncFromRequest(request));
  }
}
