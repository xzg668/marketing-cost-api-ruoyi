package com.sanhua.marketingcost.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.QualityLossRateImportRequest;
import com.sanhua.marketingcost.dto.QualityLossRatePageResponse;
import com.sanhua.marketingcost.dto.QualityLossRateRequest;
import com.sanhua.marketingcost.entity.QualityLossRate;
import com.sanhua.marketingcost.service.QualityLossRateService;
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
 * 质量损失率控制器 - 管理质量损失率的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/quality-loss-rates")
public class QualityLossRateController {
  private final QualityLossRateService qualityLossRateService;

  public QualityLossRateController(QualityLossRateService qualityLossRateService) {
    this.qualityLossRateService = qualityLossRateService;
  }

  /** 查询质量损失率列表 */
  @PreAuthorize("@ss.hasPermi('base:quality-loss:list')")
  @GetMapping
  public CommonResult<QualityLossRatePageResponse> list(
      @RequestParam(required = false) String company,
      @RequestParam(required = false) String businessUnit,
      @RequestParam(required = false) String productCategory,
      @RequestParam(required = false) String productSubcategory,
      @RequestParam(required = false) String customer,
      @RequestParam(required = false) String period,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<QualityLossRate> pager = qualityLossRateService.page(
        company,
        businessUnit,
        productCategory,
        productSubcategory,
        customer,
        period,
        current,
        size);
    return CommonResult.success(new QualityLossRatePageResponse(pager.getTotal(), pager.getRecords()));
  }

  /** 新增质量损失率 */
  @PreAuthorize("@ss.hasPermi('base:quality-loss:add')")
  @PostMapping
  public CommonResult<QualityLossRate> create(@RequestBody QualityLossRateRequest request) {
    QualityLossRate created = qualityLossRateService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改质量损失率 */
  @PreAuthorize("@ss.hasPermi('base:quality-loss:edit')")
  @PatchMapping("/{id}")
  public CommonResult<QualityLossRate> update(
      @PathVariable Long id,
      @RequestBody QualityLossRateRequest request) {
    QualityLossRate updated = qualityLossRateService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"quality loss rate not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除质量损失率 */
  @PreAuthorize("@ss.hasPermi('base:quality-loss:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(qualityLossRateService.delete(id));
  }

  /** 导入质量损失率数据 */
  @PreAuthorize("@ss.hasPermi('base:quality-loss:import')")
  @PostMapping("/import")
  public CommonResult<List<QualityLossRate>> importItems(
      @RequestBody QualityLossRateImportRequest request) {
    return CommonResult.success(qualityLossRateService.importItems(request));
  }
}
