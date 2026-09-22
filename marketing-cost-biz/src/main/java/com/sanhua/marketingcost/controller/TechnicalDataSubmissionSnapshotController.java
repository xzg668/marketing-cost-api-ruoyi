package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.entity.QuoteTechSubmission;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataSubmissionSnapshotService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskException;
import java.util.function.Supplier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/technical-data/tasks/{taskId}/submissions")
public class TechnicalDataSubmissionSnapshotController {
  private final TechnicalDataSubmissionSnapshotService service;
  private final TechnicalDataActorProvider actorProvider;

  public TechnicalDataSubmissionSnapshotController(
      TechnicalDataSubmissionSnapshotService service, TechnicalDataActorProvider actorProvider) {
    this.service = service;
    this.actorProvider = actorProvider;
  }

  @GetMapping("/{submissionId}")
  @PreAuthorize("@ss.hasAnyPermi('technical:data:task:list','technical:data:task:edit','technical:data:admin:operate')")
  public CommonResult<QuoteTechSubmission> detail(@PathVariable Long taskId, @PathVariable Long submissionId) {
    return execute(() -> service.read(taskId, submissionId, actorProvider.current()));
  }

  private <T> CommonResult<T> execute(Supplier<T> action) {
    try { return CommonResult.success(action.get()); }
    catch (TechnicalDataTaskException exception) {
      int code = switch (exception.code()) {
        case FORBIDDEN -> 403;
        case TASK_NOT_FOUND, PRODUCT_NOT_FOUND -> 404;
        case INVALID_REQUEST -> 400;
        default -> 409;
      };
      return CommonResult.error(code, exception.getMessage());
    } catch (IllegalArgumentException exception) {
      return CommonResult.error(400, exception.getMessage());
    } catch (IllegalStateException exception) {
      return CommonResult.error(409, exception.getMessage());
    }
  }

}
