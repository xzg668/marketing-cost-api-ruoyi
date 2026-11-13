package com.sanhua.marketingcost.controller;

import static com.sanhua.marketingcost.service.pricing.MaterialPriceSourceCatalog.*;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.pricing.MaterialPriceSourceService;
import com.sanhua.marketingcost.service.pricing.MaterialPriceSourceRefresh;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/material-price-sources")
public class MaterialPriceSourceController {
  private final MaterialPriceSourceService service;
  private final MaterialPriceSourceRefresh refresh;
  public MaterialPriceSourceController(MaterialPriceSourceService service, MaterialPriceSourceRefresh refresh) { this.service = service; this.refresh=refresh; }

  @GetMapping
  @PreAuthorize("@ss.hasPermi('price:material-sources:list')")
  public CommonResult<Page> list(@RequestParam(required=false) String month,
      @RequestParam(required = false) String materialCode, @RequestParam(required = false) String organization,
      @RequestParam(required=false) String materialAttribute,
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(service.list(BusinessUnitContext.getCurrentBusinessUnitType(),
        new Filter(month, materialCode, organization, materialAttribute, page, size)));
  }

  @PatchMapping("/attribute")
  @PreAuthorize("@ss.hasAnyRole('admin','bu_director','finance_reviewer') and @ss.hasPermi('price:material-sources:edit')")
  public CommonResult<Entry> change(@RequestBody Change change, Principal principal) {
    return CommonResult.success(service.change(BusinessUnitContext.getCurrentBusinessUnitType(), change, principal.getName()));
  }

  @PostMapping("/refresh")
  @PreAuthorize("@ss.hasAnyRole('admin','bu_director','finance_reviewer') and @ss.hasPermi('price:material-sources:refresh')")
  public CommonResult<RefreshReport> refresh(Principal principal) {
    return CommonResult.success(refresh.refresh(BusinessUnitContext.getCurrentBusinessUnitType(),principal.getName()));
  }
}
