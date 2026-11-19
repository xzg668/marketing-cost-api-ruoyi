package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.service.technicaldata.TechnicalBomApplicationService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalBomReferenceQuery;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskException;
import com.sanhua.marketingcost.service.technicaldata.TechnicalBomWorkbook;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalBomSaveRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** 试查与技术员相同的参考来源，不创建任务，也不保存或提交资料。 */
@RestController
@RequestMapping("/api/v2/technical-data/quote-items/{itemId}/bom-preview")
@PreAuthorize("@ss.hasRole('admin') or @ss.hasAnyPermi('technical:data:admin:operate')")
public class TechnicalBomPreviewController {
  private final TechnicalBomApplicationService service;
  private final TechnicalDataActorProvider actors;

  public TechnicalBomPreviewController(TechnicalBomApplicationService service, TechnicalDataActorProvider actors) {
    this.service = service;
    this.actors = actors;
  }

  @GetMapping("/references")
  public CommonResult<List<TechnicalBomReferenceQuery.Candidate>> search(
      @PathVariable Long itemId, @RequestParam String accountingMonth, @RequestParam String keyword) {
    return execute(() -> service.searchForQuote(itemId, accountingMonth, keyword, actors.current()));
  }

  @GetMapping("/source")
  @PreAuthorize("@ss.hasRole('admin') or @ss.hasAnyPermi('technical:data:admin:operate','ingest:quote:cost-run:execute')")
  public CommonResult<TechnicalBomApplicationService.Response> source(
      @PathVariable Long itemId, @RequestParam String accountingMonth) {
    return execute(() -> service.sourceForQuote(itemId, accountingMonth, actors.current()));
  }

  @GetMapping("/reference")
  public CommonResult<TechnicalBomApplicationService.Preview> reference(
      @PathVariable Long itemId, @RequestParam String accountingMonth, @RequestParam String code) {
    return execute(() -> service.referenceForQuote(itemId, accountingMonth, code, actors.current()));
  }

  @GetMapping("/replacement-options")
  public CommonResult<List<TechnicalBomReferenceQuery.Candidate>> replacementOptions(
      @PathVariable Long itemId,@RequestParam String accountingMonth,@RequestParam String keyword) {
    return execute(()->service.searchReplacementForQuote(itemId,accountingMonth,keyword,actors.current()));
  }

  @PostMapping("/check-reference")
  public CommonResult<TechnicalBomApplicationService.Preview> checkReference(@PathVariable Long itemId,
      @RequestParam String accountingMonth, @RequestBody TechnicalBomSaveRequest request) {
    return execute(() -> service.checkReferenceForQuote(itemId, accountingMonth, request, actors.current()));
  }

  @PostMapping(value="/upload", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
  public CommonResult<TechnicalBomApplicationService.Preview> upload(@PathVariable Long itemId,
      @RequestParam String accountingMonth, @RequestParam MultipartFile file) {
    return execute(() -> {
      if (file.isEmpty() || file.getSize() > TechnicalBomWorkbook.MAX_BYTES)
        throw new IllegalArgumentException("BOM文件不能为空且不能超过5MB");
      try { return service.previewUploadForQuote(itemId, accountingMonth, file.getOriginalFilename(), file.getBytes(), actors.current()); }
      catch (IOException error) { throw new IllegalArgumentException("无法读取BOM文件", error); }
    });
  }

  @GetMapping("/template")
  public ResponseEntity<?> template(@PathVariable Long itemId, @RequestParam String accountingMonth, @RequestParam String exampleCode) {
    var result=execute(() -> service.templateForQuote(itemId, accountingMonth, exampleCode, actors.current()));
    if (result.getCode()!=0) return ResponseEntity.status(result.getCode()).body(result);
    var file=result.getData();
    return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.fileName(),StandardCharsets.UTF_8).build().toString())
        .body(file.bytes());
  }

  private static <T> CommonResult<T> execute(Supplier<T> action) {
    try { return CommonResult.success(action.get()); }
    catch (TechnicalDataTaskException error) {
      return CommonResult.error(switch (error.code()) {
        case FORBIDDEN -> 403;
        case PRODUCT_NOT_FOUND, TASK_NOT_FOUND -> 404;
        case INVALID_REQUEST -> 400;
        default -> 409;
      }, error.getMessage());
    } catch (IllegalArgumentException error) {
      return CommonResult.error(400, error.getMessage());
    }
  }
}
