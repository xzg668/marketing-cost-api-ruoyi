package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataManufacturingResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataManufacturingSaveRequest;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomException;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingApplicationService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskException;
import java.util.function.Supplier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/technical-data/products/{productId}/manufacturing")
public class TechnicalDataManufacturingController {
  private final TechnicalDataManufacturingApplicationService service;
  private final TechnicalDataActorProvider actors;

  public TechnicalDataManufacturingController(TechnicalDataManufacturingApplicationService service, TechnicalDataActorProvider actors) {
    this.service = service; this.actors = actors;
  }

  @GetMapping
  @PreAuthorize("@ss.hasAnyPermi('technical:data:task:list','technical:data:task:edit','technical:data:admin:operate','ingest:quote:cost-run:execute')")
  public CommonResult<TechnicalDataManufacturingResponse> read(@PathVariable Long productId, @RequestParam(required = false) Long versionId) {
    return execute(() -> service.read(productId, versionId, actors.current()));
  }

  @GetMapping("/raw-material")
  @PreAuthorize("@ss.hasAnyPermi('technical:data:task:list','technical:data:task:edit','technical:data:admin:operate','ingest:quote:cost-run:execute')")
  public CommonResult<TechnicalDataManufacturingApplicationService.MaterialOption> material(@PathVariable Long productId, @RequestParam String materialNo) {
    return execute(() -> service.material(productId, materialNo, actors.current()));
  }

  @PutMapping
  @PreAuthorize("@ss.hasAnyPermi('technical:data:task:edit','technical:data:admin:operate','ingest:quote:cost-run:execute')")
  public CommonResult<TechnicalDataManufacturingResponse> save(@PathVariable Long productId, @RequestBody TechnicalDataManufacturingSaveRequest request) {
    return execute(() -> service.save(productId, request, actors.current()));
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
    } catch (IllegalArgumentException | ElectronicDrawingHybridBomException exception) {
      return CommonResult.error(400, exception.getMessage());
    }
  }
}
