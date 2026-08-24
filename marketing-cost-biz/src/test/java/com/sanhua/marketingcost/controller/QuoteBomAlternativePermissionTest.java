package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionRequest;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSummaryResponse;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.QuoteBomAlternativeApplicationService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeFeatureSwitch;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

class QuoteBomAlternativePermissionTest {

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void quoteViewerCanSeeGroupsButCannotSelectOrReadSelectionHistory() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(SecurityConfig.class)) {
      QuoteBomAlternativeController controller =
          context.getBean(QuoteBomAlternativeController.class);
      QuoteBomAlternativeApplicationService service =
          context.getBean(QuoteBomAlternativeApplicationService.class);
      when(service.getAlternativeGroups("OA-QBA-10", 10L, "2026-07"))
          .thenReturn(
              new QuoteBomAlternativeSummaryResponse(
                  "2026-07", 0, 0, false, List.of()));
      authenticate("ingest:quote:list");

      assertThat(
              controller
                  .getAlternativeGroups("OA-QBA-10", 10L, "2026-07")
                  .isSuccess())
          .isTrue();
      assertThatThrownBy(
              () ->
                  controller.saveSelection(
                      "OA-QBA-10", 10L, "GROUP", request(), null))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () ->
                  controller.getSelectionHistory(
                      "OA-QBA-10", 10L, "GROUP", "2026-07"))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void alternativeSelectorCanModifyAndReadHistory() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(SecurityConfig.class)) {
      QuoteBomAlternativeController controller =
          context.getBean(QuoteBomAlternativeController.class);
      QuoteBomAlternativeApplicationService service =
          context.getBean(QuoteBomAlternativeApplicationService.class);
      when(service.saveSelection(
              "OA-QBA-10", 10L, "GROUP", request(), "quoter"))
          .thenReturn(
              new QuoteBomAlternativeSelectionResponse(
                  "GROUP",
                  2,
                  "ALT",
                  "ALTERNATIVE",
                  "MANUAL_ALTERNATIVE",
                  false,
                  true,
                  List.of()));
      when(service.getSelectionHistory(
              "OA-QBA-10", 10L, "GROUP", "2026-07"))
          .thenReturn(List.of());
      authenticate("quote:costing:bom:alternative-select");
      var authentication = SecurityContextHolder.getContext().getAuthentication();

      assertThat(
              controller
                  .saveSelection(
                      "OA-QBA-10",
                      10L,
                      "GROUP",
                      request(),
                      authentication)
                  .isSuccess())
          .isTrue();
      assertThat(
              controller
                  .getSelectionHistory(
                      "OA-QBA-10", 10L, "GROUP", "2026-07")
                  .isSuccess())
          .isTrue();
    }
  }

  @Test
  void permissionSeedCreatesDedicatedSelectorPermissionForAdmin()
      throws Exception {
    try (InputStream input =
        getClass()
            .getResourceAsStream(
                "/db/V201__quote_bom_alternative_selection_permission.sql")) {
      assertThat(input).isNotNull();
      String sql =
          new String(input.readAllBytes(), StandardCharsets.UTF_8);
      assertThat(sql)
          .contains("quote:costing:bom:alternative-select")
          .contains("INSERT INTO sys_menu")
          .contains("INSERT IGNORE INTO sys_role_menu")
          .contains("(1, 40482)");
    }
  }

  private static QuoteBomAlternativeSelectionRequest request() {
    return new QuoteBomAlternativeSelectionRequest(
        "2026-07", "ALT", 1, "BUILD-1", "选择替代件");
  }

  private static void authenticate(String permission) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "quoter",
                null,
                List.of(new SimpleGrantedAuthority(permission))));
  }

  @Configuration
  @EnableMethodSecurity
  static class SecurityConfig {

    @Bean("ss")
    PermissionService permissionService() {
      return new PermissionService();
    }

    @Bean
    QuoteBomAlternativeApplicationService applicationService() {
      return mock(QuoteBomAlternativeApplicationService.class);
    }

    @Bean
    QuoteBomAlternativeErrorMapper errorMapper() {
      return new QuoteBomAlternativeErrorMapper();
    }

    @Bean
    QuoteBomAlternativeController controller(
        QuoteBomAlternativeApplicationService service,
        QuoteBomAlternativeErrorMapper errorMapper) {
      return new QuoteBomAlternativeController(
          service,
          errorMapper,
          new QuoteBomAlternativeFeatureSwitch(true));
    }
  }
}
