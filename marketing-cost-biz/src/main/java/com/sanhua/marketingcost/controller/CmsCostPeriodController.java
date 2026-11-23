package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingResult;
import com.sanhua.marketingcost.service.BusinessUnitRepriceLockGuard;
import com.sanhua.marketingcost.service.ProductCostingPipeline;
import com.sanhua.marketingcost.service.costing.CmsCostPeriodService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/cost-run/detail/{costRunNo}/composition/direct-labor-period",
    "/api/v1/cost-run/detail/{costRunNo}/composition/cms-period"})
@PreAuthorize("@ss.hasPermi('ingest:quote:list') and "
    + "(@ss.hasAnyRole('admin','finance_reviewer') or @ss.hasPermi('cms:cost:effective:refresh'))")
public class CmsCostPeriodController {
  private static final Logger log = LoggerFactory.getLogger(CmsCostPeriodController.class);

  public record Saved(CmsCostPeriodService.State source, boolean changed, ProductCostingResult costing,
      String costingMessage) {}

  private final CmsCostPeriodService service;
  private final ProductCostingPipeline pipeline;
  private final BusinessUnitRepriceLockGuard repriceLock;

  public CmsCostPeriodController(CmsCostPeriodService service, ProductCostingPipeline pipeline,
      BusinessUnitRepriceLockGuard repriceLock) {
    this.service = service;
    this.pipeline = pipeline;
    this.repriceLock = repriceLock;
  }

  @GetMapping
  public CommonResult<CmsCostPeriodService.Selection> select(@PathVariable String costRunNo,
      @RequestParam(required = false) String period,
      @RequestParam(defaultValue = CmsCostPeriodService.DIRECT) String costCode) {
    return CommonResult.success(service.select(costRunNo, costCode, period));
  }

  @PostMapping
  public CommonResult<Saved> save(@PathVariable String costRunNo,
      @RequestBody CmsCostPeriodService.SaveRequest request,
      @RequestParam(defaultValue = CmsCostPeriodService.DIRECT) String costCode, Authentication auth) {
    String operator = auth.getName();
    var change = service.save(costRunNo, costCode, request, operator);
    if (!change.changed()) return CommonResult.success(new Saved(change.source(), false, null, null));
    // A shared source can be corrected even if this quote is submitted or now has unrelated missing inputs.
    try {
      repriceLock.assertCostRunAllowed(change.oaNo());
      var result = pipeline.execute(new ProductCostingRequest(change.oaNo(), change.itemId(),
          change.pricingMonth(), operator, false, true));
      return CommonResult.success(new Saved(change.source(), true, result, result.getMessage()));
    } catch (AccessDeniedException denied) {
      return CommonResult.success(new Saved(change.source(), true, null, "当前账号无本单核算权限"));
    } catch (IllegalArgumentException blocked) {
      return CommonResult.success(new Saved(change.source(), true, null, blocked.getMessage()));
    } catch (RuntimeException failure) {
      log.warn("CMS 期间已保存，本单核算未完成，核算单号={}", costRunNo, failure);
      return CommonResult.success(new Saved(change.source(), true, null, "本单核算未完成，请查看核算状态"));
    }
  }
}
