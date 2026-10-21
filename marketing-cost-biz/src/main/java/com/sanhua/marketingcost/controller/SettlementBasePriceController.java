package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import com.sanhua.marketingcost.service.settlement.SettlementBasePriceService;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/settlement-base-prices")
public class SettlementBasePriceController {
  public record SaveRequest(String priceMonth, BigDecimal copperPrice, BigDecimal zincPrice,
      String unit, Integer expectedRevision, String reason) {}
  private final SettlementBasePriceService service;

  public SettlementBasePriceController(SettlementBasePriceService service) { this.service = service; }

  @GetMapping
  @PreAuthorize("@ss.hasPermi('price:settlement-base:list')")
  public CommonResult<SettlementBasePriceView> effective(@RequestParam String priceMonth) {
    return CommonResult.success(service.effective(scope(priceMonth)).map(SettlementBasePriceView::from).orElse(null));
  }

  @GetMapping("/history")
  @PreAuthorize("@ss.hasPermi('price:settlement-base:list')")
  public CommonResult<List<SettlementBasePriceView>> history(@RequestParam String priceMonth) {
    return CommonResult.success(service.history(scope(priceMonth)).stream().map(SettlementBasePriceView::from).toList());
  }

  @PutMapping
  @PreAuthorize("@ss.hasPermi('price:settlement-base:edit')")
  @OperationLog(module = "结算铜锌基价", operationType = OperationType.UPDATE)
  public CommonResult<SettlementBasePriceView> save(@RequestBody SaveRequest request, Principal principal) {
    if (principal == null) throw new IllegalArgumentException("请登录后维护结算基价");
    if (request == null || request.expectedRevision() == null) throw new IllegalArgumentException("预期修订号必填，首次建立填写 0");
    var change = new SettlementBasePriceService.Change(request.copperPrice(), request.zincPrice(), request.unit(),
        request.expectedRevision(), request.reason());
    return CommonResult.success(SettlementBasePriceView.from(service.save(scope(request.priceMonth()), change, principal.getName())));
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<CommonResult<Void>> conflict(OptimisticLockingFailureException conflict) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(CommonResult.error(409, conflict.getMessage()));
  }

  private SettlementScope scope(String month) {
    return new SettlementScope(BusinessUnitContext.getCurrentBusinessUnitType(), month);
  }
}
