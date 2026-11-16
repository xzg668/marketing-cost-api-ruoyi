package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.MakePartPriceCalcQueryRequest;
import com.sanhua.marketingcost.service.pricing.MakePartPriceGroups;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/make-part-price-calc")
public class MakePartPriceGroupController {
  private final MakePartPriceGroups groups;
  public MakePartPriceGroupController(MakePartPriceGroups groups) {this.groups=groups;}
  @GetMapping("/group-page")
  @PreAuthorize("@ss.hasPermi('price:make-part-calc:list')")
  public CommonResult<MakePartPriceGroups.Page> page(@ModelAttribute MakePartPriceCalcQueryRequest request) {
    return CommonResult.success(groups.page(request));
  }
  @GetMapping("/groups/{id}")
  @PreAuthorize("@ss.hasPermi('price:make-part-calc:list')")
  public CommonResult<MakePartPriceGroups.Details> details(@PathVariable long id,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
    return CommonResult.success(groups.details(id,page,pageSize));
  }
  @GetMapping("/group-export")
  @PreAuthorize("@ss.hasPermi('price:make-part-calc:export')")
  public void export(@ModelAttribute MakePartPriceCalcQueryRequest request,HttpServletResponse response) throws IOException {
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition","attachment; filename*=UTF-8''"+URLEncoder.encode("制造件价格汇总.xlsx",StandardCharsets.UTF_8));
    groups.export(request,response.getOutputStream());
  }
}
