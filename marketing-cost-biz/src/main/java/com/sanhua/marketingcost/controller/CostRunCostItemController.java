package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.service.CostRunCostItemService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成本试算费用项控制器 - 查询试算费用明细
 */
@RestController
@RequestMapping("/api/v1/cost-run")
public class CostRunCostItemController {
  private final CostRunCostItemService costRunCostItemService;

  public CostRunCostItemController(CostRunCostItemService costRunCostItemService) {
    this.costRunCostItemService = costRunCostItemService;
  }

  /** 查询费用项列表 */
  @PreAuthorize("@ss.hasPermi('cost:run:list')")
  @GetMapping("/cost-items")
  public CommonResult<List<CostRunCostItemDto>> list(
      @RequestParam String oaNo, @RequestParam(required = false) String productCode) {
    if (!StringUtils.hasText(oaNo)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"oaNo is required");
    }
    return CommonResult.success(costRunCostItemService.listByOaNo(oaNo, productCode));
  }

  /** 查询费用项结果列表 */
  @PreAuthorize("@ss.hasPermi('cost:run:list')")
  @GetMapping("/cost-items/result")
  public CommonResult<List<CostRunCostItemDto>> listResult(
      @RequestParam String oaNo, @RequestParam String productCode) {
    if (!StringUtils.hasText(oaNo)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"oaNo is required");
    }
    if (!StringUtils.hasText(productCode)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"productCode is required");
    }
    return CommonResult.success(costRunCostItemService.listStoredByOaNo(oaNo, productCode));
  }
}
