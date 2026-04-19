package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.BomManualItemImportRequest;
import com.sanhua.marketingcost.dto.BomManualItemPageResponse;
import com.sanhua.marketingcost.dto.BomManualItemRequest;
import com.sanhua.marketingcost.dto.BomManualSummaryPageResponse;
import com.sanhua.marketingcost.dto.BomManualSummaryRow;
import com.sanhua.marketingcost.entity.BomManualItem;
import com.sanhua.marketingcost.service.BomManualItemService;
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
 * 手工BOM控制器 - 管理手工录入的BOM数据
 */
@RestController
@RequestMapping("/api/v1/boms")
public class BomManualItemController {
  private final BomManualItemService bomManualItemService;

  public BomManualItemController(BomManualItemService bomManualItemService) {
    this.bomManualItemService = bomManualItemService;
  }

  /** 查询手工BOM列表 */
  @PreAuthorize("@ss.hasPermi('base:bom-manual:list')")
  @GetMapping
  public CommonResult<BomManualItemPageResponse> list(
      @RequestParam(required = false) String bomCode,
      @RequestParam(required = false) String itemCode,
      @RequestParam(required = false) String parentCode,
      @RequestParam(required = false) Integer level,
      @RequestParam(required = false) String shapeAttr,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<BomManualItem> pager =
        bomManualItemService.page(bomCode, itemCode, parentCode, level, shapeAttr, current, size);
    return CommonResult.success(new BomManualItemPageResponse(pager.getTotal(), pager.getRecords()));
  }

  /** 查询手工BOM汇总列表 */
  @PreAuthorize("@ss.hasPermi('base:bom-manual:list')")
  @GetMapping("/summary")
  public CommonResult<BomManualSummaryPageResponse> summary(
      @RequestParam(required = false) String bomCode,
      @RequestParam(required = false) String itemCode,
      @RequestParam(required = false) String parentCode,
      @RequestParam(required = false) Integer level,
      @RequestParam(required = false) String shapeAttr,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<BomManualSummaryRow> pager = bomManualItemService.summaryPage(
        bomCode, itemCode, parentCode, level, shapeAttr, current, size);
    return CommonResult.success(new BomManualSummaryPageResponse(pager.getTotal(), pager.getRecords()));
  }

  /** 查询手工BOM明细列表 */
  @PreAuthorize("@ss.hasPermi('base:bom-manual:list')")
  @GetMapping("/details")
  public CommonResult<List<BomManualItem>> details(
      @RequestParam String bomCode,
      @RequestParam(required = false) String itemCode,
      @RequestParam(required = false) String parentCode,
      @RequestParam(required = false) Integer level,
      @RequestParam(required = false) String shapeAttr) {
    if (!org.springframework.util.StringUtils.hasText(bomCode)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"missing bomCode");
    }
    return CommonResult.success(
        bomManualItemService.listByBomCode(bomCode, itemCode, parentCode, level, shapeAttr));
  }

  /** 新增手工BOM */
  @PreAuthorize("@ss.hasPermi('base:bom-manual:add')")
  @PostMapping
  public CommonResult<BomManualItem> create(@RequestBody BomManualItemRequest request) {
    BomManualItem created = bomManualItemService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改手工BOM */
  @PreAuthorize("@ss.hasPermi('base:bom-manual:edit')")
  @PatchMapping("/{id}")
  public CommonResult<BomManualItem> update(
      @PathVariable Long id,
      @RequestBody BomManualItemRequest request) {
    BomManualItem updated = bomManualItemService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"bom item not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除手工BOM */
  @PreAuthorize("@ss.hasPermi('base:bom-manual:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(bomManualItemService.delete(id));
  }

  /** 导入手工BOM数据 */
  @PreAuthorize("@ss.hasPermi('base:bom-manual:import')")
  @PostMapping("/import")
  public CommonResult<List<BomManualItem>> importItems(
      @RequestBody BomManualItemImportRequest request) {
    return CommonResult.success(bomManualItemService.importItems(request));
  }
}
