package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.FactorUploadBatchDto;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceAdjustRequest;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceAdjustmentResponse;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceChangeLogDto;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportBatchDetailDto;
import com.sanhua.marketingcost.dto.PriceLinkedItemDto;
import com.sanhua.marketingcost.dto.PriceLinkedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceLinkedItemUpdateRequest;
import com.sanhua.marketingcost.service.FactorMonthlyPriceAdjustmentService;
import com.sanhua.marketingcost.service.PriceLinkedItemService;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 联动价格明细控制器 - 管理联动价格明细的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/price-linked")
public class PriceLinkedItemController {
  private final PriceLinkedItemService priceLinkedItemService;
  private final FactorMonthlyPriceAdjustmentService factorMonthlyPriceAdjustmentService;

  public PriceLinkedItemController(
      PriceLinkedItemService priceLinkedItemService,
      FactorMonthlyPriceAdjustmentService factorMonthlyPriceAdjustmentService) {
    this.priceLinkedItemService = priceLinkedItemService;
    this.factorMonthlyPriceAdjustmentService = factorMonthlyPriceAdjustmentService;
  }

  /** 查询联动价格明细列表 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:list')")
  @GetMapping("/items")
  public CommonResult<List<PriceLinkedItemDto>> list(
      @RequestParam(required = false) String pricingMonth,
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false, defaultValue = "false") Boolean includeHistory) {
    return CommonResult.success(
        priceLinkedItemService.list(
            pricingMonth, materialCode, Boolean.TRUE.equals(includeHistory)));
  }

  /** 修改联动价格明细 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:edit')")
  @PatchMapping("/items/{id}")
  public CommonResult<PriceLinkedItemDto> update(
      @PathVariable Long id,
      @RequestBody PriceLinkedItemUpdateRequest request) {
    PriceLinkedItemDto updated = priceLinkedItemService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"linked item not found");
    }
    return CommonResult.success(updated);
  }

  /** 导入联动价格明细数据（JSON 批量） */
  @PreAuthorize("@ss.hasPermi('price:linked-item:import')")
  @PostMapping("/items/import")
  public CommonResult<List<PriceLinkedItemDto>> importItems(
      @RequestBody PriceLinkedItemImportRequest request) {
    return CommonResult.success(priceLinkedItemService.importItems(request));
  }

