package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceAdjustRequest;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceInitializeRequest;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceInitializeResponse;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceResponse;
import com.sanhua.marketingcost.service.FinanceQuoteBasePriceService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 财务报价 Cu 基准独立维护接口，不复用普通影响因素写入口。 */
@RestController
@RequestMapping("/api/v1/finance-quote-base-prices/cu")
public class FinanceQuoteBasePriceController {

  private final FinanceQuoteBasePriceService service;

  public FinanceQuoteBasePriceController(FinanceQuoteBasePriceService service) {
    this.service = service;
  }

  @PreAuthorize("@ss.hasPermi('cost:finance-cu-base:query')")
  @GetMapping
  public CommonResult<List<FinanceQuoteBasePriceResponse>> list(
      @RequestParam(required = false) String startMonth,
      @RequestParam(required = false) String endMonth) {
    return CommonResult.success(service.list(startMonth, endMonth));
  }

  @PreAuthorize("@ss.hasPermi('cost:finance-cu-base:edit')")
  @PostMapping("/initialize")
  public CommonResult<FinanceQuoteBasePriceInitializeResponse> initialize(
      @RequestBody FinanceQuoteBasePriceInitializeRequest request) {
    return CommonResult.success(service.initialize(request));
  }

  @PreAuthorize("@ss.hasPermi('cost:finance-cu-base:edit')")
  @PutMapping("/{id}")
  public CommonResult<FinanceQuoteBasePriceResponse> adjust(
      @PathVariable Long id,
      @RequestBody FinanceQuoteBasePriceAdjustRequest request) {
    return CommonResult.success(service.adjust(id, request));
  }
}
