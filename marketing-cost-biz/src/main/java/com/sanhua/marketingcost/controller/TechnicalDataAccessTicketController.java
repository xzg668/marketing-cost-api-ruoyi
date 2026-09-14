package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketExchangeRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketExchangeResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketIssueRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketIssueResponse;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataAccessTicketService;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataActorProvider;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataTaskException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/technical-data/access-tickets")
public class TechnicalDataAccessTicketController {
  private final TechnicalDataAccessTicketService service;
  private final TechnicalDataActorProvider actorProvider;

  public TechnicalDataAccessTicketController(
      TechnicalDataAccessTicketService service, TechnicalDataActorProvider actorProvider) {
    this.service = service;
    this.actorProvider = actorProvider;
  }

  @PreAuthorize("@ss.hasPermi('technical:data:admin:operate')")
  @PostMapping("/tasks/{taskId}")
  public CommonResult<TechnicalDataAccessTicketIssueResponse> issue(
      @PathVariable Long taskId, @RequestBody TechnicalDataAccessTicketIssueRequest request) {
    return execute(() -> service.issue(taskId, request, actorProvider.current()));
  }

  @PostMapping("/exchange")
  public CommonResult<TechnicalDataAccessTicketExchangeResponse> exchange(
      @RequestBody TechnicalDataAccessTicketExchangeRequest request) {
    return execute(() -> service.exchange(request));
  }

  private static <T> CommonResult<T> execute(java.util.function.Supplier<T> supplier) {
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
    }
  }
}
