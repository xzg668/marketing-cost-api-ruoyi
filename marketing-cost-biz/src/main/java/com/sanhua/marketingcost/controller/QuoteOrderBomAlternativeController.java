package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteOrderBomAlternatives;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionException;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/bom-alternatives")
public class QuoteOrderBomAlternativeController {
  private final QuoteOrderBomAlternatives service;
  private final QuoteBomAlternativeErrorMapper errors;
  public QuoteOrderBomAlternativeController(QuoteOrderBomAlternatives service, QuoteBomAlternativeErrorMapper errors) {
    this.service = service; this.errors = errors;
  }
  @GetMapping
  @PreAuthorize("@ss.hasPermi('ingest:quote:list')")
  public CommonResult<QuoteOrderBomAlternatives.View> list(@PathVariable String oaNo) { return CommonResult.success(service.list(oaNo)); }

  @PostMapping("/prepare")
  @PreAuthorize("@ss.hasPermi('ingest:quote:list') and (@ss.hasAnyRole('admin','bu_director','bu_staff') or @ss.hasPermi('quote:costing:bom:alternative-select') or (@ss.hasRole('finance_reviewer') and @ss.hasPermi('price:material-sources:edit')))")
  public CommonResult<QuoteOrderBomAlternatives.View> prepare(@PathVariable String oaNo) { return CommonResult.success(service.prepare(oaNo)); }

  @PatchMapping
  @PreAuthorize("@ss.hasPermi('ingest:quote:list') and (@ss.hasAnyRole('admin','bu_director','bu_staff') or @ss.hasPermi('quote:costing:bom:alternative-select') or (@ss.hasRole('finance_reviewer') and @ss.hasPermi('price:material-sources:edit')))")
  public CommonResult<QuoteOrderBomAlternatives.Saved> change(@PathVariable String oaNo,
      @RequestBody QuoteOrderBomAlternatives.Change change, Principal actor) {
    try { return CommonResult.success(service.change(oaNo, change, actor.getName())); }
    catch (QuoteBomAlternativeSelectionException | IllegalArgumentException failure) { return errors.map(failure); }
  }
}
