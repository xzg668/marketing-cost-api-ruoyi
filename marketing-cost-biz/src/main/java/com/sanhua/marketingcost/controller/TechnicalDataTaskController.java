package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPageResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPublishRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPublishResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskSubmissionRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskSubmissionResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskValidationResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataWorkbenchPageResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAdminActionRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataOaCallbackRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataOaCallbackResponse;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataAdminOperationService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataOaIntegrationService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataSubmissionApplicationService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskApplicationService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskException;
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
      "@ss.hasAnyPermi('technical:data:task:list','technical:data:review:list',"
          + "'technical:data:admin:operate')";
  private static final String PUBLISH_PERMISSION =
      "@ss.hasAnyPermi('technical:data:task:edit','technical:data:admin:operate',"
          + "'ingest:quote:cost-run:execute')";

  private final TechnicalDataTaskApplicationService applicationService;
  private final TechnicalDataSubmissionApplicationService submissionService;
  private final TechnicalDataActorProvider actorProvider;
  private final TechnicalDataAdminOperationService adminOperationService;
  private final TechnicalDataOaIntegrationService oaIntegrationService;

  @Autowired
  public TechnicalDataTaskController(
      TechnicalDataTaskApplicationService applicationService,
      TechnicalDataSubmissionApplicationService submissionService,
      TechnicalDataActorProvider actorProvider,
      TechnicalDataAdminOperationService adminOperationService,
      TechnicalDataOaIntegrationService oaIntegrationService) {
    this.applicationService = applicationService;
    this.submissionService = submissionService;
    this.actorProvider = actorProvider;
    this.adminOperationService = adminOperationService;
    this.oaIntegrationService = oaIntegrationService;
  }

  public TechnicalDataTaskController(
      TechnicalDataTaskApplicationService applicationService,
      TechnicalDataSubmissionApplicationService submissionService,
      TechnicalDataActorProvider actorProvider) {
    this(applicationService, submissionService, actorProvider, null, null);
  }

  @PreAuthorize(PUBLISH_PERMISSION)
  @PostMapping("/tasks/publish-from-quote")
  public CommonResult<TechnicalDataTaskPublishResponse> publishFromQuote(
      @RequestBody TechnicalDataTaskPublishRequest request) {
    return execute(() -> {
      var actor = actorProvider.current();
      TechnicalDataTaskPublishResponse published = applicationService.publish(request, actor);
      if (oaIntegrationService != null && oaIntegrationService.enabled()
          && published.task() != null
          && published.task().externalTaskId() == null) {
        TechnicalDataTaskResponse synchronizedTask = oaIntegrationService.publishInitial(
            published.task().id(), actor);
        return new TechnicalDataTaskPublishResponse(
            published.action(), published.replacedTaskId(), synchronizedTask);
      }
      return published;
    });
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/tasks/mine")
  public CommonResult<TechnicalDataTaskPageResponse> mine(
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String taskStatus,
      @RequestParam(required = false) String accountingMonth) {
    return execute(() -> applicationService.mine(
        current, size, taskStatus, accountingMonth, actorProvider.current()));
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/products/mine")
  public CommonResult<TechnicalDataWorkbenchPageResponse> workbench(
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String taskStatus,
      @RequestParam(required = false) String accountingMonth,
      @RequestParam(required = false) String keyword) {
    return execute(() -> applicationService.workbench(
        current, size, taskStatus, accountingMonth, keyword, actorProvider.current()));
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/tasks/{taskId}")
  public CommonResult<TechnicalDataTaskResponse> detail(@PathVariable Long taskId) {
    return execute(() -> applicationService.detail(taskId, actorProvider.current()));
  }

  @PreAuthorize(PUBLISH_PERMISSION)
  @PostMapping("/tasks/{taskId}/validate")
  public CommonResult<TechnicalDataTaskValidationResponse> validate(@PathVariable Long taskId) {
    return execute(() -> submissionService.validate(taskId, actorProvider.current()));
  }

  @PreAuthorize(PUBLISH_PERMISSION)
  @PostMapping("/tasks/{taskId}/submit")
  public CommonResult<TechnicalDataTaskSubmissionResponse> submit(
      @PathVariable Long taskId,
      @RequestBody TechnicalDataTaskSubmissionRequest request) {
    return execute(() -> submissionService.submit(taskId, request, actorProvider.current()));
  }

  @PreAuthorize("@ss.hasPermi('technical:data:admin:operate')")
  @PostMapping("/tasks/{taskId}/admin/reassign")
  public CommonResult<TechnicalDataTaskResponse> reassign(
      @PathVariable Long taskId, @RequestBody TechnicalDataAdminActionRequest request) {
    return execute(() -> adminOperationService.reassign(taskId, request, actorProvider.current()));
  }

  @PreAuthorize("@ss.hasPermi('technical:data:admin:operate')")
  @PostMapping("/tasks/{taskId}/admin/proxy-entry")
  public CommonResult<TechnicalDataTaskResponse> startProxyEntry(
      @PathVariable Long taskId, @RequestBody TechnicalDataAdminActionRequest request) {
    return execute(() -> adminOperationService.startProxyEntry(
        taskId, request, actorProvider.current()));
  }

  @PreAuthorize("@ss.hasPermi('technical:data:admin:operate')")
  @PostMapping("/tasks/{taskId}/admin/unlock-draft")
  public CommonResult<TechnicalDataTaskResponse> unlockDraft(
      @PathVariable Long taskId, @RequestBody TechnicalDataAdminActionRequest request) {
    return execute(() -> adminOperationService.unlockDraft(taskId, request, actorProvider.current()));
  }

  @PreAuthorize("@ss.hasPermi('technical:data:admin:operate')")
  @PostMapping("/tasks/{taskId}/admin/void")
  public CommonResult<TechnicalDataTaskResponse> voidTask(
      @PathVariable Long taskId, @RequestBody TechnicalDataAdminActionRequest request) {
    return execute(() -> adminOperationService.voidTask(taskId, request, actorProvider.current()));
  }

  @PreAuthorize("@ss.hasPermi('technical:data:admin:operate')")
  @PostMapping("/tasks/{taskId}/external-task/retry")
  public CommonResult<TechnicalDataTaskResponse> retryExternalTask(
      @PathVariable Long taskId, @RequestBody TechnicalDataAdminActionRequest request) {
    return execute(() -> oaIntegrationService.retry(taskId, request, actorProvider.current()));
  }

  @PostMapping("/external/oa/task-callback")
  public CommonResult<TechnicalDataOaCallbackResponse> oaCallback(
      @RequestBody TechnicalDataOaCallbackRequest request) {
    return execute(() -> oaIntegrationService.callback(request));
  }

  private static <T> CommonResult<T> execute(Supplier<T> supplier) {
    try {
      return CommonResult.success(supplier.get());
    } catch (TechnicalDataTaskException exception) {
      int code = switch (exception.code()) {
        case TASK_NOT_FOUND, PRODUCT_NOT_FOUND -> GlobalErrorCodeConstants.NOT_FOUND.getCode();
        case FORBIDDEN -> GlobalErrorCodeConstants.FORBIDDEN.getCode();
        case VERSION_CONFLICT,
            ACTIVE_PRODUCT_CONFLICT,
            SOURCE_CHANGE_REQUIRES_COMPLETE_TASK,
            PERSISTENCE_CONFLICT -> 409;
        case INVALID_REQUEST -> GlobalErrorCodeConstants.BAD_REQUEST.getCode();
      };
      return CommonResult.error(code, exception.code().name() + ": " + exception.getMessage());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), exception.getMessage());
    }
  }
}
