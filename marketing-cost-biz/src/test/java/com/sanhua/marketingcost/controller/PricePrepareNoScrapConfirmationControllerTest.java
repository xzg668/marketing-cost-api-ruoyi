package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmRequest;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmResponse;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmationPageRequest;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapConfirmationPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.NoScrapRevokeRequest;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.MakePartNoScrapConfirmationService;
import com.sanhua.marketingcost.service.PricePrepareQueryService;
import com.sanhua.marketingcost.service.PricePrepareService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class PricePrepareNoScrapConfirmationControllerTest {

  private PricePrepareService pricePrepareService;
  private PricePrepareQueryService queryService;
  private MakePartNoScrapConfirmationService noScrapService;
  private PricePrepareController controller;

  @BeforeEach
  void setUp() {
    pricePrepareService = mock(PricePrepareService.class);
    queryService = mock(PricePrepareQueryService.class);
    noScrapService = mock(MakePartNoScrapConfirmationService.class);
    controller = new PricePrepareController(pricePrepareService, queryService, noScrapService);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("POST /no-scrap-confirmations：正常请求返回成功")
  void confirmNoScrapReturnsSuccess() {
    NoScrapConfirmResponse mocked = response(10L);
    when(noScrapService.confirm(any(), eq("alice"))).thenReturn(mocked);

    CommonResult<NoScrapConfirmResponse> result =
        controller.confirmNoScrap(confirmRequest(), auth("alice"));

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData()).isSameAs(mocked);
    verify(noScrapService).confirm(any(), eq("alice"));
  }

  @Test
  @DisplayName("POST /no-scrap-confirmations：参数非法返回 BAD_REQUEST")
  void confirmNoScrapBadRequest() {
    when(noScrapService.confirm(any(), eq("alice")))
        .thenThrow(new IllegalArgumentException("materialNo 不能为空"));

    CommonResult<NoScrapConfirmResponse> result =
        controller.confirmNoScrap(confirmRequest(), auth("alice"));

    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("materialNo");
  }

  @Test
  @DisplayName("POST /no-scrap-confirmations/{id}/revoke：撤销请求透传操作者")
  void revokeNoScrapReturnsSuccess() {
    NoScrapRevokeRequest request = new NoScrapRevokeRequest();
    request.setRevokeReason("业务撤销");
    when(noScrapService.revoke(eq(10L), eq(request), eq("bob"))).thenReturn(response(10L));

    CommonResult<NoScrapConfirmResponse> result =
        controller.revokeNoScrap(10L, request, auth("bob"));

    assertThat(result.isSuccess()).isTrue();
    verify(noScrapService).revoke(10L, request, "bob");
  }

  @Test
  @DisplayName("GET /no-scrap-confirmations：分页查询返回成功")
  void pageNoScrapConfirmationsReturnsSuccess() {
    NoScrapConfirmationPageResponse mocked =
        new NoScrapConfirmationPageResponse(1, List.of(response(10L)));
    when(noScrapService.page(any())).thenReturn(mocked);

    CommonResult<NoScrapConfirmationPageResponse> result =
        controller.noScrapConfirmations(new NoScrapConfirmationPageRequest());

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getTotal()).isEqualTo(1);
  }

  @Test
  @DisplayName("GET /no-scrap-confirmations/effective：有效查询参数非法返回 BAD_REQUEST")
  void effectiveBadRequest() {
    when(noScrapService.findEffective("301300339", "bad", "COMMERCIAL"))
        .thenThrow(new IllegalArgumentException("periodMonth 必须为 yyyy-MM 格式"));

    CommonResult<NoScrapConfirmResponse> result =
        controller.effectiveNoScrapConfirmation("301300339", "bad", "COMMERCIAL");

    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("yyyy-MM");
  }

  @Test
  @DisplayName("确认和撤销接口必须使用无废料专属权限")
  void noScrapEndpointsUseDedicatedPermissions() throws Exception {
    Method confirm = PricePrepareController.class.getMethod(
        "confirmNoScrap",
        NoScrapConfirmRequest.class,
        org.springframework.security.core.Authentication.class);
    Method revoke = PricePrepareController.class.getMethod(
        "revokeNoScrap",
        Long.class,
        NoScrapRevokeRequest.class,
        org.springframework.security.core.Authentication.class);

    assertThat(confirm.getAnnotation(PreAuthorize.class).value())
        .contains("cost:price-prepare:no-scrap-confirm");
    assertThat(revoke.getAnnotation(PreAuthorize.class).value())
        .contains("cost:price-prepare:no-scrap-revoke");
  }

  @Test
  @DisplayName("权限不足时 method security 拒绝确认无废料")
  void confirmDeniedWithoutPermission() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(TestSecurityConfig.class)) {
      PricePrepareController proxiedController = context.getBean(PricePrepareController.class);
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  "staff",
                  null,
                  List.of(new SimpleGrantedAuthority("cost:price-prepare:gap"))));

      assertThatThrownBy(() -> proxiedController.confirmNoScrap(confirmRequest(), auth("staff")))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  @DisplayName("权限不足时 method security 拒绝撤销无废料确认")
  void revokeDeniedWithoutPermission() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(TestSecurityConfig.class)) {
      PricePrepareController proxiedController = context.getBean(PricePrepareController.class);
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  "staff",
                  null,
                  List.of(new SimpleGrantedAuthority("cost:price-prepare:no-scrap-confirm"))));

      NoScrapRevokeRequest request = new NoScrapRevokeRequest();
      request.setRevokeReason("业务撤销");
      assertThatThrownBy(() -> proxiedController.revokeNoScrap(10L, request, auth("staff")))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  private UsernamePasswordAuthenticationToken auth(String username) {
    return new UsernamePasswordAuthenticationToken(username, "n/a");
  }

  private NoScrapConfirmRequest confirmRequest() {
    NoScrapConfirmRequest request = new NoScrapConfirmRequest();
    request.setBusinessUnitType("COMMERCIAL");
    request.setMaterialNo("301300339");
    request.setEffectiveFromMonth("2026-06");
    request.setConfirmReason("确认该料号确实无废料产生");
    return request;
  }

  private NoScrapConfirmResponse response(Long id) {
    NoScrapConfirmResponse response = new NoScrapConfirmResponse();
    response.setId(id);
    response.setBusinessUnitType("COMMERCIAL");
    response.setMaterialNo("301300339");
    response.setEffectiveFromMonth("2026-06");
    response.setStatus("ACTIVE");
    response.setConfirmedBy("alice");
    return response;
  }

  @Configuration
  @EnableMethodSecurity
  static class TestSecurityConfig {
    @Bean("ss")
    PermissionService permissionService() {
      return new PermissionService();
    }

    @Bean
    PricePrepareService pricePrepareService() {
      return mock(PricePrepareService.class);
    }

    @Bean
    PricePrepareQueryService pricePrepareQueryService() {
      return mock(PricePrepareQueryService.class);
    }

    @Bean
    MakePartNoScrapConfirmationService noScrapConfirmationService() {
      return mock(MakePartNoScrapConfirmationService.class);
    }

    @Bean
    PricePrepareController pricePrepareController(
        PricePrepareService pricePrepareService,
        PricePrepareQueryService queryService,
        MakePartNoScrapConfirmationService noScrapConfirmationService) {
      return new PricePrepareController(
          pricePrepareService, queryService, noScrapConfirmationService);
    }
  }
}
