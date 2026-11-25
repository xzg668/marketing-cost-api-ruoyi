package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.pricing.WorkbookPriceImportHistory;
import static com.sanhua.marketingcost.service.pricing.WorkbookPriceImportHistory.Kind.RANGE_ORIGINAL;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceRangeItemImportRequest;
import com.sanhua.marketingcost.dto.PriceRangeItemPageResponse;
import com.sanhua.marketingcost.dto.PriceRangeItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.service.PriceRangeItemService;
import com.sanhua.marketingcost.service.impl.OriginalRangeImportService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import org.springframework.web.multipart.MultipartFile;

/** 区间价格控制器 - 管理区间价格的增删改查与导入 */
@RestController
@RequestMapping("/api/v1/price-range/items")
public class PriceRangeItemController {
  @org.springframework.beans.factory.annotation.Autowired
  private WorkbookPriceImportHistory importHistory;

  private final PriceRangeItemService priceRangeItemService;
  private final OriginalRangeImportService originals;

  public PriceRangeItemController(
      PriceRangeItemService priceRangeItemService, OriginalRangeImportService originals) {
    this.priceRangeItemService = priceRangeItemService;
    this.originals = originals;
  }

  @PreAuthorize("@ss.hasPermi('price:range:import')")
  @PostMapping(value = "/original-preview", consumes = "multipart/form-data")
  public CommonResult<OriginalRangeImportService.Preview> previewOriginal(
      @RequestParam MultipartFile file,
      @RequestParam String pricingMonth,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(required = false) Long supplementRuleId)
      throws IOException {
    return CommonResult.success(
        originals.preview(
            new OriginalRangeImportService.Command(
                file.getBytes(),
                file.getOriginalFilename(),
                businessUnitType,
                pricingMonth,
                null,
                null,
                supplementRuleId)));
  }

  @PreAuthorize("@ss.hasPermi('price:range:import')")
  @PostMapping(value = "/original-import", consumes = "multipart/form-data")
  public CommonResult<OriginalRangeImportService.Publication> importOriginal(
      @RequestParam MultipartFile file,
      @RequestParam String pricingMonth,
      @RequestParam(required = false) String businessUnitType,
      @RequestParam(required = false) Long supplementRuleId,
      @RequestParam String expectedSha256,
      @RequestParam String previewFingerprint)
      throws IOException {
    return CommonResult.success(
        originals.confirm(
            new OriginalRangeImportService.Command(
                file.getBytes(),
                file.getOriginalFilename(),
                businessUnitType,
                pricingMonth,
                expectedSha256,
                previewFingerprint,
                supplementRuleId)));
  }

