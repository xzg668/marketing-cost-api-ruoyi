package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataReviewDecisionRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataReviewDecisionResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataReviewItemDetailResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataReviewTaskResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataTaskPageResponse;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataReviewApplicationService;
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

@RestController
@RequestMapping("/api/v2/technical-data/reviews")
public class TechnicalDataReviewController {
  private static final String READ_PERMISSION =
      "@ss.hasAnyPermi('technical:data:review:list','technical:data:admin:operate')";
  private static final String DECIDE_PERMISSION =
      "@ss.hasAnyPermi('technical:data:review:decide','technical:data:admin:operate')";

  private final TechnicalDataReviewApplicationService service;
  private final TechnicalDataActorProvider actorProvider;

  public TechnicalDataReviewController(
      TechnicalDataReviewApplicationService service,
      TechnicalDataActorProvider actorProvider) {
    this.service = service;
    this.actorProvider = actorProvider;
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/mine")
  public CommonResult<TechnicalDataTaskPageResponse> mine(
      @RequestParam(defaultValue = "1") int current,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String taskStatus,
      @RequestParam(required = false) String accountingMonth) {
    return execute(() -> service.mine(
        current, size, taskStatus, accountingMonth, actorProvider.current()));
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/tasks/{taskId}")
  public CommonResult<TechnicalDataReviewTaskResponse> detail(@PathVariable Long taskId) {
    return execute(() -> service.detail(taskId, actorProvider.current()));
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/tasks/{taskId}/items/{itemId}")
  public CommonResult<TechnicalDataReviewItemDetailResponse> itemDetail(
      @PathVariable Long taskId, @PathVariable Long itemId) {
    return execute(() -> service.itemDetail(taskId, itemId, actorProvider.current()));
  }

  @PreAuthorize(DECIDE_PERMISSION)
  @PostMapping("/tasks/{taskId}/items/{itemId}/pass")
  public CommonResult<TechnicalDataReviewDecisionResponse> pass(
      @PathVariable Long taskId,
      @PathVariable Long itemId,
      @RequestBody TechnicalDataReviewDecisionRequest request) {
    return execute(() -> service.decide(
        taskId, itemId, "PASSED", request, actorProvider.current()));
  }

  @PreAuthorize(DECIDE_PERMISSION)
  @PostMapping("/tasks/{taskId}/items/{itemId}/return")
  public CommonResult<TechnicalDataReviewDecisionResponse> returned(
      @PathVariable Long taskId,
      @PathVariable Long itemId,
      @RequestBody TechnicalDataReviewDecisionRequest request) {
    return execute(() -> service.decide(
        taskId, itemId, "RETURNED", request, actorProvider.current()));
  }

  private static <T> CommonResult<T> execute(Supplier<T> supplier) {
    try {
      return CommonResult.success(supplier.get());
    } catch (TechnicalDataTaskException exception) {
      int code = switch (exception.code()) {
        case TASK_NOT_FOUND, PRODUCT_NOT_FOUND -> GlobalErrorCodeConstants.NOT_FOUND.getCode();
        case FORBIDDEN -> GlobalErrorCodeConstants.FORBIDDEN.getCode();
        case VERSION_CONFLICT, ACTIVE_PRODUCT_CONFLICT,
            SOURCE_CHANGE_REQUIRES_COMPLETE_TASK, PERSISTENCE_CONFLICT -> 409;
        case INVALID_REQUEST -> GlobalErrorCodeConstants.BAD_REQUEST.getCode();
      };
      return CommonResult.error(code, exception.code().name() + ": " + exception.getMessage());
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), exception.getMessage());
    }
  }
}
