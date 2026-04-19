package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.OtherExpenseRateImportRequest;
import com.sanhua.marketingcost.dto.OtherExpenseRatePageResponse;
import com.sanhua.marketingcost.dto.OtherExpenseRateRequest;
import com.sanhua.marketingcost.entity.OtherExpenseRate;
import com.sanhua.marketingcost.service.OtherExpenseRateService;
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
 * 其他费用率控制器 - 管理其他费用率的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/other-expense-rates")
public class OtherExpenseRateController {
  private final OtherExpenseRateService otherExpenseRateService;

  public OtherExpenseRateController(OtherExpenseRateService otherExpenseRateService) {
    this.otherExpenseRateService = otherExpenseRateService;
  }

  /** 查询其他费用率列表 */
  @PreAuthorize("@ss.hasPermi('base:other-expense:list')")
  @GetMapping
  public CommonResult<OtherExpenseRatePageResponse> list(
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String productName) {
    List<OtherExpenseRate> list = otherExpenseRateService.list(materialCode, productName);
    return CommonResult.success(new OtherExpenseRatePageResponse(list.size(), list));
  }

  /** 新增其他费用率 */
  @PreAuthorize("@ss.hasPermi('base:other-expense:add')")
  @PostMapping
  public CommonResult<OtherExpenseRate> create(@RequestBody OtherExpenseRateRequest request) {
    OtherExpenseRate created = otherExpenseRateService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改其他费用率 */
  @PreAuthorize("@ss.hasPermi('base:other-expense:edit')")
  @PatchMapping("/{id}")
  public CommonResult<OtherExpenseRate> update(
      @PathVariable Long id,
      @RequestBody OtherExpenseRateRequest request) {
    OtherExpenseRate updated = otherExpenseRateService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"other expense rate not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除其他费用率 */
  @PreAuthorize("@ss.hasPermi('base:other-expense:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(otherExpenseRateService.delete(id));
  }

  /** 导入其他费用率数据 */
  @PreAuthorize("@ss.hasPermi('base:other-expense:import')")
  @PostMapping("/import")
  public CommonResult<List<OtherExpenseRate>> importItems(
      @RequestBody OtherExpenseRateImportRequest request) {
    return CommonResult.success(otherExpenseRateService.importItems(request));
  }
}
