package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomExclusionSummaryResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomResponse;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.QuoteEffectiveBomApplicationService;
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

class QuoteEffectiveBomApiSecurityTest {

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void quoteViewerCanQueryButCannotTriggerRebuild() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(SecurityConfig.class)) {
      QuoteEffectiveBomController controller = context.getBean(QuoteEffectiveBomController.class);
      QuoteEffectiveBomApplicationService service =
          context.getBean(QuoteEffectiveBomApplicationService.class);
      when(service.getEffectiveBom("OA-1", 11L)).thenReturn(response());
      authenticate("ingest:quote:list");

      assertThat(controller.getEffectiveBom("OA-1", 11L).isSuccess()).isTrue();
      assertThatThrownBy(() -> controller.rebuildPreview("OA-1", 11L))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void alternativeSelectorCanRebuildButCannotQueryWithoutViewPermission() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(SecurityConfig.class)) {
      QuoteEffectiveBomController controller = context.getBean(QuoteEffectiveBomController.class);
      QuoteEffectiveBomApplicationService service =
          context.getBean(QuoteEffectiveBomApplicationService.class);
      when(service.rebuildPreview("OA-1", 11L)).thenReturn(response());
      authenticate("quote:costing:bom:alternative-select");

      assertThat(controller.rebuildPreview("OA-1", 11L).isSuccess()).isTrue();
      assertThatThrownBy(() -> controller.getEffectiveBom("OA-1", 11L))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  private static void authenticate(String permission) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "quoter", null, List.of(new SimpleGrantedAuthority(permission))));
  }

  private static QuoteEffectiveBomResponse response() {
    return new QuoteEffectiveBomResponse(
        "DRAFT",
        "OA-1",
        11L,
        "2026-08",
        "P",
        "CUSTOMER-A",
        "OA_HEADER_CUSTOMER",
        "BOX",
        "210",
        "COMMERCIAL",
        1L,
        "RAW-1",
        null,
        null,
        11L,
        List.of(),
        List.of(),
        new QuoteEffectiveBomExclusionSummaryResponse(true, 0, Map.of()),
        List.of(),
        List.of());
  }

  @Configuration
  @EnableMethodSecurity
  static class SecurityConfig {

    @Bean("ss")
    PermissionService permissionService() {
      return new PermissionService();
    }

    @Bean
    QuoteEffectiveBomApplicationService applicationService() {
      return mock(QuoteEffectiveBomApplicationService.class);
    }

    @Bean
    QuoteEffectiveBomErrorMapper errorMapper() {
      return new QuoteEffectiveBomErrorMapper();
    }

    @Bean
    QuoteEffectiveBomController controller(
        QuoteEffectiveBomApplicationService service,
        QuoteEffectiveBomErrorMapper errorMapper) {
      return new QuoteEffectiveBomController(service, errorMapper);
    }
  }
}