  /**
   * 联动+固定 Excel 导入 —— T18 新增。
   *
   * <p>上传 "原材料(联动+固定)" / "联动价-部品" 等表，按 {@code 订单类型} 列分流到
   * {@code lp_price_linked_item} / {@code lp_price_fixed_item}。联动行公式过
   * {@code FormulaNormalizer} 校验；非法公式单行跳过不影响整批。
   */
  @PreAuthorize("@ss.hasPermi('price:linked-item:import')")
  @PostMapping("/items/import-excel")
  public CommonResult<PriceItemImportResponse> importExcel(
      @RequestPart("file") MultipartFile file,
      @RequestParam("pricingMonth") String pricingMonth,
      @RequestParam(value = "businessUnitType", required = false) String businessUnitType,
      @RequestParam(value = "overwriteManual", defaultValue = "false") boolean overwriteManual,
      @RequestParam(value = "effectiveStrategy", required = false) String effectiveStrategy,
      @RequestParam(value = "formulaEffectiveDate", required = false) String formulaEffectiveDate,
      @RequestParam(value = "factorPriceConflictStrategy", required = false)
          String factorPriceConflictStrategy) {
    if (file == null || file.isEmpty()) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "file is required");
    }
    try {
      return CommonResult.success(
          priceLinkedItemService.importExcel(
              file.getInputStream(), pricingMonth, overwriteManual,
              businessUnitType, file.getOriginalFilename(), effectiveStrategy,
              formulaEffectiveDate, factorPriceConflictStrategy));
    } catch (IllegalArgumentException e) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), e.getMessage());
    } catch (IOException e) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
          "读取上传文件失败: " + e.getMessage());
    }
  }

  /** 查询月度联动价与影响因素 Excel 导入历史 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:import-history:list')"
      + " or @ss.hasPermi('price:linked-item:list')")
  @GetMapping("/items/import-history")
  public CommonResult<List<FactorUploadBatchDto>> listImportHistory(
      @RequestParam(required = false) String pricingMonth,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(required = false) String uploadedBy,
      @RequestParam(required = false, defaultValue = "false") Boolean includeAllUploaders,
      @RequestParam(required = false) Integer limit) {
    return CommonResult.success(
        priceLinkedItemService.listImportHistory(
            pricingMonth, businessUnitType, uploadedBy, includeAllUploaders, limit));
  }

  /** 查询某个导入批次的影响因素预览和自动绑定日志 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:import-history:list')"
      + " or @ss.hasPermi('price:linked-item:list')")
  @GetMapping("/items/import-history/{factorUploadBatchId}")
  public CommonResult<PriceLinkedImportBatchDetailDto> getImportBatchDetail(
      @PathVariable Long factorUploadBatchId) {
    PriceLinkedImportBatchDetailDto detail =
        priceLinkedItemService.getImportBatchDetail(factorUploadBatchId);
    if (detail == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "import batch not found");
    }
    return CommonResult.success(detail);
  }

  /** 查询月度影响因素导入批次，供影响因素表菜单使用；数据仍来自 lp_factor_upload_batch。 */
  @PreAuthorize("@ss.hasPermi('price:finance-base:batch:list')"
      + " or @ss.hasPermi('price:finance-base:list')"
      + " or @ss.hasPermi('price:linked-item:list')")
  @GetMapping("/factors/import-batches")
  public CommonResult<List<FactorUploadBatchDto>> listFactorImportBatches(
      @RequestParam(required = false) String pricingMonth,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(required = false) String uploadedBy,
      @RequestParam(required = false, defaultValue = "false") Boolean includeAllUploaders,
      @RequestParam(required = false) Integer limit) {
    return CommonResult.success(
        priceLinkedItemService.listImportHistory(
            pricingMonth, businessUnitType, uploadedBy, includeAllUploaders, limit));
  }

  /** 查询某个影响因素导入批次的行级来源明细，供影响因素表菜单做来源追溯。 */
  @PreAuthorize("@ss.hasPermi('price:finance-base:batch:list')"
      + " or @ss.hasPermi('price:finance-base:list')"
      + " or @ss.hasPermi('price:linked-item:list')")
  @GetMapping("/factors/import-batches/{factorUploadBatchId}")
  public CommonResult<PriceLinkedImportBatchDetailDto> getFactorImportBatchDetail(
      @PathVariable Long factorUploadBatchId) {
    PriceLinkedImportBatchDetailDto detail =
        priceLinkedItemService.getImportBatchDetail(factorUploadBatchId);
    if (detail == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "import batch not found");
    }
    return CommonResult.success(detail);
  }

  /** 影响因素月度调价：只更新 lp_factor_monthly_price.price，不改变联动价变量绑定关系。 */
  @PreAuthorize("@ss.hasPermi('price:finance-base:edit')")
  @PatchMapping("/factors/monthly-prices/{factorMonthlyPriceId}/adjust")
  public CommonResult<FactorMonthlyPriceAdjustmentResponse> adjustFactorMonthlyPrice(
      @PathVariable Long factorMonthlyPriceId,
      @RequestBody FactorMonthlyPriceAdjustRequest request,
      Authentication authentication) {
    try {
      return CommonResult.success(
          factorMonthlyPriceAdjustmentService.adjust(
              factorMonthlyPriceId, request, currentUsername(authentication)));
    } catch (IllegalArgumentException ex) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), ex.getMessage());
    }
  }

  /** 查询某个影响因素月度价格的调价日志，供影响因素表页面做审计追溯。 */
  @PreAuthorize("@ss.hasPermi('price:finance-base:list') or @ss.hasPermi('price:linked-item:list')")
  @GetMapping("/factors/monthly-prices/{factorMonthlyPriceId}/change-logs")
  public CommonResult<List<FactorMonthlyPriceChangeLogDto>> listFactorMonthlyPriceChangeLogs(
      @PathVariable Long factorMonthlyPriceId) {
    return CommonResult.success(
        factorMonthlyPriceAdjustmentService.listChangeLogs(factorMonthlyPriceId));
  }

  /** 新增联动价格明细 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:add')")
  @PostMapping("/items")
  public CommonResult<PriceLinkedItemDto> create(
      @RequestBody PriceLinkedItemUpdateRequest request) {
    PriceLinkedItemDto created = priceLinkedItemService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"create failed");
    }
    return CommonResult.success(created);
  }

  /** 删除联动价格明细 */
  @PreAuthorize("@ss.hasPermi('price:linked-item:remove')")
  @DeleteMapping("/items/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(priceLinkedItemService.delete(id));
  }

  private String currentUsername(Authentication authentication) {
    return authentication == null ? "system" : authentication.getName();
  }

}
