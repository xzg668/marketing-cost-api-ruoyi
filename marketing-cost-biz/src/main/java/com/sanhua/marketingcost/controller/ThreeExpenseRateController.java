package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.ThreeExpenseRateImportRequest;
import com.sanhua.marketingcost.dto.ThreeExpenseRatePageResponse;
import com.sanhua.marketingcost.dto.ThreeExpenseRateRequest;
import com.sanhua.marketingcost.entity.ThreeExpenseRate;
import com.sanhua.marketingcost.service.ThreeExpenseRateService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 三项费用率控制器 - 管理三项费用率的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/three-expense-rates")
public class ThreeExpenseRateController {
  private final ThreeExpenseRateService threeExpenseRateService;

  public ThreeExpenseRateController(ThreeExpenseRateService threeExpenseRateService) {
    this.threeExpenseRateService = threeExpenseRateService;
  }

  /** 查询三项费用率列表 */
  @PreAuthorize("@ss.hasPermi('base:three-expense:list')")
  @GetMapping
  public CommonResult<ThreeExpenseRatePageResponse> list(
      @RequestParam(required = false) String department) {
    List<ThreeExpenseRate> list = threeExpenseRateService.list(department);
    return CommonResult.success(new ThreeExpenseRatePageResponse(list.size(), list));
  }

  /** 新增三项费用率 */
  @PreAuthorize("@ss.hasPermi('base:three-expense:add')")
  @PostMapping
  public CommonResult<ThreeExpenseRate> create(@RequestBody ThreeExpenseRateRequest request) {
    ThreeExpenseRate created = threeExpenseRateService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改三项费用率 */
  @PreAuthorize("@ss.hasPermi('base:three-expense:edit')")
  @PatchMapping("/{id}")
  public CommonResult<ThreeExpenseRate> update(
      @PathVariable Long id,
      @RequestBody ThreeExpenseRateRequest request) {
    ThreeExpenseRate updated = threeExpenseRateService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"three expense rate not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除三项费用率 */
  @PreAuthorize("@ss.hasPermi('base:three-expense:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(threeExpenseRateService.delete(id));
  }

  /** 导入三项费用率数据 */
  @PreAuthorize("@ss.hasPermi('base:three-expense:import')")
  @PostMapping("/import")
  public CommonResult<List<ThreeExpenseRate>> importItems(
      @RequestBody ThreeExpenseRateImportRequest request) {
    return CommonResult.success(threeExpenseRateService.importItems(request));
  }
}
