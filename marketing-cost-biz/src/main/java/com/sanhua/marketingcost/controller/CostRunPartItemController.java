package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.service.CostRunPartItemService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成本试算零部件控制器 - 查询试算零部件数据
 */
@RestController
@RequestMapping("/api/v1/cost-run")
public class CostRunPartItemController {
  private final CostRunPartItemService costRunPartItemService;

  public CostRunPartItemController(CostRunPartItemService costRunPartItemService) {
    this.costRunPartItemService = costRunPartItemService;
  }

  /** 查询试算零部件列表 */
  @PreAuthorize("@ss.hasPermi('cost:run:list')")
  @GetMapping("/parts")
  public CommonResult<List<CostRunPartItemDto>> list(@RequestParam String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"oaNo is required");
    }
    return CommonResult.success(costRunPartItemService.listByOaNo(oaNo));
  }

  /** 查询试算零部件结果列表 */
  @PreAuthorize("@ss.hasPermi('cost:run:list')")
  @GetMapping("/parts/result")
  public CommonResult<List<CostRunPartItemDto>> listResult(@RequestParam String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"oaNo is required");
    }
    return CommonResult.success(costRunPartItemService.listStoredByOaNo(oaNo));
  }
}
