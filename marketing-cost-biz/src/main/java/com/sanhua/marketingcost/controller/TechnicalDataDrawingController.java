package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataDrawingRecheckRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataDrawingResponse;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataDrawingApplicationService;
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
@RequestMapping("/api/v2/technical-data/products/{productId}/drawing")
public class TechnicalDataDrawingController {
  private final TechnicalDataDrawingApplicationService service;
  private final TechnicalDataActorProvider actors;

  public TechnicalDataDrawingController(TechnicalDataDrawingApplicationService service, TechnicalDataActorProvider actors) {
    this.service = service; this.actors = actors;
  }

  @GetMapping
  @PreAuthorize("@ss.hasAnyPermi('technical:data:task:list','technical:data:task:edit','technical:data:admin:operate','ingest:quote:cost-run:execute')")
  public CommonResult<TechnicalDataDrawingResponse> read(@PathVariable Long productId,
      @RequestParam(required = false) Long versionId) {
    return execute(() -> service.read(productId, versionId, actors.current()));
  }

  @PostMapping("/recheck")
  @PreAuthorize("@ss.hasAnyPermi('technical:data:task:edit','technical:data:admin:operate','ingest:quote:cost-run:execute')")
  public CommonResult<TechnicalDataDrawingResponse> recheck(@PathVariable Long productId,
      @RequestBody TechnicalDataDrawingRecheckRequest request) {
    return execute(() -> service.recheck(productId, request, actors.current()));
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
    } catch (IllegalArgumentException | com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceImportException exception) {
      return CommonResult.error(400, exception.getMessage());
    }
  }
}
