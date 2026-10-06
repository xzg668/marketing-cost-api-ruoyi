package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import com.sanhua.marketingcost.integration.oa.oauth.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/oa")
public class OaOAuthController {
  private final OaOAuthService service;
  public OaOAuthController(OaOAuthService service) { this.service = service; }

  @PostMapping("/start")
  public CommonResult<OaOAuthService.BeginResponse> start(@RequestBody OaOAuthService.BeginRequest request,
      HttpServletResponse response) {
    noCache(response);
    return execute("OAUTH_START", () -> service.begin(request));
  }

  @GetMapping("/callback")
  public void callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
      HttpServletResponse response) throws IOException {
    noCache(response);
    try (var call = OaInterfaceLog.start("OAUTH_CALLBACK")) {
      try { String target = service.callback(code, state); call.success(); response.sendRedirect(target); }
      catch (OaOAuthException exception) {
        call.result("REJECTED", null, exception.code());
        response.sendRedirect(service.landingUrl() + "#error=" + OaOAuthClient.encode(exception.getMessage()));
      }
    }
  }

  @PostMapping("/exchange")
  public CommonResult<OaOAuthService.SessionResponse> exchange(@RequestBody OaOAuthService.ExchangeRequest request,
      HttpServletResponse response) {
    noCache(response);
    return execute("OAUTH_SESSION", () -> service.exchange(request));
  }

  @GetMapping("/me")
  public CommonResult<OaOAuthPrincipal> me(Authentication authentication, HttpServletResponse response) {
    noCache(response);
    if (authentication == null || !(authentication.getPrincipal() instanceof OaOAuthPrincipal person)) {
      return CommonResult.error(401, "请通过 OA 免登进入补录工作台");
    }
    return CommonResult.success(person);
  }

  private static void noCache(HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
  }
  private static <T> CommonResult<T> execute(String operation, Supplier<T> action) {
    try (var call = OaInterfaceLog.start(operation)) {
      try { T result = action.get(); call.success(); return CommonResult.success(result); }
      catch (OaOAuthException exception) {
        call.result("REJECTED", null, exception.code());
        return CommonResult.error(400, exception.getMessage());
      }
    }
  }
}
