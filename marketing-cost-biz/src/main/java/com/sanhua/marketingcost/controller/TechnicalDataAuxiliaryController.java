package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryReferenceRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryReferenceResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliarySaveRequest;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataAuxiliaryApplicationService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskException;
import java.util.function.Supplier;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/technical-data/products/{productId}/auxiliary")
public class TechnicalDataAuxiliaryController {
  private static final String READ_PERMISSION =
      "@ss.hasAnyPermi('technical:data:task:list','technical:data:review:list',"
          + "'technical:data:admin:operate')";
  private static final String EDIT_PERMISSION =
      "@ss.hasAnyPermi('technical:data:task:edit','technical:data:admin:operate')";

  private final TechnicalDataAuxiliaryApplicationService applicationService;
  private final TechnicalDataActorProvider actorProvider;

  public TechnicalDataAuxiliaryController(
      TechnicalDataAuxiliaryApplicationService applicationService,
      TechnicalDataActorProvider actorProvider) {
    this.applicationService = applicationService;
    this.actorProvider = actorProvider;
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping
  public CommonResult<TechnicalDataAuxiliaryResponse> get(@PathVariable Long productId) {
    return execute(() -> applicationService.get(productId, actorProvider.current()));
  }

  @PreAuthorize(READ_PERMISSION)
  @GetMapping("/references")
  public CommonResult<TechnicalDataAuxiliaryReferenceResponse> references(
      @PathVariable Long productId, @RequestParam(required = false) String keyword) {
    return execute(() -> applicationService.references(productId, keyword, actorProvider.current()));
  }

  @PreAuthorize(EDIT_PERMISSION)
  @PostMapping("/reference")
  public CommonResult<TechnicalDataAuxiliaryResponse> applyReference(
      @PathVariable Long productId,
      @RequestBody TechnicalDataAuxiliaryReferenceRequest request) {
    return execute(() -> applicationService.applyReference(
        productId, request, actorProvider.current()));
  }

  @PreAuthorize(EDIT_PERMISSION)
  @PutMapping
  public CommonResult<TechnicalDataAuxiliaryResponse> save(
      @PathVariable Long productId, @RequestBody TechnicalDataAuxiliarySaveRequest request) {
    return execute(() -> applicationService.save(productId, request, actorProvider.current()));
  }

  @PreAuthorize(EDIT_PERMISSION)
  @DeleteMapping("/items/{itemId}")
  public CommonResult<TechnicalDataAuxiliaryResponse> delete(
      @PathVariable Long productId,
      @PathVariable Long itemId,
      @RequestParam Integer expectedVersion) {
    return execute(() -> applicationService.delete(
        productId, itemId, expectedVersion, actorProvider.current()));
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
      return CommonResult.error(GlobalErrorCodeConstants.BAD_REQUEST.getCode(), exception.getMessage());
    }
  }
}
