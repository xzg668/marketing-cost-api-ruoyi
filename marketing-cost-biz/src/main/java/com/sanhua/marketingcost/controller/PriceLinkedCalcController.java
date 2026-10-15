package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceLinkedCalcPageResponse;
import com.sanhua.marketingcost.dto.PriceLinkedCalcTraceResponse;
import com.sanhua.marketingcost.service.PriceLinkedCalcService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 联动结果与计算依据的只读入口。 */
@RestController
@RequestMapping("/api/v1/price-linked")
public class PriceLinkedCalcController {
  private final PriceLinkedCalcService priceLinkedCalcService;

  public PriceLinkedCalcController(PriceLinkedCalcService priceLinkedCalcService) {
    this.priceLinkedCalcService = priceLinkedCalcService;
  }

  /**
   * 查询已落库联动价计算结果。
   *
   * <p>LPE-10：本接口只做追溯查询，不在查询时触发计算或补写 calc_item。
   */
  @PreAuthorize("@ss.hasPermi('price:linked-calc:list')")
  @GetMapping("/calc/results")
  public CommonResult<PriceLinkedCalcPageResponse> results(
      @RequestParam(required = false) String oaNo,
      @RequestParam(required = false) String customer,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(required = false) String itemCode,
      @RequestParam(required = false) String calcScene,
      @RequestParam(required = false) String pricingMonth,
      @RequestParam(required = false) String calcStatus,
      @RequestParam(required = false) String factorSource,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<com.sanhua.marketingcost.dto.PriceLinkedCalcRow> pager =
        priceLinkedCalcService.resultPage(
            oaNo,
            customer,
            businessUnitType,
            itemCode,
            calcScene,
            pricingMonth,
            calcStatus,
            factorSource,
            current,
            size);
    return CommonResult.success(
        new PriceLinkedCalcPageResponse(pager.getTotal(), pager.getRecords()));
  }

  /**
   * 旧公式的依据查看入口；公共原稿使用 original-effective-price 读取已发布依据。
   *
   * <p>返回 {@code {id, traceJson}}；id 不存在返 {@code 404/NOT_FOUND}。 traceJson 里平铺 {@code rawExpr /
   * normalizedExpr / variables / result / error}， 公式语法或变量解析失败会体现在 {@code error} 字段，HTTP 仍为 200。
   */
  @PreAuthorize("@ss.hasPermi('price:linked-calc:list')")
  @GetMapping("/items/{id}/trace")
  public CommonResult<PriceLinkedCalcTraceResponse> trace(@PathVariable("id") Long id) {
    PriceLinkedCalcTraceResponse payload = priceLinkedCalcService.getTrace(id);
    if (payload == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.NOT_FOUND.getCode(), "linked item not found");
    }
    return CommonResult.success(payload);
  }

  /** 读取已保存结果的计算依据，不触发重算。 */
  @PreAuthorize("@ss.hasPermi('price:linked-calc:list')")
  @GetMapping("/calc/{calcId}/trace")
  public CommonResult<PriceLinkedCalcTraceResponse> calcTrace(@PathVariable("calcId") Long calcId) {
    PriceLinkedCalcTraceResponse payload = priceLinkedCalcService.getCalcTrace(calcId);
    if (payload == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.NOT_FOUND.getCode(), "calc item not found");
    }
    return CommonResult.success(payload);
  }
}
