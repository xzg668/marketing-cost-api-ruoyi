package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.SalaryCostImportRequest;
import com.sanhua.marketingcost.dto.SalaryCostPageResponse;
import com.sanhua.marketingcost.dto.SalaryCostRequest;
import com.sanhua.marketingcost.entity.SalaryCost;
import com.sanhua.marketingcost.service.SalaryCostService;
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
 * 工资成本控制器 - 管理工资成本的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/salary-costs")
public class SalaryCostController {
  private final SalaryCostService salaryCostService;

  public SalaryCostController(SalaryCostService salaryCostService) {
    this.salaryCostService = salaryCostService;
  }

  /** 查询工资成本列表 */
  @PreAuthorize("@ss.hasPermi('base:salary:list')")
  @GetMapping
  public CommonResult<SalaryCostPageResponse> list(
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String businessUnit) {
    List<SalaryCost> list = salaryCostService.list(materialCode, businessUnit);
    return CommonResult.success(new SalaryCostPageResponse(list.size(), list));
  }

  /** 新增工资成本 */
  @PreAuthorize("@ss.hasPermi('base:salary:add')")
  @PostMapping
  public CommonResult<SalaryCost> create(@RequestBody SalaryCostRequest request) {
    SalaryCost created = salaryCostService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改工资成本 */
  @PreAuthorize("@ss.hasPermi('base:salary:edit')")
  @PatchMapping("/{id}")
  public CommonResult<SalaryCost> update(
      @PathVariable Long id,
      @RequestBody SalaryCostRequest request) {
    SalaryCost updated = salaryCostService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"salary cost not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除工资成本 */
  @PreAuthorize("@ss.hasPermi('base:salary:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(salaryCostService.delete(id));
  }

  /** 导入工资成本数据 */
  @PreAuthorize("@ss.hasPermi('base:salary:import')")
  @PostMapping("/import")
  public CommonResult<List<SalaryCost>> importItems(
      @RequestBody SalaryCostImportRequest request) {
    return CommonResult.success(salaryCostService.importItems(request));
  }
}
