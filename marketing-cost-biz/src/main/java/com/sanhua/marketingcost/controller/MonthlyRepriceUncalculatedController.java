package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.MonthlyRepriceUncalculated.Page;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceUncalculatedQuery;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/monthly-reprice/1130/uncalculated")
public class MonthlyRepriceUncalculatedController {
  private final MonthlyRepriceUncalculatedQuery query;
  public MonthlyRepriceUncalculatedController(MonthlyRepriceUncalculatedQuery query) {this.query=query;}
  @GetMapping
  @PreAuthorize("@ss.hasAnyRole('ADMIN','BU_DIRECTOR') or @ss.hasPermi('price:monthly-reprice:list')")
  public CommonResult<Page> page(@RequestParam(required=false) String month,
      @RequestParam(required=false) String customer,@RequestParam(required=false) String product,
      @RequestParam(required=false) String status,@RequestParam(defaultValue="1") int page,
      @RequestParam(defaultValue="20") int size) {
    return CommonResult.success(query.page(month,customer,product,status,page,size));
  }
}
