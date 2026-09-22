package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.technicaldata.AuxiliaryClassificationResponse;
import com.sanhua.marketingcost.dto.technicaldata.AuxiliaryClassificationResponse.Preview;
import com.sanhua.marketingcost.service.technicaldata.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Supplier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}")
public class QuoteAuxiliaryClassificationController {
  private final TechnicalDataAuxiliaryClassificationService service;
  private final TechnicalDataAuxiliaryClassificationWorkbook workbook;
  private final TechnicalDataActorProvider actors;
  public QuoteAuxiliaryClassificationController(TechnicalDataAuxiliaryClassificationService service,
      TechnicalDataAuxiliaryClassificationWorkbook workbook, TechnicalDataActorProvider actors) {
    this.service=service; this.workbook=workbook; this.actors=actors;
  }
  @GetMapping("/auxiliary-classifications")
  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list','ingest:quote:cost-run:execute')")
  public CommonResult<java.util.List<AuxiliaryClassificationResponse>> list(@PathVariable String oaNo,@RequestParam(required=false) String accountingMonth) {
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.normalizePricingMonth(accountingMonth);
    return execute(() -> service.list(oaNo,month,actors.current()));
  }
  @GetMapping("/items/{itemId}/auxiliary-classification")
  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list','ingest:quote:cost-run:execute')")
  public CommonResult<AuxiliaryClassificationResponse> get(@PathVariable String oaNo,@PathVariable Long itemId,@RequestParam String accountingMonth) {
    return execute(() -> service.get(oaNo,itemId,accountingMonth,actors.current()));
  }
  @GetMapping("/items/{itemId}/auxiliary-classification/export")
  @PreAuthorize("@ss.hasPermi('ingest:quote:cost-run:execute')")
  public void export(@PathVariable String oaNo,@PathVariable Long itemId,@RequestParam String accountingMonth,HttpServletResponse response) throws IOException {
    byte[] bytes=workbook.export(service.get(oaNo,itemId,accountingMonth,actors.current()));
    response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition","attachment; filename=auxiliary-classification-"+itemId+".xlsx");
    response.getOutputStream().write(bytes);
  }
  @PostMapping("/items/{itemId}/auxiliary-classification/preview")
  @PreAuthorize("@ss.hasPermi('ingest:quote:cost-run:execute')")
  public CommonResult<Preview> preview(@PathVariable String oaNo,@PathVariable Long itemId,@RequestParam String accountingMonth,@RequestParam MultipartFile file) throws IOException {
    byte[] bytes=file.getBytes();
    return execute(() -> service.preview(oaNo,itemId,accountingMonth,workbook.parse(bytes),actors.current()));
  }
  @PostMapping("/items/{itemId}/auxiliary-classification/confirm")
  @PreAuthorize("@ss.hasPermi('ingest:quote:cost-run:execute')")
  public CommonResult<AuxiliaryClassificationResponse> confirm(@PathVariable String oaNo,@PathVariable Long itemId,@RequestParam String accountingMonth,
      @RequestParam String previewFingerprint,@RequestParam MultipartFile file) throws IOException {
    byte[] bytes=file.getBytes();
    return execute(() -> service.confirm(oaNo,itemId,accountingMonth,workbook.parse(bytes),previewFingerprint,actors.current()));
  }
  private <T> CommonResult<T> execute(Supplier<T> operation) {
    try { return CommonResult.success(operation.get()); }
    catch (TechnicalDataTaskException error) { return CommonResult.error(error.code()==TechnicalDataTaskErrorCode.FORBIDDEN?403:409,error.getMessage()); }
    catch (IllegalArgumentException error) { return CommonResult.error(400,error.getMessage()); }
  }
}
