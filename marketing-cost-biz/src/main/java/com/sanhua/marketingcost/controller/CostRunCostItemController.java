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

  /**
   * 查询费用项结果列表
   *
   * <p>T24：新增可选 category 参数：
   * <ul>
   *   <li>不传 / 传 EXPENSE → 返回传统费用项（旧行为，14 个 cost_code）</li>
   *   <li>传 BOM_BUCKET → 返回见机表汇总行（焊料 / 包装 等）</li>
   * </ul>
   */
  @PreAuthorize("@ss.hasPermi('cost:run:list')")
  @GetMapping("/cost-items/result")
  public CommonResult<List<CostRunCostItemDto>> listResult(
      @RequestParam String oaNo,
      @RequestParam String productCode,
      @RequestParam(required = false) String category) {
    if (!StringUtils.hasText(oaNo)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"oaNo is required");
    }
    if (!StringUtils.hasText(productCode)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"productCode is required");
    }
    // T24：传 category 走 3 参签名（按 EXPENSE/BOM_BUCKET 过滤）；不传走 2 参兼容签名（默认 EXPENSE）
    List<CostRunCostItemDto> data = StringUtils.hasText(category)
        ? costRunCostItemService.listStoredByOaNo(oaNo, productCode, category)
        : costRunCostItemService.listStoredByOaNo(oaNo, productCode);
    return CommonResult.success(data);
  }
}
