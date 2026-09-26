package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPublishRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPublishResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskValidationResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataWorkbenchPageResponse;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskApplicationService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskException;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataWorkflowService;
import java.util.function.Supplier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/v2/technical-data")
public class TechnicalDataTaskController {
  private static final String READ_PERMISSION =
      "@ss.hasAnyPermi('technical:data:task:list','technical:data:task:edit',"
          + "'technical:data:admin:operate','ingest:quote:cost-run:execute')";
  private static final String PUBLISH_PERMISSION =
      "@ss.hasAnyPermi('technical:data:admin:operate',"
          + "'ingest:quote:cost-run:execute')";

  private final TechnicalDataTaskApplicationService applicationService;
  private final com.sanhua.marketingcost.service.technicaldata.TechnicalDataSubmissionValidationService validation;
  private final com.sanhua.marketingcost.service.technicaldata.TechnicalDataDocumentSubmissionService documents;
  private final TechnicalDataActorProvider actorProvider;
  private final TechnicalDataWorkflowService workflowService;

  public TechnicalDataTaskController(TechnicalDataTaskApplicationService applicationService,
      com.sanhua.marketingcost.service.technicaldata.TechnicalDataSubmissionValidationService validation,
      com.sanhua.marketingcost.service.technicaldata.TechnicalDataDocumentSubmissionService documents,
      TechnicalDataActorProvider actorProvider, TechnicalDataWorkflowService workflowService) {
    this.applicationService=applicationService; this.validation=validation; this.documents=documents;
    this.actorProvider=actorProvider; this.workflowService=workflowService;
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/forms/{formId}/workbench")
  public CommonResult<com.sanhua.marketingcost.service.technicaldata.TechnicalDataDocumentSubmissionService.Workbench> documentWorkbench(
      @PathVariable long formId) {
    return execute(() -> documents.workbench(formId, actorProvider.current()));
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/forms/{formId}/submissions/{batchId}")
  public CommonResult<com.sanhua.marketingcost.service.technicaldata.TechnicalDataDocumentSubmissionService.Workbench> submittedDocument(
      @PathVariable long formId, @PathVariable String batchId) {
    return execute(() -> documents.submitted(formId,batchId,actorProvider.current()));
  }

  @PreAuthorize("@ss.hasPermi('technical:data:task:edit')")
  @PostMapping("/forms/{formId}/submit")
  public CommonResult<com.sanhua.marketingcost.service.technicaldata.TechnicalDataDocumentSubmissionService.Result> submitDocument(
      @PathVariable long formId,
      @RequestBody com.sanhua.marketingcost.service.technicaldata.TechnicalDataDocumentSubmissionService.Request request) {
    return execute(() -> documents.submit(formId, request, actorProvider.current()));
  }

  @PreAuthorize(PUBLISH_PERMISSION)
  @PostMapping("/tasks/publish-from-quote")
  public CommonResult<TechnicalDataTaskPublishResponse> publishFromQuote(
      @RequestBody TechnicalDataTaskPublishRequest request) {
    return execute(() -> {
      var actor = actorProvider.current();
      TechnicalDataTaskPublishResponse published = applicationService.publish(request, actor);
      return published;
    });
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/products")
  public CommonResult<TechnicalDataWorkbenchPageResponse> workbench(
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String taskStatus,
      @RequestParam(required = false) String accountingMonth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String oaNo) {
    return execute(() -> applicationService.workbench(
        current, size, taskStatus, accountingMonth, keyword, oaNo, actorProvider.current()));
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/tasks/{taskId}")
  public CommonResult<TechnicalDataTaskResponse> detail(@PathVariable Long taskId) {
    return execute(() -> applicationService.detail(taskId, actorProvider.current()));
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/tasks/{taskId}/workflow")
  public CommonResult<TechnicalDataWorkflowService.Status> workflow(@PathVariable Long taskId) {
    return execute(() -> workflowService.status(taskId, actorProvider.current()));
  }

  @PreAuthorize("@ss.hasPermi('technical:data:task:edit')")
  @PostMapping("/tasks/{taskId}/validate")
  public CommonResult<TechnicalDataTaskValidationResponse> validate(@PathVariable Long taskId) {
    return execute(() -> {
      var actor=actorProvider.current();
      return validation.validate(taskId, actor.userId(), actor);
    });
  }

  private static <T> CommonResult<T> execute(Supplier<T> supplier) {
    try {
      return CommonResult.success(supplier.get());
    } catch (TechnicalDataTaskException exception) {
      int code = switch (exception.code()) {
        case TASK_NOT_FOUND, PRODUCT_NOT_FOUND -> GlobalErrorCodeConstants.NOT_FOUND.getCode();
        case FORBIDDEN -> GlobalErrorCodeConstants.FORBIDDEN.getCode();
        case VERSION_CONFLICT,
            ACTIVE_PRODUCT_CONFLICT, SHARED_MODULE_CONFLICT,
            PERSISTENCE_CONFLICT -> 409;
        case INVALID_REQUEST -> GlobalErrorCodeConstants.BAD_REQUEST.getCode();
      };
      return CommonResult.error(code, exception.code().name() + ": " + exception.getMessage());
    } catch (com.sanhua.marketingcost.integration.oa.OaIntegrationException exception) {
      return CommonResult.error(exception.httpStatus().value(), exception.code() + ": " + exception.getMessage());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), exception.getMessage());
    }
  }
}
