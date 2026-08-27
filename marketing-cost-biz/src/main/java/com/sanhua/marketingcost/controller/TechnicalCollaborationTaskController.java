package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.collaboration.TechnicalTaskActionRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalTaskChangeLogResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalTaskDetailResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalTaskListResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalTaskValidationResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomCandidateSearchResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomDraftRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomDraftResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomReferenceRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomWorkspaceResponse;
import com.sanhua.marketingcost.dto.collaboration.ElectronicBomVerifyRequest;
import com.sanhua.marketingcost.dto.collaboration.ElectronicBomVerificationResponse;
import com.sanhua.marketingcost.dto.collaboration.ElectronicDrawingBomImportResponse;
import com.sanhua.marketingcost.dto.collaboration.ElectronicDrawingMaterialMappingRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackageCopyRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackageDraftRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackagePriceCheckResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackageSearchResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackageWorkspaceResponse;
import com.sanhua.marketingcost.service.collaboration.CollaborationDomainException;
import com.sanhua.marketingcost.service.collaboration.TechnicalBomDraftApplicationService;
import com.sanhua.marketingcost.service.collaboration.TechnicalBomTemplateService;
import com.sanhua.marketingcost.service.collaboration.ElectronicBomVerificationService;
import com.sanhua.marketingcost.service.collaboration.ElectronicDrawingBomImportService;
import com.sanhua.marketingcost.service.collaboration.ElectronicDrawingMaterialMatcher.Option;
import com.sanhua.marketingcost.service.collaboration.TechnicalPackageDraftApplicationService;
import com.sanhua.marketingcost.service.collaboration.TechnicalTaskApplicationService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/collaboration/product-tasks")
public class TechnicalCollaborationTaskController {
  private final TechnicalTaskApplicationService applicationService;
  private final TechnicalBomDraftApplicationService bomDraftService;
  private final TechnicalBomTemplateService bomTemplateService;
  private final ElectronicBomVerificationService electronicBomVerificationService;
  private final ElectronicDrawingBomImportService electronicDrawingBomImportService;
  private final TechnicalPackageDraftApplicationService packageDraftService;

