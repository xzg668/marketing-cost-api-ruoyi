package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.materialshape.QuoteOrderMaterialShapes;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/material-shapes")
public class QuoteOrderMaterialShapeController {
  private final QuoteOrderMaterialShapes service;
  public QuoteOrderMaterialShapeController(QuoteOrderMaterialShapes service) {this.service=service;}
  @GetMapping
  @PreAuthorize("@ss.hasPermi('ingest:quote:list')")
  public CommonResult<QuoteOrderMaterialShapes.View> list(@PathVariable String oaNo) {return CommonResult.success(service.list(oaNo));}
  @PostMapping("/prepare")
  @PreAuthorize("@ss.hasPermi('ingest:quote:list')")
  public CommonResult<QuoteOrderMaterialShapes.View> prepare(@PathVariable String oaNo,Principal actor) {return CommonResult.success(service.prepare(oaNo,actor.getName()));}
  @PatchMapping
  @PreAuthorize("@ss.hasPermi('ingest:quote:list') and @ss.hasAnyRole('admin','bu_director','finance_reviewer') and (@ss.hasPermi('price:material-sources:edit') or @ss.hasPermi('quote:costing:bom:alternative-select'))")
  public CommonResult<QuoteOrderMaterialShapes.Saved> change(@PathVariable String oaNo,@RequestBody QuoteOrderMaterialShapes.Change change,Principal actor) {
    return CommonResult.success(service.change(oaNo,change,actor.getName()));
  }
}
