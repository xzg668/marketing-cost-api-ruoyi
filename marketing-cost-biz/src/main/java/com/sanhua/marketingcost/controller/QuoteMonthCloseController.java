package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.costing.QuoteMonthCloseService;
import com.sanhua.marketingcost.service.costing.QuoteMonthCloseService.Close;
import com.sanhua.marketingcost.service.costing.QuoteMonthCloseService.View;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class QuoteMonthCloseController {
  private static final String ROOT = "/api/v1/quote-month-close";
  private static final String CHANGE_ACCESS = "@ss.hasAnyRole('admin','bu_director')";
  private static final String VIEW_ACCESS = CHANGE_ACCESS + " or hasAuthority('cost:month-close:list')";

  private final QuoteMonthCloseService service;
  private final PermissionService permissions;

  public QuoteMonthCloseController(QuoteMonthCloseService service, PermissionService permissions) {
    this.service = service;
    this.permissions = permissions;
  }

  @GetMapping(ROOT)
  @PreAuthorize(VIEW_ACCESS)
  public CommonResult<View> view(@RequestParam(required = false) String periodMonth) {
    try {
      return CommonResult.success(service.view(periodMonth, permissions.hasAnyRole("admin", "bu_director")));
    } catch (IllegalArgumentException failure) {
      return CommonResult.error(400, failure.getMessage());
    }
  }

  @GetMapping(ROOT + "/history")
  @PreAuthorize(VIEW_ACCESS)
  public CommonResult<List<Close>> history() {
    return CommonResult.success(service.history());
  }

  @PostMapping(ROOT + "/confirm")
  @PreAuthorize(CHANGE_ACCESS)
  public CommonResult<Close> confirm(@RequestBody Confirm request, Authentication authentication) {
    try {
      return CommonResult.success(service.confirm(request.periodMonth(), authentication.getName()));
    } catch (IllegalArgumentException | IllegalStateException failure) {
      return CommonResult.error(400, failure.getMessage());
    }
  }

  @PostMapping(ROOT + "/{closeId}/revoke")
  @PreAuthorize(CHANGE_ACCESS)
  public CommonResult<Close> revoke(@PathVariable long closeId, @RequestBody Revoke request,
      Authentication authentication) {
    try {
      return CommonResult.success(service.revoke(closeId, request.periodMonth(), request.reason(), authentication.getName()));
    } catch (IllegalArgumentException | IllegalStateException failure) {
      return CommonResult.error(400, failure.getMessage());
    }
  }

  public record Confirm(String periodMonth) {}
  public record Revoke(String periodMonth, String reason) {}
}
