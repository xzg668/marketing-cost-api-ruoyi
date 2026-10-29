package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/monthly-reprice")
public class MonthlyRepriceController {

  // 月度调价会改变批次状态和结果发布口径，操作类接口必须限制为业务总监或明确授权账号。
  static final String OPERATE_PERMISSION =
      "@ss.hasAnyRole('ADMIN','BU_DIRECTOR') or @ss.hasPermi('price:monthly-reprice:operate')";

  @PreAuthorize(OPERATE_PERMISSION)
  @PostMapping("/batches")
  public CommonResult<Void> createBatch(
      @RequestBody Object request, Authentication authentication) {
    return unavailable();
  }

  @PreAuthorize(OPERATE_PERMISSION)
  @PostMapping("/batches/{repriceNo}/expand")
  public CommonResult<Void> expand(
      @PathVariable("repriceNo") String repriceNo, Authentication authentication) {
    return unavailable();
  }

  @PreAuthorize(OPERATE_PERMISSION)
  @PostMapping("/batches/{repriceNo}/prepare-linked-price")
  public CommonResult<Void> prepareLinkedPrice(
      @PathVariable("repriceNo") String repriceNo, Authentication authentication) {
    return unavailable();
  }

  @PreAuthorize(OPERATE_PERMISSION)
  @PostMapping("/batches/{repriceNo}/confirm")
  public CommonResult<Void> confirm(
      @PathVariable("repriceNo") String repriceNo, Authentication authentication) {
    return unavailable();
  }

  @PreAuthorize(OPERATE_PERMISSION)
  @PostMapping("/batches/{repriceNo}/cancel")
  public CommonResult<Void> cancel(
      @PathVariable("repriceNo") String repriceNo, Authentication authentication) {
    return unavailable();
  }

  @PreAuthorize(OPERATE_PERMISSION)
  @PostMapping("/batches/{repriceNo}/retry-failed")
  public CommonResult<Void> retryFailed(
      @PathVariable("repriceNo") String repriceNo, Authentication authentication) {
    return unavailable();
  }

  private <T> CommonResult<T> unavailable() {
    return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
        "旧月度调价操作已停用，历史结果仍可查询");
  }
}
