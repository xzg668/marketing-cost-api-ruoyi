package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.service.technicaldata.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v2/technical-data/products/{productId}/auxiliary")
public class TechnicalDataAuxiliaryController {
  private static final String READ_PERMISSION =
      "@ss.hasAnyPermi('technical:data:task:list','technical:data:task:edit',"
          + "'technical:data:admin:operate','ingest:quote:cost-run:execute')";
  private static final String EDIT_PERMISSION =
      "@ss.hasAnyPermi('technical:data:task:edit','technical:data:admin:operate',"
          + "'ingest:quote:cost-run:execute')";
  private final TechnicalDataAuxiliaryApplicationService applicationService;
  private final TechnicalDataActorProvider actorProvider;

  public TechnicalDataAuxiliaryController(TechnicalDataAuxiliaryApplicationService applicationService, TechnicalDataActorProvider actorProvider) {
    this.applicationService = applicationService; this.actorProvider = actorProvider;
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping
  public CommonResult<TechnicalDataAuxiliaryResponse> get(@PathVariable Long productId, @RequestParam(required = false) Long versionId) {
    return execute(() -> applicationService.get(productId, versionId, actorProvider.current()));
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/references")
  public CommonResult<List<TechnicalDataAuxiliaryCmsSource>> references(@PathVariable Long productId, @RequestParam String keyword) {
    return execute(() -> applicationService.references(productId, keyword, actorProvider.current()));
  }

  @PreAuthorize(EDIT_PERMISSION)
  @PostMapping(value = "/upload-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public CommonResult<TechnicalDataAuxiliaryUploadResponse> preview(@PathVariable Long productId, @RequestParam MultipartFile file) {
    return execute(() -> {
      if (file.isEmpty() || file.getSize() > TechnicalDataAuxiliaryUploadParser.MAX_BYTES) throw new IllegalArgumentException("辅料文件不能为空，且不能超过 5 MB");
      try { return applicationService.preview(productId, file.getOriginalFilename(), file.getBytes(), actorProvider.current()); }
      catch (IOException exception) { throw new IllegalArgumentException("无法读取辅料上传文件", exception); }
    });
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/template")
  public ResponseEntity<?> template(@PathVariable Long productId) {
    return download(() -> {
      applicationService.get(productId, null, actorProvider.current());
      try { return new TechnicalDataAttachmentStore.StoredFile("辅料模板_含二级科目.xlsx", null,
          new ClassPathResource("templates/technical-data/auxiliary.xlsx").getContentAsByteArray()); }
      catch (IOException exception) { throw new IllegalStateException("辅料模板无法读取", exception); }
    });
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/file")
  public ResponseEntity<?> file(@PathVariable Long productId, @RequestParam(required = false) Long versionId) {
    return download(() -> applicationService.file(productId, versionId, actorProvider.current()));
  }

  @PreAuthorize(EDIT_PERMISSION)
  @PutMapping
  public CommonResult<TechnicalDataAuxiliaryResponse> save(@PathVariable Long productId, @RequestBody TechnicalDataAuxiliarySaveRequest request) {
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
