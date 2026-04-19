package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.ManufactureRateImportRequest;
import com.sanhua.marketingcost.dto.ManufactureRatePageResponse;
import com.sanhua.marketingcost.dto.ManufactureRateRequest;
import com.sanhua.marketingcost.entity.ManufactureRate;
import com.sanhua.marketingcost.service.ManufactureRateService;
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
 * 制造费率控制器 - 管理制造费率的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/manufacture-rates")
public class ManufactureRateController {
  private final ManufactureRateService manufactureRateService;

  public ManufactureRateController(ManufactureRateService manufactureRateService) {
    this.manufactureRateService = manufactureRateService;
  }

  /** 查询制造费率列表 */
  @PreAuthorize("@ss.hasPermi('base:manufacture-rate:list')")
  @GetMapping
  public CommonResult<ManufactureRatePageResponse> list(
      @RequestParam(required = false) String businessUnit) {
    List<ManufactureRate> list = manufactureRateService.list(businessUnit);
    return CommonResult.success(new ManufactureRatePageResponse(list.size(), list));
  }

  /** 新增制造费率 */
  @PreAuthorize("@ss.hasPermi('base:manufacture-rate:add')")
  @PostMapping
  public CommonResult<ManufactureRate> create(@RequestBody ManufactureRateRequest request) {
    ManufactureRate created = manufactureRateService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改制造费率 */
  @PreAuthorize("@ss.hasPermi('base:manufacture-rate:edit')")
  @PatchMapping("/{id}")
  public CommonResult<ManufactureRate> update(
      @PathVariable Long id,
      @RequestBody ManufactureRateRequest request) {
    ManufactureRate updated = manufactureRateService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"manufacture rate not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除制造费率 */
  @PreAuthorize("@ss.hasPermi('base:manufacture-rate:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(manufactureRateService.delete(id));
  }

  /** 导入制造费率数据 */
  @PreAuthorize("@ss.hasPermi('base:manufacture-rate:import')")
  @PostMapping("/import")
  public CommonResult<List<ManufactureRate>> importItems(
      @RequestBody ManufactureRateImportRequest request) {
    return CommonResult.success(manufactureRateService.importItems(request));
  }
}
