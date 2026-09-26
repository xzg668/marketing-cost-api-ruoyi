package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.integration.oa.OaIntegrationException;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataReturnService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskException;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/technical-data/returns")
@PreAuthorize("@ss.hasAnyPermi('ingest:quote:cost-run:execute','technical:data:admin:operate')")
public class TechnicalDataReturnController {
  public record Selection(List<Long> taskIds) {}

  private final TechnicalDataReturnService returns;
  private final TechnicalDataActorProvider actors;

  public TechnicalDataReturnController(
      TechnicalDataReturnService returns, TechnicalDataActorProvider actors) {
    this.returns = returns;
    this.actors = actors;
  }

  @PostMapping("/preview")
  public CommonResult<List<TechnicalDataReturnService.Candidate>> preview(
      @RequestBody Selection selection) {
    return execute(() -> returns.candidates(selection.taskIds(), actors.current()));
  }

  @PostMapping
  public CommonResult<TechnicalDataReturnService.Result> submit(
      @RequestBody TechnicalDataReturnService.Request request) {
    return execute(() -> returns.submit(request, actors.current()));
  }

  @GetMapping("/{batchId}")
  public CommonResult<TechnicalDataReturnService.Result> status(@PathVariable String batchId) {
    return execute(() -> returns.status(batchId, actors.current()));
  }

  private <T> CommonResult<T> execute(Supplier<T> action) {
    try {
      return CommonResult.success(action.get());
    } catch (TechnicalDataTaskException error) {
      return CommonResult.error(
          error.code()
                  == com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskErrorCode
                      .FORBIDDEN
              ? 403
              : 409,
          error.getMessage());
    } catch (OaIntegrationException error) {
      return CommonResult.error(error.httpStatus().value(), error.getMessage());
    } catch (IllegalArgumentException | IllegalStateException error) {
      return CommonResult.error(400, error.getMessage());
    }
  }
}
