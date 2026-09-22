package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceReferenceView;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceSaveRequest;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataPriceApplicationService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskException;
import java.util.function.Supplier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/technical-data/products/{productId}/price")
public class TechnicalDataPriceController {
  private static final String READ_PERMISSION =
      "@ss.hasAnyPermi('technical:data:task:list','technical:data:task:edit',"
          + "'technical:data:admin:operate','ingest:quote:cost-run:execute')";
  private static final String EDIT_PERMISSION =
      "@ss.hasAnyPermi('technical:data:task:edit','technical:data:admin:operate',"
          + "'ingest:quote:cost-run:execute')";

  private final TechnicalDataPriceApplicationService applicationService;
  private final TechnicalDataActorProvider actorProvider;

  public TechnicalDataPriceController(
      TechnicalDataPriceApplicationService applicationService,
      TechnicalDataActorProvider actorProvider) {
    this.applicationService = applicationService;
    this.actorProvider = actorProvider;
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping
  public CommonResult<TechnicalDataPriceResponse> get(@PathVariable Long productId,
      @RequestParam(required = false) Long versionId) {
    return execute(() -> applicationService.get(productId, versionId, actorProvider.current()));
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/references")
  public CommonResult<java.util.List<TechnicalDataPriceReferenceView>> references(
      @PathVariable Long productId,
      @RequestParam String searchBy, @RequestParam String keyword) {
    return execute(() -> applicationService.references(
        productId, searchBy, keyword, actorProvider.current()).stream()
        .map(TechnicalDataPriceReferenceView::from).toList());
  }

  @PreAuthorize(EDIT_PERMISSION)
  @PutMapping
  public CommonResult<TechnicalDataPriceResponse> save(
      @PathVariable Long productId,
      @RequestBody TechnicalDataPriceSaveRequest request) {
    return execute(() -> applicationService.save(productId, request, actorProvider.current()));
  }

  @PreAuthorize(READ_PERMISSION)
  @org.springframework.web.bind.annotation.PostMapping("/recheck")
  public CommonResult<TechnicalDataPriceResponse> recheck(@PathVariable Long productId) {
    return execute(() -> applicationService.recheckPublication(productId, actorProvider.current()));
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
