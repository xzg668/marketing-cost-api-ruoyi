package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryCmsSource;
import java.util.List;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalarySaveRequest;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataSalaryApplicationService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskException;
import java.util.function.Supplier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryUploadResponse;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataSalaryUploadParser;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataAttachmentStore;

@RestController
@RequestMapping("/api/v2/technical-data/products/{productId}/salary")
public class TechnicalDataSalaryController {
  private static final String READ_PERMISSION =
      "@ss.hasAnyPermi('technical:data:task:list','technical:data:task:edit',"
          + "'technical:data:admin:operate','ingest:quote:cost-run:execute')";
  private static final String EDIT_PERMISSION =
      "@ss.hasAnyPermi('technical:data:task:edit','technical:data:admin:operate',"
          + "'ingest:quote:cost-run:execute')";

  private final TechnicalDataSalaryApplicationService applicationService;
  private final TechnicalDataActorProvider actorProvider;

  public TechnicalDataSalaryController(
      TechnicalDataSalaryApplicationService applicationService,
      TechnicalDataActorProvider actorProvider) {
    this.applicationService = applicationService;
    this.actorProvider = actorProvider;
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping
  public CommonResult<TechnicalDataSalaryResponse> get(@PathVariable Long productId, @RequestParam(required = false) Long versionId) {
    return execute(() -> applicationService.get(productId, versionId, actorProvider.current()));
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/references")
  public CommonResult<List<TechnicalDataSalaryCmsSource>> references(
      @PathVariable Long productId, @RequestParam String keyword,
      @RequestParam(defaultValue = "REFERENCE") String entryMode) {
    return execute(() -> applicationService.references(productId, keyword, entryMode, actorProvider.current()));
  }

  @PreAuthorize(EDIT_PERMISSION)
  @PostMapping(value = "/upload-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public CommonResult<TechnicalDataSalaryUploadResponse> preview(@PathVariable Long productId, @RequestParam MultipartFile file) {
    return execute(() -> {
      if (file.isEmpty() || file.getSize() > TechnicalDataSalaryUploadParser.MAX_BYTES) throw new IllegalArgumentException("工时文件不能为空，且不能超过 5 MB");
      try { return applicationService.preview(productId, file.getOriginalFilename(), file.getBytes(), actorProvider.current()); }
      catch (IOException exception) { throw new IllegalArgumentException("无法读取工时上传文件", exception); }
    });
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/template")
  public ResponseEntity<?> template(@PathVariable Long productId) {
    return download(() -> {
      applicationService.get(productId, null, actorProvider.current());
      try { return new TechnicalDataAttachmentStore.StoredFile("工时模板2026第一版.xlsx", null,
          new ClassPathResource("templates/technical-data/salary.xlsx").getContentAsByteArray()); }
      catch (IOException exception) { throw new IllegalStateException("工时模板无法读取", exception); }
    });
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/file")
  public ResponseEntity<?> file(@PathVariable Long productId, @RequestParam(required = false) Long versionId) {
    return download(() -> applicationService.file(productId, versionId, actorProvider.current()));
  }

  @PreAuthorize(EDIT_PERMISSION)
  @PutMapping
  public CommonResult<TechnicalDataSalaryResponse> save(
      @PathVariable Long productId, @RequestBody TechnicalDataSalarySaveRequest request) {
    return execute(() -> applicationService.save(productId, request, actorProvider.current()));
  }

  private ResponseEntity<?> download(Supplier<TechnicalDataAttachmentStore.StoredFile> supplier) {
    var result = execute(supplier);
    if (result.getCode() != 0) return ResponseEntity.status(result.getCode()).body(result);
    var file = result.getData();
    return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
        .body(file.bytes());
  }

  private static <T> CommonResult<T> execute(Supplier<T> supplier) {
    try {
      return CommonResult.success(supplier.get());
    } catch (TechnicalDataTaskException exception) {
      int code = switch (exception.code()) {
        case TASK_NOT_FOUND, PRODUCT_NOT_FOUND -> GlobalErrorCodeConstants.NOT_FOUND.getCode();
        case FORBIDDEN -> GlobalErrorCodeConstants.FORBIDDEN.getCode();
        case VERSION_CONFLICT, ACTIVE_PRODUCT_CONFLICT, SHARED_MODULE_CONFLICT,
            PERSISTENCE_CONFLICT -> 409;
        case INVALID_REQUEST -> GlobalErrorCodeConstants.BAD_REQUEST.getCode();
      };
      return CommonResult.error(code, exception.code().name() + ": " + exception.getMessage());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), exception.getMessage());
    }
  }
}
