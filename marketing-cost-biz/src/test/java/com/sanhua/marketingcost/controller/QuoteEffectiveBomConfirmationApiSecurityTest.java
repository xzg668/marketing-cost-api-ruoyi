package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomConfirmResponse;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.QuoteEffectiveBomConfirmationService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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

class QuoteEffectiveBomConfirmationApiSecurityTest {

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void bomConfirmerCanUseBothMutationRoutes() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(SecurityConfig.class)) {
      QuoteEffectiveBomConfirmationController controller =
          context.getBean(QuoteEffectiveBomConfirmationController.class);
      QuoteEffectiveBomConfirmationService service =
          context.getBean(QuoteEffectiveBomConfirmationService.class);
      when(service.confirm("OA-1", 11L, null))
          .thenReturn(new QuoteEffectiveBomConfirmResponse(
              1L, "qeb_BUILD_1", false, false, 1, 1, null));
      when(service.prepareCostingBom("OA-1", 11L)).thenReturn(costing());
      when(service.rebuildCostingFromEffective("OA-1", 11L)).thenReturn(costing());
      authenticate("quote:costing:bom:confirm");

      assertThat(controller.confirm("OA-1", 11L, null).isSuccess()).isTrue();
      assertThat(controller.prepareCostingBom("OA-1", 11L).isSuccess()).isTrue();
      assertThat(controller.rebuildFromEffective("OA-1", 11L).isSuccess()).isTrue();
    }
  }

  @Test
  void viewerAndAlternativeSelectorCannotConfirmOrBuildCostingRows() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(SecurityConfig.class)) {
      QuoteEffectiveBomConfirmationController controller =
          context.getBean(QuoteEffectiveBomConfirmationController.class);
      authenticate("ingest:quote:list");

      assertThatThrownBy(() -> controller.confirm("OA-1", 11L, null))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> controller.prepareCostingBom("OA-1", 11L))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> controller.rebuildFromEffective("OA-1", 11L))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  private static void authenticate(String permission) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "quoter", null, List.of(new SimpleGrantedAuthority(permission))));
  }

  private static QuoteBomCostingBuildResponse costing() {
    return new QuoteBomCostingBuildResponse(
        1L, null, 11L, "OA-1", "P", "NON_BARE", "2026-08", "qeb_BUILD_1",
        1, 1, 0, Map.of(), List.of(), LocalDateTime.now());
  }

  @Configuration
  @EnableMethodSecurity
  static class SecurityConfig {

    @Bean("ss")
    PermissionService permissionService() {
      return new PermissionService();
    }

    @Bean
    QuoteEffectiveBomConfirmationService service() {
      return mock(QuoteEffectiveBomConfirmationService.class);
    }

    @Bean
    QuoteEffectiveBomErrorMapper errorMapper() {
      return new QuoteEffectiveBomErrorMapper();
    }

    @Bean
    QuoteEffectiveBomConfirmationController controller(
        QuoteEffectiveBomConfirmationService service,
        QuoteEffectiveBomErrorMapper errorMapper) {
      return new QuoteEffectiveBomConfirmationController(service, errorMapper);
    }
  }
}
