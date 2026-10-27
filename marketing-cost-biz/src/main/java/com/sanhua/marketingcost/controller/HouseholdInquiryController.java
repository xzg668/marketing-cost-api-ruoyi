package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.annotation.*;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.pricing.household.*;
import static com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.*;
import java.io.IOException;
import java.security.Principal;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/household-inquiries")
public class HouseholdInquiryController {
  private final HouseholdInquiryService service;
  public HouseholdInquiryController(HouseholdInquiryService service) { this.service=service; }

  @PostMapping(value="/import-preview",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("@ss.hasPermi('price:household-inquiry:import')")
  public CommonResult<Preview> preview(@RequestParam MultipartFile file,@RequestParam String sourceMonth,
      Principal principal) throws IOException {
    if(file==null||file.isEmpty()||file.getSize()>HouseholdInquiryWorkbookParser.MAX_BYTES)throw new IllegalArgumentException("请上传非空且不超过10 MB的家用询价工作簿");
    return CommonResult.success(service.preview(unit(),actor(principal),file.getBytes(),file.getOriginalFilename(),sourceMonth));
  }
  @PostMapping("/import-publish")
  @PreAuthorize("@ss.hasPermi('price:household-inquiry:import')")
  @OperationLog(module="家用部件询价",operationType=OperationType.IMPORT)
  public CommonResult<PublishResult> publish(@RequestBody JsonNode body,Principal principal) {
    var keys=new HashSet<String>();if(body!=null&&body.isObject())body.fieldNames().forEachRemaining(keys::add);
    if(!keys.equals(Set.of("previewId","publishRequestId","previewFingerprint"))||!body.get("previewId").isTextual()||!body.get("publishRequestId").isTextual()||!body.get("previewFingerprint").isTextual())throw new IllegalArgumentException("发布只接受原预览返回的previewId、publishRequestId和previewFingerprint");
    return CommonResult.success(service.publish(unit(),actor(principal),new PublishRequest(body.get("previewId").textValue(),body.get("publishRequestId").textValue(),body.get("previewFingerprint").textValue())));
  }
  @GetMapping("/import-previews/{previewId}")
  @PreAuthorize("@ss.hasPermi('price:household-inquiry:import')")
  public CommonResult<Preview> preview(@PathVariable String previewId,Principal principal) { return CommonResult.success(service.preview(unit(),actor(principal),previewId)); }

  @PostMapping("/import-previews/{previewId}/cancel")
  @PreAuthorize("@ss.hasPermi('price:household-inquiry:import')")
  public CommonResult<Void> cancel(@PathVariable String previewId,Principal principal) { service.cancel(unit(),actor(principal),previewId);return CommonResult.success(null); }

  @GetMapping
  @PreAuthorize("@ss.hasPermi('price:household-inquiry:list')")
  public CommonResult<PricePage> current(@RequestParam(required=false) String materialCode,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return CommonResult.success(service.current(unit(),materialCode,page,size)); }

  @GetMapping("/history")
  @PreAuthorize("@ss.hasPermi('price:household-inquiry:query')")
  public CommonResult<PricePage> history(@RequestParam String materialCode,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) { return CommonResult.success(service.history(unit(),materialCode,page,size)); }

  @GetMapping("/{id}")
  @PreAuthorize("@ss.hasPermi('price:household-inquiry:query')")
  public CommonResult<PriceView> detail(@PathVariable long id) { return CommonResult.success(service.price(unit(),id)); }

  @GetMapping("/{id}/financial-reference")
  @PreAuthorize("@ss.hasPermi('price:household-inquiry:download')")
  public CommonResult<FinancialView> financial(@PathVariable long id) { return CommonResult.success(service.financial(unit(),id)); }

  @GetMapping("/{id}/original")
  @PreAuthorize("@ss.hasPermi('price:household-inquiry:download')")
  public ResponseEntity<byte[]> original(@PathVariable long id) { return file(service.original(unit(),id)); }

  @GetMapping("/imports/{batchId}")
  @PreAuthorize("@ss.hasPermi('price:household-inquiry:download')")
  public CommonResult<ImportView> importDetails(@PathVariable long batchId,Principal principal) { return CommonResult.success(service.importDetails(unit(),actor(principal),batchId)); }

  @GetMapping("/imports/{batchId}/original")
  @PreAuthorize("@ss.hasPermi('price:household-inquiry:download')")
  public ResponseEntity<byte[]> importOriginal(@PathVariable long batchId,Principal principal) { return file(service.importOriginal(unit(),actor(principal),batchId)); }

  @ExceptionHandler(HouseholdInquiryFailure.class)
  public ResponseEntity<CommonResult<Void>> failure(HouseholdInquiryFailure failure) {
    return ResponseEntity.status(failure.status()).body(CommonResult.error(failure.status(),failure.code()+": "+failure.getMessage()));
  }
  private static ResponseEntity<byte[]> file(OriginalFile file) {
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.fileName(),java.nio.charset.StandardCharsets.UTF_8).build().toString())
        .header("X-File-SHA256",file.sha256()).body(file.bytes());
  }
  private static String unit(){return BusinessUnitContext.getCurrentBusinessUnitType();}
  private static String actor(Principal principal) { if(principal==null||principal.getName()==null||principal.getName().isBlank())throw new AccessDeniedException("请使用有效认证账号");return principal.getName(); }
}
