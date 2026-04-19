package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.DepartmentFundRateImportRequest;
import com.sanhua.marketingcost.dto.DepartmentFundRatePageResponse;
import com.sanhua.marketingcost.dto.DepartmentFundRateRequest;
import com.sanhua.marketingcost.entity.DepartmentFundRate;
import com.sanhua.marketingcost.service.DepartmentFundRateService;
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
 * 部门经费率控制器 - 管理部门经费率的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/department-fund-rates")
public class DepartmentFundRateController {
  private final DepartmentFundRateService departmentFundRateService;

  public DepartmentFundRateController(DepartmentFundRateService departmentFundRateService) {
    this.departmentFundRateService = departmentFundRateService;
  }

  /** 查询部门经费率列表 */
  @PreAuthorize("@ss.hasPermi('base:dept-fund:list')")
  @GetMapping
  public CommonResult<DepartmentFundRatePageResponse> list(
      @RequestParam(required = false) String businessUnit) {
    List<DepartmentFundRate> list = departmentFundRateService.list(businessUnit);
    return CommonResult.success(new DepartmentFundRatePageResponse(list.size(), list));
  }

  /** 新增部门经费率 */
  @PreAuthorize("@ss.hasPermi('base:dept-fund:add')")
  @PostMapping
  public CommonResult<DepartmentFundRate> create(@RequestBody DepartmentFundRateRequest request) {
    DepartmentFundRate created = departmentFundRateService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改部门经费率 */
  @PreAuthorize("@ss.hasPermi('base:dept-fund:edit')")
  @PatchMapping("/{id}")
  public CommonResult<DepartmentFundRate> update(
      @PathVariable Long id,
      @RequestBody DepartmentFundRateRequest request) {
    DepartmentFundRate updated = departmentFundRateService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"department fund rate not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除部门经费率 */
  @PreAuthorize("@ss.hasPermi('base:dept-fund:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(departmentFundRateService.delete(id));
  }

  /** 导入部门经费率数据 */
  @PreAuthorize("@ss.hasPermi('base:dept-fund:import')")
  @PostMapping("/import")
  public CommonResult<List<DepartmentFundRate>> importItems(
      @RequestBody DepartmentFundRateImportRequest request) {
    return CommonResult.success(departmentFundRateService.importItems(request));
  }
}
