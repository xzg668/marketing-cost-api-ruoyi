package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.annotation.*;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.pricing.invoice.*;
import java.security.Principal;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/invoice-prices")
public class InvoicePriceController {
  private final InvoicePriceService service;
  public InvoicePriceController(InvoicePriceService service) { this.service=service; }
  @PostMapping(value="/preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("@ss.hasPermi('price:invoice:import')")
  public CommonResult<InvoiceWorkbookParser.Parsed> preview(@RequestParam MultipartFile file) throws java.io.IOException {
    return CommonResult.success(service.preview(bytes(file)));
  }
  @PostMapping(value="/import",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("@ss.hasPermi('price:invoice:import')")
  @OperationLog(module="发票价格",operationType=OperationType.IMPORT)
  public CommonResult<InvoicePriceService.Published> publish(@RequestParam MultipartFile file,@RequestParam String orgCode,
      @RequestParam String requestId,Principal principal) throws java.io.IOException {
    return CommonResult.success(service.publish(unit(),orgCode,principal.getName(),requestId,file.getOriginalFilename(),bytes(file)));
  }
  @GetMapping
  @PreAuthorize("@ss.hasPermi('price:invoice:list')")
  public CommonResult<InvoicePriceService.Page> current(@RequestParam String orgCode,@RequestParam(required=false) String materialCode,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
    return CommonResult.success(service.current(unit(),orgCode,materialCode,page,size));
  }
  @GetMapping("/history")
  @PreAuthorize("@ss.hasPermi('price:invoice:query')")
  public CommonResult<InvoicePriceService.Page> history(@RequestParam String orgCode,@RequestParam(required=false) String materialCode,
      @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
    return CommonResult.success(service.history(unit(),orgCode,materialCode,page,size));
  }
  @GetMapping("/imports/{id}")
  @PreAuthorize("@ss.hasPermi('price:invoice:query')")
  public CommonResult<List<InvoiceWorkbookParser.Price>> batch(@PathVariable long id) { return CommonResult.success(service.batchRows(unit(),id)); }
  @GetMapping("/imports/{id}/original")
  @PreAuthorize("@ss.hasPermi('price:invoice:download')")
  public ResponseEntity<byte[]> original(@PathVariable long id) {
    var original=service.original(unit(),id);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(original.name(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
        .body(original.bytes());
  }
  private static String unit(){return BusinessUnitContext.getCurrentBusinessUnitType();}
  private static byte[] bytes(MultipartFile file) throws java.io.IOException {
    if(file==null||file.isEmpty()||file.getSize()>InvoiceWorkbookParser.MAX_BYTES)throw new IllegalArgumentException("请上传非空且不超过10 MB的发票工作簿");
    return file.getBytes();
  }
}
