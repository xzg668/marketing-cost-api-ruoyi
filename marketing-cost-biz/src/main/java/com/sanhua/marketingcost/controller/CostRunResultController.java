package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.service.CostRunResultService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成本试算结果控制器 - 查询试算结果
 */
@RestController
@RequestMapping("/api/v1/cost-run")
public class CostRunResultController {
  private final CostRunResultService costRunResultService;

  public CostRunResultController(CostRunResultService costRunResultService) {
    this.costRunResultService = costRunResultService;
  }

  /** 查询试算结果列表 */
  @PreAuthorize("@ss.hasPermi('cost:run:list')")
  @GetMapping("/result")
  public CommonResult<CostRunResultDto> getResult(
      @RequestParam String oaNo, @RequestParam(required = false) String productCode) {
    if (!StringUtils.hasText(oaNo)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"oaNo is required");
    }
    return CommonResult.success(costRunResultService.getResult(oaNo, productCode));
  }
}