  public TechnicalCollaborationTaskController(
      TechnicalTaskApplicationService applicationService,
      TechnicalBomDraftApplicationService bomDraftService,
      TechnicalBomTemplateService bomTemplateService,
      ElectronicBomVerificationService electronicBomVerificationService,
      ElectronicDrawingBomImportService electronicDrawingBomImportService,
      TechnicalPackageDraftApplicationService packageDraftService) {
    this.applicationService = applicationService;
    this.bomDraftService = bomDraftService;
    this.bomTemplateService = bomTemplateService;
    this.electronicBomVerificationService = electronicBomVerificationService;
    this.electronicDrawingBomImportService = electronicDrawingBomImportService;
    this.packageDraftService = packageDraftService;
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:read')")
  @GetMapping("/mine")
  public CommonResult<TechnicalTaskListResponse> mine() {
    return execute(applicationService::mine);
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:read')")
  @GetMapping("/{taskId}")
  public CommonResult<TechnicalTaskDetailResponse> detail(@PathVariable Long taskId) {
    return execute(() -> applicationService.detail(taskId));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PostMapping("/{taskId}/start")
  public CommonResult<TechnicalTaskDetailResponse> start(
      @PathVariable Long taskId, @RequestBody TechnicalTaskActionRequest request) {
    return execute(() -> applicationService.start(taskId, version(request)));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PostMapping("/{taskId}/validate")
  public CommonResult<TechnicalTaskValidationResponse> validate(
      @PathVariable Long taskId, @RequestBody TechnicalTaskActionRequest request) {
    return execute(() -> applicationService.validate(taskId, version(request)));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:submit')")
  @PostMapping("/{taskId}/submit")
  public CommonResult<TechnicalTaskDetailResponse> submit(
      @PathVariable Long taskId, @RequestBody TechnicalTaskActionRequest request) {
    return execute(() -> applicationService.submit(taskId, version(request)));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:read')")
  @GetMapping("/{taskId}/change-log")
  public CommonResult<TechnicalTaskChangeLogResponse> changeLog(
      @PathVariable Long taskId) {
    return execute(() -> applicationService.changeLog(taskId));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:read')")
  @GetMapping("/{taskId}/bom-workspace")
  public CommonResult<TechnicalBomWorkspaceResponse> bomWorkspace(@PathVariable Long taskId) {
    return execute(() -> bomDraftService.workspace(taskId));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:read')")
  @GetMapping("/{taskId}/bom-candidates")
  public CommonResult<TechnicalBomCandidateSearchResponse> bomCandidates(
      @PathVariable Long taskId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String spec,
      @RequestParam(required = false) String model) {
    return execute(() -> bomDraftService.search(taskId, keyword, spec, model));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:read')")
  @GetMapping("/{taskId}/bom-candidates/{productCode}")
  public CommonResult<TechnicalBomDraftResponse> bomCandidateTree(
      @PathVariable Long taskId,
      @PathVariable String productCode,
      @RequestParam(required = false) String bomPurpose) {
    return execute(() -> bomDraftService.candidateTree(taskId, productCode, bomPurpose));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PostMapping("/{taskId}/bom-draft/copy")
  public CommonResult<TechnicalBomDraftResponse> copyBomDraft(
      @PathVariable Long taskId, @RequestBody TechnicalBomReferenceRequest request) {
    return execute(() -> bomDraftService.copyReference(taskId, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PostMapping("/{taskId}/bom-draft/new")
  public CommonResult<TechnicalBomDraftResponse> newBomDraft(
      @PathVariable Long taskId, @RequestBody TechnicalBomReferenceRequest request) {
    return execute(() -> bomDraftService.createNew(taskId, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PostMapping("/{taskId}/bom-draft")
  public CommonResult<TechnicalBomDraftResponse> saveBomDraft(
      @PathVariable Long taskId, @RequestBody TechnicalBomDraftRequest request) {
    return execute(() -> bomDraftService.save(taskId, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @GetMapping("/{taskId}/bom-draft/export-electronic-template")
  public void exportElectronicTemplate(
      @PathVariable Long taskId,
      HttpServletResponse response) throws IOException {
    TechnicalBomTemplateService.TemplateFile file = bomTemplateService.export(taskId);
    response.setContentType(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
        + URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20"));
    response.setContentLength(file.bytes().length);
    response.getOutputStream().write(file.bytes());
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PostMapping("/{taskId}/electronic-bom/verify")
  public CommonResult<ElectronicBomVerificationResponse> verifyElectronicBom(
      @PathVariable Long taskId,
      @RequestBody ElectronicBomVerifyRequest request) {
    return execute(() -> electronicBomVerificationService.verify(taskId, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PostMapping(value = "/{taskId}/electronic-bom/import", consumes = "multipart/form-data")
  public CommonResult<ElectronicDrawingBomImportResponse> importElectronicBom(
      @PathVariable Long taskId,
      @RequestPart("file") MultipartFile file,
      @RequestParam Integer expectedVersion) throws IOException {
    byte[] bytes = file.getBytes();
    return execute(() -> electronicDrawingBomImportService.importFile(
        taskId, expectedVersion, file.getOriginalFilename(), bytes));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:read')")
  @GetMapping("/{taskId}/electronic-bom/import-result")
  public CommonResult<ElectronicDrawingBomImportResponse> electronicBomImportResult(
      @PathVariable Long taskId) {
    return execute(() -> electronicDrawingBomImportService.current(taskId));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PostMapping("/{taskId}/electronic-bom/import/confirm")
  public CommonResult<ElectronicBomVerificationResponse> confirmElectronicBomImport(
      @PathVariable Long taskId,
      @RequestBody ElectronicBomVerifyRequest request) {
    return execute(() -> electronicBomVerificationService.confirmImportedExcel(taskId, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:read')")
  @GetMapping("/{taskId}/electronic-bom/material-options")
  public CommonResult<java.util.List<Option>> electronicBomMaterialOptions(
      @PathVariable Long taskId,
      @RequestParam String keyword,
      @RequestParam(defaultValue = "30") int limit) {
    return execute(() -> electronicDrawingBomImportService.searchMaterialOptions(taskId, keyword, limit));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PutMapping("/{taskId}/electronic-bom/mappings")
  public CommonResult<ElectronicDrawingBomImportResponse> applyElectronicBomMappings(
      @PathVariable Long taskId,
      @RequestBody ElectronicDrawingMaterialMappingRequest request) {
    return execute(() -> electronicDrawingBomImportService.applyMappings(taskId, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:read')")
  @GetMapping("/{taskId}/package/reference-products")
  public CommonResult<TechnicalPackageSearchResponse> packageReferenceProducts(
      @PathVariable Long taskId,
      @RequestParam(required = false) String keyword) {
    return execute(() -> packageDraftService.searchReferenceProducts(taskId, keyword));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:read')")
  @GetMapping("/{taskId}/package/package-parents")
  public CommonResult<TechnicalPackageSearchResponse> packageParents(
      @PathVariable Long taskId,
      @RequestParam(required = false) String keyword) {
    return execute(() -> packageDraftService.searchPackageParents(taskId, keyword));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:read')")
  @GetMapping("/{taskId}/package-draft")
  public CommonResult<TechnicalPackageWorkspaceResponse> packageDraft(
      @PathVariable Long taskId) {
    return execute(() -> packageDraftService.workspace(taskId));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PostMapping("/{taskId}/package-draft/copy")
  public CommonResult<TechnicalPackageWorkspaceResponse> copyPackageDraft(
      @PathVariable Long taskId,
      @RequestBody TechnicalPackageCopyRequest request) {
    return execute(() -> packageDraftService.copy(taskId, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PutMapping("/{taskId}/package-draft")
  public CommonResult<TechnicalPackageWorkspaceResponse> savePackageDraft(
      @PathVariable Long taskId,
      @RequestBody TechnicalPackageDraftRequest request) {
    return execute(() -> packageDraftService.save(taskId, request));
  }

  @PreAuthorize("@ss.hasPermi('collaboration:task:edit')")
  @PostMapping("/{taskId}/package-draft/check-price")
  public CommonResult<TechnicalPackagePriceCheckResponse> checkPackagePrice(
      @PathVariable Long taskId,
      @RequestBody TechnicalTaskActionRequest request) {
    return execute(() -> packageDraftService.checkPrice(taskId, version(request)));
  }

  private static Integer version(TechnicalTaskActionRequest request) {
    if (request == null || request.expectedVersion() == null) {
      throw new IllegalArgumentException("expectedVersion不能为空");
    }
    return request.expectedVersion();
  }

  private static <T> CommonResult<T> execute(Supplier<T> supplier) {
    try {
      return CommonResult.success(supplier.get());
    } catch (CollaborationDomainException exception) {
      int code = switch (exception.code()) {
        case TASK_NOT_FOUND -> GlobalErrorCodeConstants.NOT_FOUND.getCode();
        case TASK_ASSIGNEE_MISMATCH -> GlobalErrorCodeConstants.FORBIDDEN.getCode();
        case TASK_VERSION_CONFLICT -> 409;
        default -> GlobalErrorCodeConstants.BAD_REQUEST.getCode();
      };
      return CommonResult.error(code, exception.code().name() + ": " + exception.getMessage());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), exception.getMessage());
    }
  }

  @FunctionalInterface
  private interface Supplier<T> { T get(); }
}
