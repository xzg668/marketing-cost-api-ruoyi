package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.dto.FinanceFactorImportPreview;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorCuAdjustmentService;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPublicationService;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorWorkbookExportService;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import com.sanhua.marketingcost.service.pricing.FactorPriceSnapshot;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.Principal;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/finance-factor-publications")
public class FinanceFactorPublicationController {
  private final FinanceFactorPublicationService service;
  private final FinanceFactorPriceReader reader;
  private final FinanceFactorCuAdjustmentService copper;
  private final FinanceFactorWorkbookExportService exports;

  public FinanceFactorPublicationController(
      FinanceFactorPublicationService service,
      FinanceFactorPriceReader reader,
      FinanceFactorCuAdjustmentService copper,
      FinanceFactorWorkbookExportService exports) {
    this.service = service;
    this.reader = reader;
    this.copper = copper;
    this.exports = exports;
  }

  @PreAuthorize("@ss.hasPermi('price:finance-base:import')")
  @GetMapping("/export-previous-month")
  public ResponseEntity<byte[]> exportPreviousMonth(@RequestParam String priceMonth, @RequestParam String priceOrgCode) {
    var file = exports.previousMonth(context(priceMonth, priceOrgCode, null, null));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
        .header("X-Finance-Source-Month", file.sourceMonth())
        .header("X-Finance-Revision", Integer.toString(file.revision()))
        .body(file.bytes());
  }

  @PreAuthorize("@ss.hasPermi('cost:finance-cu-base:edit')")
  @PostMapping("/cu/{monthlyPriceId}/preview")
  public CommonResult<CuPreview> previewCu(
      @PathVariable Long monthlyPriceId, @RequestBody CuAdjustment request) {
    var preview =
        copper.preview(
            monthlyPriceId,
            request.pricePerTon(),
            request.changeReason(),
            BusinessUnitContext.getCurrentBusinessUnitType());
    return CommonResult.success(new CuPreview(preview.previewFingerprint()));
  }

  @PreAuthorize("@ss.hasPermi('cost:finance-cu-base:edit')")
  @PostMapping("/cu/{monthlyPriceId}/publish")
  public CommonResult<FinanceFactorPublicationService.Publication> publishCu(
      @PathVariable Long monthlyPriceId, @RequestBody CuAdjustment request, Principal principal) {
    if (principal == null) throw new IllegalArgumentException("请登录后发布财务Cu调整");
    return CommonResult.success(
        copper.publish(
            monthlyPriceId,
            request.pricePerTon(),
            request.changeReason(),
            BusinessUnitContext.getCurrentBusinessUnitType(),
            request.previewFingerprint(),
            principal.getName()));
  }

  public record CuAdjustment(
      BigDecimal pricePerTon, String changeReason, String previewFingerprint) {}

  public record CuPreview(String previewFingerprint) {}

  @PreAuthorize("@ss.hasPermi('cost:finance-cu-base:query')")
  @GetMapping("/cu")
  public CommonResult<FactorPriceSnapshot> readCu(
      @RequestParam String priceMonth,
      @RequestParam String priceOrgCode,
      @RequestParam(required = false) String schemeCode,
      @RequestParam Condition condition,
      @RequestParam(required = false) Integer revision) {
    var snapshot =
        reader.read(context(priceMonth, priceOrgCode, null, schemeCode), condition, revision);
    return CommonResult.success(snapshot.copperOnly());
  }

  @PreAuthorize("@ss.hasPermi('price:finance-base:list')")
  @GetMapping
  public CommonResult<FactorPriceSnapshot> read(
      @RequestParam String priceMonth,
      @RequestParam String priceOrgCode,
      @RequestParam(required = false) String schemeCode,
      @RequestParam Condition condition,
      @RequestParam(required = false) Integer revision) {
    return CommonResult.success(
        reader.read(context(priceMonth, priceOrgCode, null, schemeCode), condition, revision));
  }

  @PreAuthorize("@ss.hasPermi('price:finance-base:import')")
  @PostMapping("/preview")
  public CommonResult<FinanceFactorImportPreview> preview(
      @RequestPart("file") MultipartFile file,
      @RequestParam String priceMonth,
      @RequestParam String priceOrgCode,
      @RequestParam(required = false) String sheetName,
      @RequestParam(required = false) String defaultSchemeCode)
      throws IOException {
    return CommonResult.success(
        service.preview(
            file.getBytes(),
            file.getOriginalFilename(),
            context(priceMonth, priceOrgCode, sheetName, defaultSchemeCode)));
  }

  @PreAuthorize("@ss.hasPermi('price:finance-base:import')")
  @PostMapping("/publish")
  public CommonResult<FinanceFactorPublicationService.Publication> publish(
      @RequestPart("file") MultipartFile file,
      @RequestParam String priceMonth,
      @RequestParam String priceOrgCode,
      @RequestParam(required = false) String sheetName,
      @RequestParam(required = false) String defaultSchemeCode,
      @RequestParam String previewFingerprint,
      Principal principal)
      throws IOException {
    if (principal == null) throw new IllegalArgumentException("请登录后发布财务基价");
    return CommonResult.success(
        service.publish(
            file.getBytes(),
            file.getOriginalFilename(),
            context(priceMonth, priceOrgCode, sheetName, defaultSchemeCode),
            previewFingerprint,
            principal.getName()));
  }

  private FinanceFactorImportContext context(
      String month, String org, String sheet, String scheme) {
    return new FinanceFactorImportContext(
        BusinessUnitContext.getCurrentBusinessUnitType(), org, month, sheet, scheme);
  }
}
