package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateResponse;
import com.sanhua.marketingcost.dto.MonthlyRepriceLinkedPricePrepareResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceObjectExpandResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
import com.sanhua.marketingcost.service.MonthlyRepriceConfirmService;
import com.sanhua.marketingcost.service.MonthlyRepriceLinkedPricePrepareService;
import com.sanhua.marketingcost.service.MonthlyRepriceObjectExpandService;
import com.sanhua.marketingcost.service.MonthlyRepriceOperationService;
import com.sanhua.marketingcost.service.MonthlyRepriceStartService;
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

  private final MonthlyRepriceStartService startService;
  private final MonthlyRepriceObjectExpandService objectExpandService;
  private final MonthlyRepriceLinkedPricePrepareService linkedPricePrepareService;
  private final MonthlyRepriceConfirmService confirmService;
  private final MonthlyRepriceOperationService operationService;

  public MonthlyRepriceController(
      MonthlyRepriceStartService startService,
      MonthlyRepriceObjectExpandService objectExpandService,
      MonthlyRepriceLinkedPricePrepareService linkedPricePrepareService,
      MonthlyRepriceConfirmService confirmService,
      MonthlyRepriceOperationService operationService) {
    this.startService = startService;
    this.objectExpandService = objectExpandService;
    this.linkedPricePrepareService = linkedPricePrepareService;
    this.confirmService = confirmService;
    this.operationService = operationService;
  }

  @PreAuthorize(OPERATE_PERMISSION)
  @PostMapping("/batches")
  public CommonResult<MonthlyRepriceBatchCreateResponse> createBatch(
      @RequestBody MonthlyRepriceBatchCreateRequest request, Authentication authentication) {
    return CommonResult.success(startService.start(request, currentUsername(authentication)));
  }

  @PreAuthorize(OPERATE_PERMISSION)
  @PostMapping("/batches/{repriceNo}/expand")
  public CommonResult<MonthlyRepriceObjectExpandResult> expand(
      @PathVariable("repriceNo") String repriceNo, Authentication authentication) {
    return CommonResult.success(objectExpandService.expand(repriceNo, currentUsername(authentication)));
  }

  @PreAuthorize(OPERATE_PERMISSION)
  @PostMapping("/batches/{repriceNo}/prepare-linked-price")
  public CommonResult<MonthlyRepriceLinkedPricePrepareResult> prepareLinkedPrice(
      @PathVariable("repriceNo") String repriceNo, Authentication authentication) {
    return CommonResult.success(
        linkedPricePrepareService.prepare(repriceNo, currentUsername(authentication)));
  }

  @PreAuthorize(OPERATE_PERMISSION)
  @PostMapping("/batches/{repriceNo}/confirm")
  public CommonResult<MonthlyRepriceProgressSnapshot> confirm(
      @PathVariable("repriceNo") String repriceNo, Authentication authentication) {
    return CommonResult.success(confirmService.confirm(repriceNo, currentUsername(authentication)));
  }

  @PreAuthorize(OPERATE_PERMISSION)
  @PostMapping("/batches/{repriceNo}/cancel")
  public CommonResult<MonthlyRepriceProgressSnapshot> cancel(
      @PathVariable("repriceNo") String repriceNo, Authentication authentication) {
    return CommonResult.success(operationService.cancel(repriceNo, currentUsername(authentication)));
  }

  @PreAuthorize(OPERATE_PERMISSION)
  @PostMapping("/batches/{repriceNo}/retry-failed")
  public CommonResult<MonthlyRepriceProgressSnapshot> retryFailed(
      @PathVariable("repriceNo") String repriceNo, Authentication authentication) {
    return CommonResult.success(operationService.retryFailed(repriceNo, currentUsername(authentication)));
  }

  private String currentUsername(Authentication authentication) {
    return authentication == null ? "system" : authentication.getName();
  }
}
