package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.FinanceBasePriceImportRequest;
import com.sanhua.marketingcost.dto.FinanceBasePriceRequest;
import com.sanhua.marketingcost.dto.InfluenceFactorImportResponse;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.service.FinanceBasePriceImportService;
import com.sanhua.marketingcost.service.FinanceBasePriceService;
import java.io.IOException;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 财务基价控制器 - 管理财务基价的增删改查与导入
 */
@RestController
@RequestMapping("/api/v1/base-prices")
public class FinanceBasePriceController {
  private final FinanceBasePriceService financeBasePriceService;
  private final FinanceBasePriceImportService financeBasePriceImportService;

  public FinanceBasePriceController(
      FinanceBasePriceService financeBasePriceService,
      FinanceBasePriceImportService financeBasePriceImportService) {
    this.financeBasePriceService = financeBasePriceService;
    this.financeBasePriceImportService = financeBasePriceImportService;
  }

  /** 查询财务基价列表 */
  @PreAuthorize("@ss.hasPermi('price:finance-base:list')")
  @GetMapping
  public CommonResult<List<FinanceBasePrice>> list(
      @RequestParam(required = false) String priceMonth,
      @RequestParam(required = false) String keyword) {
    return CommonResult.success(financeBasePriceService.list(priceMonth, keyword));
  }

  /** 新增财务基价 */
  @PreAuthorize("@ss.hasPermi('price:finance-base:add')")
  @PostMapping
  public CommonResult<FinanceBasePrice> create(@RequestBody FinanceBasePriceRequest request) {
    return CommonResult.success(financeBasePriceService.create(request));
  }

  /** 修改财务基价 */
  @PreAuthorize("@ss.hasPermi('price:finance-base:edit')")
  @PatchMapping("/{id}")
  public CommonResult<FinanceBasePrice> update(
      @PathVariable Long id,
      @RequestBody FinanceBasePriceRequest request) {
    FinanceBasePrice updated = financeBasePriceService.update(id, request);
    if (updated == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(),"base price not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除财务基价 */
  @PreAuthorize("@ss.hasPermi('price:finance-base:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(financeBasePriceService.delete(id));
  }

  /** 导入财务基价数据（JSON 批量，老入口，保留供管理 UI 用） */
  @PreAuthorize("@ss.hasPermi('price:finance-base:import')")
  @PostMapping("/import")
  public CommonResult<List<FinanceBasePrice>> importPrices(
      @RequestBody FinanceBasePriceImportRequest request) {
    return CommonResult.success(financeBasePriceService.importPrices(request));
  }

  /**
   * 影响因素 10 Excel 导入 —— T17 新增。
   *
   * <p>表单字段：
   * <ul>
   *   <li>{@code file} —— 上传的 .xlsx（MultipartFile）</li>
   *   <li>{@code priceMonth} —— 价期月（如 "2026-02"），必填；Excel 本身不承载月份列</li>
   * </ul>
   * 响应含 batchId + 统计 + 错误明细；单行错误不会让整批失败。
   */
  @PreAuthorize("@ss.hasPermi('price:finance-base:import')")
  @PostMapping("/import-excel")
  public CommonResult<InfluenceFactorImportResponse> importExcel(
      @RequestPart("file") MultipartFile file,
      @RequestParam("priceMonth") String priceMonth) {
    if (file == null || file.isEmpty()) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "file is required");
    }
    try {
      return CommonResult.success(
          financeBasePriceImportService.importExcel(file.getInputStream(), priceMonth));
    } catch (IOException e) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(),
          "读取上传文件失败: " + e.getMessage());
    }
  }
}
