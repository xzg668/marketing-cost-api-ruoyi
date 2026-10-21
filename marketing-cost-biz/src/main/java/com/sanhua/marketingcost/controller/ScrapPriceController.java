package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.aspect.OperationLogDiffContext;
import com.sanhua.marketingcost.entity.ScrapPrice;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.scrap.ScrapPriceImportService;
import com.sanhua.marketingcost.service.scrap.ScrapPriceQueryService;
import java.io.IOException;
import java.security.Principal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/scrap-prices")
public class ScrapPriceController {
  private final ScrapPriceImportService imports;
  private final ScrapPriceQueryService query;

  public ScrapPriceController(ScrapPriceImportService imports, ScrapPriceQueryService query) {
    this.imports = imports;
    this.query = query;
  }

  @GetMapping
  @PreAuthorize("@ss.hasPermi('price:scrap:list')")
  public CommonResult<Page<ScrapPrice>> list(
      @RequestParam String priceMonth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String priceType,
      @RequestParam(required = false) String supplier,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(
        query.list(unit(), priceMonth, keyword, priceType, supplier, page, size));
  }

  @GetMapping("/{id}")
  @PreAuthorize("@ss.hasPermi('price:scrap:list')")
  public CommonResult<ScrapPrice> detail(@PathVariable long id) {
    return CommonResult.success(query.detail(unit(), id));
  }

  @PostMapping("/import/preview")
  @PreAuthorize("@ss.hasPermi('price:scrap:import')")
  public CommonResult<ScrapPriceImportService.Preview> preview(
      @RequestPart("file") MultipartFile file, @RequestParam String priceMonth) throws IOException {
    return CommonResult.success(
        imports.preview(file.getBytes(), file.getOriginalFilename(), unit(), priceMonth));
  }

  @PostMapping("/import")
  @PreAuthorize("@ss.hasPermi('price:scrap:import')")
  @OperationLog(module = "废料价格", operationType = OperationType.IMPORT)
  public CommonResult<ScrapPriceImportService.ImportResult> confirm(
      @RequestPart("file") MultipartFile file,
      @RequestParam String priceMonth,
      @RequestParam String previewFingerprint,
      Principal principal)
      throws IOException {
    return CommonResult.success(
        imports.confirm(
            file.getBytes(),
            file.getOriginalFilename(),
            unit(),
            priceMonth,
            previewFingerprint,
            actor(principal)));
  }

  @PatchMapping("/{id}")
  @PreAuthorize("@ss.hasPermi('price:scrap:edit')")
  @OperationLog(
      module = "废料价格",
      operationType = OperationType.UPDATE,
      recordDiff = true,
      targetIdParam = "id")
  public CommonResult<ScrapPrice> edit(
      @PathVariable long id,
      @RequestBody ScrapPriceImportService.Edit request,
      Principal principal) {
    String operator = actor(principal);
    ScrapPrice before = query.detail(unit(), id);
    ScrapPrice updated = imports.edit(id, unit(), request, operator);
    OperationLogDiffContext.record(before, updated);
    return CommonResult.success(updated);
  }

  private String unit() {
    return BusinessUnitContext.getCurrentBusinessUnitType();
  }

  private String actor(Principal principal) {
    if (principal == null) throw new IllegalArgumentException("请登录后维护废料价格");
    return principal.getName();
  }
}
