package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestListItemResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunTrialRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCuMaterialDifferenceResponse;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.BusinessUnitRepriceLockGuard;
import com.sanhua.marketingcost.service.QuoteBomConfirmationService;
import com.sanhua.marketingcost.service.QuoteCostRunWorkbenchService;
import com.sanhua.marketingcost.service.QuoteCostingWorkbenchService;
import com.sanhua.marketingcost.service.QuotePricePrepareWorkbenchService;
import com.sanhua.marketingcost.service.QuotePriceTypeConfirmationService;
import com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class QuoteRequestControllerSecurityTest {
  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void quoteRequestListDeniesWithoutPermission() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(TestSecurityConfig.class)) {
      QuoteRequestController controller = context.getBean(QuoteRequestController.class);
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  "staff", null, List.of(new SimpleGrantedAuthority("other:permission"))));

      assertThatThrownBy(() -> controller.page(1, 20, null, null, null, null))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void quoteRequestListAllowsWithPermission() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(TestSecurityConfig.class)) {
      QuoteRequestQueryService service = context.getBean(QuoteRequestQueryService.class);
      when(service.pageRequests(1, 20, null, null, null, null))
          .thenReturn(new PageResult<>(List.of(new QuoteRequestListItemResponse()), 1L));
      QuoteRequestController controller = context.getBean(QuoteRequestController.class);
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  "finance",
                  null,
                  List.of(new SimpleGrantedAuthority("ingest:quote:list"))));

      assertThat(controller.page(1, 20, null, null, null, null).getData().getTotal()).isEqualTo(1);
    }
  }

  @Test
  void cuMaterialDifferencesDeniesWithoutQuotePermission() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(TestSecurityConfig.class)) {
      QuoteRequestController controller = context.getBean(QuoteRequestController.class);
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  "staff", null, List.of(new SimpleGrantedAuthority("other:permission"))));

      assertThatThrownBy(
              () ->
                  controller.cuMaterialDifferences(
                      "OA-1", 1L, "RUN-1", 1, 20, null, null, true, null))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void cuMaterialDifferencesAllowsWithQuotePermission() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(TestSecurityConfig.class)) {
      QuoteCostRunWorkbenchService service = context.getBean(QuoteCostRunWorkbenchService.class);
      when(
              service.pageCuMaterialDifferences(
                  "OA-1", 1L, "RUN-1", 1, 20, null, null, true, null))
          .thenReturn(new PageResult<>(List.of(new QuoteCuMaterialDifferenceResponse()), 1L));
      QuoteRequestController controller = context.getBean(QuoteRequestController.class);
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  "finance",
                  null,
                  List.of(new SimpleGrantedAuthority("ingest:quote:list"))));

      assertThat(
              controller
                  .cuMaterialDifferences(
                      "OA-1", 1L, "RUN-1", 1, 20, null, null, true, null)
                  .getData()
                  .getTotal())
          .isEqualTo(1L);
    }
  }

  @Test
  void trialCostRunDeniesWithoutQuotePermission() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(TestSecurityConfig.class)) {
      QuoteRequestController controller = context.getBean(QuoteRequestController.class);
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  "staff", null, List.of(new SimpleGrantedAuthority("other:permission"))));

      assertThatThrownBy(
              () -> controller.trialCostRun("OA-1", 1L, new QuoteCostRunTrialRequest()))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void trialCostRunAllowsQuotePermissionAndChecksMonthlyRepriceLock() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(TestSecurityConfig.class)) {
      QuoteCostRunWorkbenchService service = context.getBean(QuoteCostRunWorkbenchService.class);
      BusinessUnitRepriceLockGuard lockGuard =
          context.getBean(BusinessUnitRepriceLockGuard.class);
      QuoteCostRunTrialRequest request = new QuoteCostRunTrialRequest();
      when(service.trial("OA-1", 1L, request)).thenReturn(new QuoteCostRunWorkbenchResponse());
      QuoteRequestController controller = context.getBean(QuoteRequestController.class);
      SecurityContextHolder.getContext()
          .setAuthentication(
              new UsernamePasswordAuthenticationToken(
                  "quoter",
                  null,
                  List.of(new SimpleGrantedAuthority("ingest:quote:list"))));

      assertThat(controller.trialCostRun("OA-1", 1L, request).isSuccess()).isTrue();
      verify(lockGuard).assertCostRunAllowed("OA-1");
      verify(service).trial("OA-1", 1L, request);
    }
  }

  @Configuration
  @EnableMethodSecurity
  static class TestSecurityConfig {
    @Bean("ss")
    PermissionService permissionService() {
      return new PermissionService();
    }

    @Bean
    QuoteRequestQueryService quoteRequestQueryService() {
      return mock(QuoteRequestQueryService.class);
    }

    @Bean
    QuoteCostingWorkbenchService quoteCostingWorkbenchService() {
      return mock(QuoteCostingWorkbenchService.class);
    }

    @Bean
    QuoteBomConfirmationService quoteBomConfirmationService() {
      return mock(QuoteBomConfirmationService.class);
    }

    @Bean
    QuotePriceTypeConfirmationService quotePriceTypeConfirmationService() {
      return mock(QuotePriceTypeConfirmationService.class);
    }

    @Bean
    QuotePricePrepareWorkbenchService quotePricePrepareWorkbenchService() {
      return mock(QuotePricePrepareWorkbenchService.class);
    }

    @Bean
    QuoteCostRunWorkbenchService quoteCostRunWorkbenchService() {
      return mock(QuoteCostRunWorkbenchService.class);
    }

    @Bean
    BusinessUnitRepriceLockGuard businessUnitRepriceLockGuard() {
      return mock(BusinessUnitRepriceLockGuard.class);
    }

    @Bean
    QuoteRequestController quoteRequestController(
        QuoteRequestQueryService service,
        QuoteCostingWorkbenchService workbenchService,
        QuoteBomConfirmationService confirmationService,
        QuotePriceTypeConfirmationService priceTypeConfirmationService,
        QuotePricePrepareWorkbenchService pricePrepareWorkbenchService,
        QuoteCostRunWorkbenchService costRunWorkbenchService,
        BusinessUnitRepriceLockGuard repriceLockGuard) {
      return new QuoteRequestController(
          service,
          workbenchService,
          confirmationService,
          priceTypeConfirmationService,
          pricePrepareWorkbenchService,
          costRunWorkbenchService,
          repriceLockGuard);
    }
  }
}
