package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceType;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import com.sanhua.marketingcost.service.settlement.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/settlement-prices")
public class SettlementPriceController {
  private final SettlementPriceRepository prices;
  private final SettlementPriceImportService imports;
  private final SettlementPriceQuery query;

  public SettlementPriceController(SettlementPriceRepository prices, SettlementPriceImportService imports,
      SettlementPriceQuery query) {
    this.prices = prices;
    this.imports = imports;
    this.query = query;
  }

  @GetMapping("/pricing")
  @PreAuthorize("@ss.hasPermi('price:settlement:list')")
  public CommonResult<List<SettlementPriceQuery.Entry>> pricing(@RequestParam String priceMonth,
      @RequestParam(required = false) String materialCode, @RequestParam(required = false) SettlementPriceType priceType) {
    return CommonResult.success(query.list(new SettlementScope(unit(), priceMonth), materialCode, priceType));
  }

  @GetMapping
  @PreAuthorize("@ss.hasPermi('price:settlement:list')")
  public CommonResult<List<SettlementPriceRecord>> list(@RequestParam String priceMonth,
      @RequestParam(required = false) String materialCode, @RequestParam(required = false) SettlementPriceType priceType) {
    return CommonResult.success(prices.effective(new SettlementScope(unit(), priceMonth), materialCode, priceType));
  }

  @GetMapping("/{id}")
  @PreAuthorize("@ss.hasPermi('price:settlement:list')")
  public CommonResult<SettlementPriceRecord> detail(@PathVariable long id) { return CommonResult.success(row(id)); }

  @GetMapping("/{id}/history")
  @PreAuthorize("@ss.hasPermi('price:settlement:list')")
  public CommonResult<List<SettlementPriceRecord>> history(@PathVariable long id) {
    var row = row(id);
    return CommonResult.success(prices.history(new SettlementScope(unit(), row.getPriceMonth()), row.getMaterialCode(), row.getPriceType()));
  }

  @GetMapping("/{id}/effective-history")
  @PreAuthorize("@ss.hasPermi('price:settlement:list')")
  public CommonResult<List<SettlementPriceQuery.MonthlyRevision>> effectiveHistory(@PathVariable long id,
      @RequestParam String priceMonth) {
    return CommonResult.success(query.history(new SettlementScope(unit(), priceMonth), id));
  }

  @GetMapping("/{id}/original")
  @PreAuthorize("@ss.hasPermi('price:settlement:list')")
  public ResponseEntity<byte[]> original(@PathVariable long id) {
    var original = imports.original(unit(), id);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(original.fileName(), StandardCharsets.UTF_8).build().toString())
        .body(original.bytes());
  }

  @PostMapping("/import/preview")
  @PreAuthorize("@ss.hasPermi('price:settlement:import')")
  public CommonResult<SettlementPriceImportService.Preview> preview(@RequestPart("file") MultipartFile file,
      @RequestParam String priceMonth) throws IOException {
    return CommonResult.success(imports.preview(file.getBytes(), file.getOriginalFilename(), new SettlementScope(unit(), priceMonth)));
  }

  @PostMapping("/import")
  @PreAuthorize("@ss.hasPermi('price:settlement:import')")
  @OperationLog(module = "结算价格", operationType = OperationType.IMPORT)
  public CommonResult<SettlementPriceImportService.ImportResult> confirm(@RequestPart("file") MultipartFile file,
      @RequestParam String priceMonth, @RequestParam String previewFingerprint, Principal principal) throws IOException {
    return CommonResult.success(imports.confirm(file.getBytes(), file.getOriginalFilename(),
        new SettlementScope(unit(), priceMonth), previewFingerprint, actor(principal)));
  }

  @ExceptionHandler(OptimisticLockingFailureException.class)
  public ResponseEntity<CommonResult<Void>> conflict(OptimisticLockingFailureException conflict) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(CommonResult.error(409, conflict.getMessage()));
  }

  private SettlementPriceRecord row(long id) {
    return prices.byId(unit(), id).orElseThrow(() -> new IllegalArgumentException("结算价格不存在"));
  }
  private String unit() { return BusinessUnitContext.getCurrentBusinessUnitType(); }
  private String actor(Principal principal) {
    if (principal == null) throw new IllegalArgumentException("请登录后维护结算价格");
    return principal.getName();
  }
}
