package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.security.QuoteVisibilityPolicy;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/system/user/{userId}/quote-scope")
@PreAuthorize("@ss.hasRole('admin')")
public class QuoteVisibilityAccountController {
  private final QuoteVisibilityPolicy policy;
  public QuoteVisibilityAccountController(QuoteVisibilityPolicy policy) { this.policy=policy; }
  public record Configure(String quoteScope) {}
  @GetMapping public CommonResult<QuoteVisibilityPolicy.AccountScope> get(@PathVariable long userId) {
    return CommonResult.success(policy.account(userId));
  }
  @OperationLog(module = "用户管理-报价范围", operationType = OperationType.UPDATE, targetIdParam = "userId")
  @PutMapping public CommonResult<QuoteVisibilityPolicy.AccountScope> save(@PathVariable long userId,
      @RequestBody Configure request,Authentication actor) {
    return CommonResult.success(policy.configure(userId,request.quoteScope(),actor.getName()));
  }
}
