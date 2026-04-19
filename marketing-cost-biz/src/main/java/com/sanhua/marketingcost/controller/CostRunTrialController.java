package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.CostRunProgressResponse;
import com.sanhua.marketingcost.dto.CostRunTrialRequest;
import com.sanhua.marketingcost.service.CostRunTrialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 成本试算控制器 - 提交试算任务与查询进度
 */
@RestController
@RequestMapping("/api/v1/cost-run")
public class CostRunTrialController {
  private static final Logger log = LoggerFactory.getLogger(CostRunTrialController.class);
  private final CostRunTrialService costRunTrialService;

  public CostRunTrialController(CostRunTrialService costRunTrialService) {
    this.costRunTrialService = costRunTrialService;
  }

  /** 提交成本试算任务 */
  @PreAuthorize("@ss.hasPermi('cost:run:edit')")
  @PostMapping("/trial")
  public CommonResult<String> run(@RequestBody CostRunTrialRequest request) {
    if (request == null || !StringUtils.hasText(request.getOaNo())) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"oaNo is required");
    }
    costRunTrialService.run(request.getOaNo())
        .exceptionally(ex -> {
          log.error("试算异步执行失败, oaNo={}", request.getOaNo(), ex);
          return null;
        });
    return CommonResult.success("试算已提交，请通过 progress 接口查询进度");
  }

  /** 查询试算进度 */
  @PreAuthorize("@ss.hasPermi('cost:run:list')")
  @GetMapping("/progress")
  public CommonResult<CostRunProgressResponse> progress(@RequestParam("oaNo") String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"oaNo is required");
    }
    return CommonResult.success(costRunTrialService.progress(oaNo));
  }
}
