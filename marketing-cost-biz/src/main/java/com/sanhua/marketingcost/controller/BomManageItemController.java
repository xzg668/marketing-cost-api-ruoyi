package com.sanhua.marketingcost.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.BomManageParentPageResponse;
import com.sanhua.marketingcost.dto.BomManageParentRow;
import com.sanhua.marketingcost.dto.BomManageRefreshRequest;
import com.sanhua.marketingcost.entity.BomManageItem;
import com.sanhua.marketingcost.service.BomManageItemService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BOM管理控制器 - 管理BOM主数据的查询与刷新
 */
@RestController
@RequestMapping("/api/v1/bom-manage")
public class BomManageItemController {
  private final BomManageItemService bomManageItemService;

  public BomManageItemController(BomManageItemService bomManageItemService) {
    this.bomManageItemService = bomManageItemService;
  }

  /** 查询BOM管理列表 */
  @PreAuthorize("@ss.hasPermi('base:bom:list')")
  @GetMapping
  public CommonResult<BomManageParentPageResponse> list(
      @RequestParam(required = false) String oaNo,
      @RequestParam(required = false) String bomCode,
      @RequestParam(required = false) String materialNo,
      @RequestParam(required = false) String shapeAttr,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<BomManageParentRow> pager =
        bomManageItemService.page(oaNo, bomCode, materialNo, shapeAttr, current, size);
    return CommonResult.success(new BomManageParentPageResponse(pager.getTotal(), pager.getRecords()));
  }

  /** 查询BOM管理明细列表 */
  @PreAuthorize("@ss.hasPermi('base:bom:list')")
  @GetMapping("/details")
  public CommonResult<List<BomManageItem>> details(
      @RequestParam String oaNo,
      @RequestParam Long oaFormItemId,
      @RequestParam String bomCode,
      @RequestParam String rootItemCode,
      @RequestParam(required = false) String shapeAttr) {
    if (!org.springframework.util.StringUtils.hasText(oaNo)
        || oaFormItemId == null
        || !org.springframework.util.StringUtils.hasText(bomCode)
        || !org.springframework.util.StringUtils.hasText(rootItemCode)) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"missing required params");
    }
    return CommonResult.success(
        bomManageItemService.listDetails(oaNo, oaFormItemId, bomCode, rootItemCode, shapeAttr));
  }

  /** 刷新BOM管理数据 */
  @PreAuthorize("@ss.hasPermi('base:bom:edit')")
  @PostMapping("/refresh")
  public CommonResult<Integer> refresh(@RequestBody BomManageRefreshRequest request) {
    if (request == null || !org.springframework.util.StringUtils.hasText(request.getOaNo())) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"missing oaNo");
    }
    return CommonResult.success(bomManageItemService.refresh(request));
  }
}
