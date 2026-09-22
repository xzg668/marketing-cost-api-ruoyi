package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataProfileUpdateRequest;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataProfileApplicationService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskException;
import java.util.function.Supplier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/technical-data")
public class TechnicalDataProfileController {
  private static final String EDIT_PERMISSION =
      "@ss.hasAnyPermi('technical:data:task:edit','technical:data:admin:operate',"
          + "'ingest:quote:cost-run:execute')";

  private final TechnicalDataProfileApplicationService applicationService;
  private final TechnicalDataActorProvider actorProvider;

  public TechnicalDataProfileController(
      TechnicalDataProfileApplicationService applicationService,
      TechnicalDataActorProvider actorProvider) {
    this.applicationService = applicationService;
    this.actorProvider = actorProvider;
  }

  @PreAuthorize(EDIT_PERMISSION)
  @PatchMapping("/products/{productId}/profile")
  public CommonResult<TechnicalDataProfileResponse> save(
      @PathVariable Long productId,
      @RequestBody TechnicalDataProfileUpdateRequest request) {
    return execute(() -> applicationService.save(productId, request, actorProvider.current()));
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
    } catch (IllegalArgumentException | IllegalStateException exception) {
      return CommonResult.error(
          GlobalErrorCodeConstants.BAD_REQUEST.getCode(), exception.getMessage());
    }
  }
}
