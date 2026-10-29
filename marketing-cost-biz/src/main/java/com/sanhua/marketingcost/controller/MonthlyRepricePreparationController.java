package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.jjb.JjbVersionAccess;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.reprice.MonthlyRepricePreparationService;
import java.security.Principal;
import java.time.*;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/monthly-reprice/preparation")
public class MonthlyRepricePreparationController {
  public record Request(String oaNo, Long oaFormItemId, Long sourceCostVersionId,
      YearMonth pricingMonth, LocalDateTime priceAsOfTime, String priceOrgCode,
      AnnualUsageInput annualUsage, List<MonthlyPriceRequest> materials) {}

  private final JjbVersionAccess access;
  private final MonthlyRepricePreparationService service;

  public MonthlyRepricePreparationController(JjbVersionAccess access, MonthlyRepricePreparationService service) {
    this.access = access;
    this.service = service;
  }

  /** POST carries bounded structured conditions; it performs only reads. */
  @PostMapping("/preview")
  @PreAuthorize("@ss.hasPermi('price:monthly-material:query') and @ss.hasPermi('ingest:quote:list')")
  public CommonResult<MonthlyRepricePreparationService.Preview> preview(@RequestBody Request request, Principal principal) {
    if (principal == null || request == null || request.pricingMonth() == null || request.priceAsOfTime() == null)
      throw new IllegalArgumentException("登录身份、目标月份和取价时点必填");
    var owned = access.read(request.oaNo(), request.oaFormItemId(), request.sourceCostVersionId());
    if (!List.of("SUCCESS", "CONFIRMED").contains(owned.version().getStatus()))
      throw new IllegalArgumentException("资料检查必须关联实际成功核算的报价版本");
    var context = new MonthlyMaterialPrices.Context(BusinessUnitContext.getCurrentBusinessUnitType(),
        owned.version().getOaNo(), owned.item().getId(), owned.version().getProductCode(),
        request.pricingMonth(), request.priceAsOfTime(), principal.getName(), request.priceOrgCode(), null, request.annualUsage());
    return CommonResult.success(service.preview(context, request.materials()));
  }
}
