package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.pricing.folder.MonthlyPriceFolderModel.Report;
import com.sanhua.marketingcost.service.pricing.folder.MonthlyPriceFolderService;
import java.util.List;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/monthly-reprice/price-folders")
@PreAuthorize("@ss.hasPermi('price:linked:import') and @ss.hasPermi('price:range:import')")
public class MonthlyPriceFolderController {
  private final MonthlyPriceFolderService service;
  public MonthlyPriceFolderController(MonthlyPriceFolderService service) { this.service = service; }
  public record Start(String pricingMonth, String requestKey, List<String> paths) {}
  public record Revision(long revision) {}
  public record Added(long revision, List<String> paths) {}
  public record Failed(String message) {}
  @PostMapping
  public CommonResult<Report> start(@RequestBody Start request) {
    return CommonResult.success(service.start(request.pricingMonth(), request.requestKey(), request.paths()));
  }
  @GetMapping("/latest")
  public CommonResult<Report> latest(@RequestParam String pricingMonth) { return CommonResult.success(service.latest(pricingMonth)); }
  @GetMapping("/{id}")
  public CommonResult<Report> report(@PathVariable long id) { return CommonResult.success(service.report(id)); }
  @PostMapping("/{id}/files")
  public CommonResult<Report> add(@PathVariable long id, @RequestBody Added request) {
    return CommonResult.success(service.addFiles(id, request.revision(), request.paths()));
  }
  @PostMapping(value = "/{id}/files/{fileId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public CommonResult<MonthlyPriceFolderService.UploadResult> upload(@PathVariable long id,
      @PathVariable long fileId, @RequestParam MultipartFile file) throws java.io.IOException {
    return CommonResult.success(service.upload(id, fileId, file.getBytes()));
  }
  @PostMapping("/{id}/files/{fileId}/upload-failed")
  public CommonResult<Boolean> failed(@PathVariable long id, @PathVariable long fileId, @RequestBody Failed request) {
    service.uploadFailed(id, fileId, request.message()); return CommonResult.success(true);
  }
  @PostMapping("/{id}/files/{fileId}/process")
  public CommonResult<Report> process(@PathVariable long id, @PathVariable long fileId, @RequestBody Revision request) {
    return CommonResult.success(service.process(id, fileId, request.revision()));
  }
  @PostMapping("/{id}/complete")
  public CommonResult<Report> complete(@PathVariable long id, @RequestBody Revision request) {
    return CommonResult.success(service.complete(id, request.revision()));
  }
  @GetMapping("/{id}/files/{fileId}/original")
  public ResponseEntity<byte[]> original(@PathVariable long id, @PathVariable long fileId) {
    var file = service.download(id, fileId);
    return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(file.fileName(), java.nio.charset.StandardCharsets.UTF_8).build().toString()).body(file.bytes());
  }
}
