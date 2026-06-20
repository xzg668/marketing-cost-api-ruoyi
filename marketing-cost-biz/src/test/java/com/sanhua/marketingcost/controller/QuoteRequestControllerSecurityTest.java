package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestListItemResponse;
import com.sanhua.marketingcost.security.PermissionService;
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
    QuoteRequestController quoteRequestController(
        QuoteRequestQueryService service,
        QuoteCostingWorkbenchService workbenchService,
        QuoteBomConfirmationService confirmationService,
        QuotePriceTypeConfirmationService priceTypeConfirmationService,
        QuotePricePrepareWorkbenchService pricePrepareWorkbenchService,
        QuoteCostRunWorkbenchService costRunWorkbenchService) {
      return new QuoteRequestController(
          service,
          workbenchService,
          confirmationService,
          priceTypeConfirmationService,
          pricePrepareWorkbenchService,
          costRunWorkbenchService);
    }
  }
}
