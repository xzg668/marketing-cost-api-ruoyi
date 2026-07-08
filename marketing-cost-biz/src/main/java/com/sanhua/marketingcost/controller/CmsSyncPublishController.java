package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.CmsSyncPublishRunResponse;
import com.sanhua.marketingcost.service.CmsSyncPublishService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/cms-sync-publish")
public class CmsSyncPublishController {

  private final CmsSyncPublishService cmsSyncPublishService;

  public CmsSyncPublishController(CmsSyncPublishService cmsSyncPublishService) {
    this.cmsSyncPublishService = cmsSyncPublishService;
  }

  @PreAuthorize("@ss.hasPermi('cms:cost:effective:refresh')")
  @PostMapping("/run-ready")
  public CommonResult<CmsSyncPublishRunResponse> runReady() {
    return CommonResult.success(cmsSyncPublishService.runNextReady());
  }
}