  @PreAuthorize("@ss.hasPermi('price:range:list') && @ss.hasPermi('price:range:import')")
  @GetMapping("/original-rules/{id}/template")
  public ResponseEntity<byte[]> originalTemplate(@PathVariable Long id) {
    var file = originals.supplementTemplate(id);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(file.name(), StandardCharsets.UTF_8).build().toString())
        .body(file.bytes());
  }

  @PreAuthorize("@ss.hasPermi('price:range:list')")
  @GetMapping("/original-rules/options")
  public CommonResult<List<OriginalRangeImportService.RuleOption>> originalOptions(
      @RequestParam String pricingMonth, @RequestParam String materialCode) {
    return CommonResult.success(originals.options(pricingMonth, materialCode));
  }

  @PreAuthorize("@ss.hasPermi('price:range:add') && @ss.hasPermi('price:range:list')")
  @PostMapping("/original-rules/{id}/bands")
  public CommonResult<OriginalRangeImportService.Publication> addOriginalBand(
      @PathVariable Long id, @RequestBody OriginalRangeImportService.BandAddition request) {
    return CommonResult.success(originals.addBand(id, request));
  }

  @PreAuthorize("@ss.hasPermi('price:range:list')")
  @GetMapping("/original-rules/{id}")
  public CommonResult<OriginalRangeImportService.PublishedRule> originalDetail(
      @PathVariable Long id) {
    return CommonResult.success(originals.detail(id));
  }

  @PreAuthorize("@ss.hasPermi('price:range:list')")
  @GetMapping
  public CommonResult<PriceRangeItemPageResponse> list(
      @RequestParam(required = false) String materialCode,
      @RequestParam(required = false) String supplierCode,
      @RequestParam(required = false) String specModel,
      @RequestParam(required = false) String effectiveFrom,
      @RequestParam(required = false, defaultValue = "1") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
    int current = page == null || page < 1 ? 1 : page;
    int size = pageSize == null || pageSize < 1 ? 20 : pageSize;
    Page<PriceRangeItem> pager =
        priceRangeItemService.page(
            materialCode, supplierCode, specModel, effectiveFrom, current, size);
    return CommonResult.success(
        new PriceRangeItemPageResponse(pager.getTotal(), pager.getRecords()));
  }

  /** 新增区间价格 */
  @PreAuthorize("@ss.hasPermi('price:range:add')")
  @PostMapping
  public CommonResult<PriceRangeItem> create(@RequestBody PriceRangeItemUpdateRequest request) {
    PriceRangeItem created = priceRangeItemService.create(request);
    if (created == null) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "create failed");
    }
    return CommonResult.success(created);
  }

  /** 修改区间价格 */
  @PreAuthorize("@ss.hasPermi('price:range:edit')")
  @PatchMapping("/{id}")
  public CommonResult<PriceRangeItem> update(
      @PathVariable Long id, @RequestBody PriceRangeItemUpdateRequest request) {
    PriceRangeItem updated = priceRangeItemService.update(id, request);
    if (updated == null) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), "range item not found");
    }
    return CommonResult.success(updated);
  }

  /** 删除区间价格 */
  @PreAuthorize("@ss.hasPermi('price:range:remove')")
  @DeleteMapping("/{id}")
  public CommonResult<Boolean> delete(@PathVariable Long id) {
    return CommonResult.success(priceRangeItemService.delete(id));
  }

  /** 导入区间价格数据 */
  @PreAuthorize("@ss.hasPermi('price:range:import')")
  @PostMapping("/import")
  public CommonResult<List<PriceRangeItem>> importItems(
      @RequestBody PriceRangeItemImportRequest request) {
    return CommonResult.success(priceRangeItemService.importItems(request));
  }
  @PostMapping("/rows/{id}/void")
  @PreAuthorize("@ss.hasPermi('price:range:import')")
  @com.sanhua.marketingcost.annotation.OperationLog(module="区间价",operationType=com.sanhua.marketingcost.annotation.OperationType.UPDATE)
  public CommonResult<Void> voidRow(@PathVariable long id) {
    importHistory.withdrawRangeRow(com.sanhua.marketingcost.security.BusinessUnitContext.getCurrentBusinessUnitType(),id);
    return CommonResult.success(null);
  }

  @GetMapping("/imports")
  @PreAuthorize("@ss.hasPermi('price:range:import')")
  public CommonResult<WorkbookPriceImportHistory.Page> importHistory(
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
    return CommonResult.success(importHistory.list(com.sanhua.marketingcost.security.BusinessUnitContext.getCurrentBusinessUnitType(),
        RANGE_ORIGINAL,page,size));
  }

  @GetMapping("/imports/{id}/original")
  @PreAuthorize("@ss.hasPermi('price:range:import')")
  public org.springframework.http.ResponseEntity<byte[]> importOriginalFile(@PathVariable long id) {
    var original=importHistory.original(com.sanhua.marketingcost.security.BusinessUnitContext.getCurrentBusinessUnitType(),RANGE_ORIGINAL,id);
    return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)
        .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,org.springframework.http.ContentDisposition.attachment()
            .filename(original.fileName(),java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(original.bytes());
  }

  @PostMapping("/imports/{id}/void")
  @PreAuthorize("@ss.hasPermi('price:range:import')")
  @com.sanhua.marketingcost.annotation.OperationLog(module="区间价",operationType=com.sanhua.marketingcost.annotation.OperationType.UPDATE)
  public CommonResult<Void> voidImport(@PathVariable long id,java.security.Principal principal) {
    importHistory.withdraw(com.sanhua.marketingcost.security.BusinessUnitContext.getCurrentBusinessUnitType(),RANGE_ORIGINAL,id,principal.getName());
    return CommonResult.success(null);
  }

}
