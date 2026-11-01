package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.annotation.*;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.pricing.inquiry.*;
import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/inquiry-prices")
public class InquiryPriceController {
  private final InquiryPriceService service;

  /** Preserve the source lookup's 404 rather than the application's generic 500 fallback. */
  @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
  public ResponseEntity<CommonResult<Void>> sourceNotFound(
      org.springframework.web.server.ResponseStatusException missing) {
    return ResponseEntity.status(missing.getStatusCode())
        .body(CommonResult.error(missing.getStatusCode().value(), missing.getReason()));
  }

  public InquiryPriceController(InquiryPriceService service) {
    this.service = service;
  }

  @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("@ss.hasPermi('price:inquiry:import')")
  public CommonResult<InquiryWorkbookParser.Parsed> preview(
      @RequestParam MultipartFile file, @RequestParam String priceMonth)
      throws java.io.IOException {
    return CommonResult.success(
        service.preview(unit(), priceMonth, file.getOriginalFilename(), bytes(file)));
  }

  @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("@ss.hasPermi('price:inquiry:import')")
  @OperationLog(module = "询价", operationType = OperationType.IMPORT)
  public CommonResult<InquiryPriceService.Published> publish(
      @RequestParam MultipartFile file,
      @RequestParam String priceMonth,
      @RequestParam String requestId,
      Principal principal)
      throws java.io.IOException {
    return CommonResult.success(
        service.publish(
            unit(),
            priceMonth,
            principal.getName(),
            requestId,
            file.getOriginalFilename(),
            bytes(file)));
  }

  @GetMapping
  @PreAuthorize("@ss.hasPermi('price:inquiry:list')")
  public CommonResult<InquiryPriceService.Page<InquiryPriceService.Current>> current(
      @RequestParam String priceMonth,
      @RequestParam(required = false) String search,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(service.current(unit(), priceMonth, search, page, size));
  }

  @GetMapping("/history")
  @PreAuthorize("@ss.hasPermi('price:inquiry:query')")
  public CommonResult<InquiryPriceService.Page<Map<String, Object>>> history(
      @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
    return CommonResult.success(service.history(unit(), page, size));
  }

  @GetMapping("/imports/{id}")
  @PreAuthorize("@ss.hasPermi('price:inquiry:query')")
  public CommonResult<List<InquiryPriceRepository.Source>> batch(@PathVariable long id) {
    return CommonResult.success(service.batch(unit(), id));
  }

  @GetMapping("/imports/{id}/original")
  @PreAuthorize("@ss.hasPermi('price:inquiry:download')")
  public ResponseEntity<byte[]> original(@PathVariable long id) {
    var original = service.original(unit(), id);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(original.name(), java.nio.charset.StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(original.bytes());
  }

  private static String unit() {
    return BusinessUnitContext.getCurrentBusinessUnitType();
  }

  private static byte[] bytes(MultipartFile file) throws java.io.IOException {
    if (file == null || file.isEmpty() || file.getSize() > InquiryWorkbookParser.MAX_BYTES)
      throw new IllegalArgumentException("请上传非空且不超过10 MB的询价工作簿");
    return file.getBytes();
  }
}
