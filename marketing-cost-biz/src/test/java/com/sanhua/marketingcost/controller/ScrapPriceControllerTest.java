package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.scrap.ScrapPriceImportService;
import com.sanhua.marketingcost.service.scrap.ScrapPriceQueryService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ScrapPriceControllerTest {
  AnnotationConfigApplicationContext context;
  ScrapPriceController controller;
  ScrapPriceImportService imports;
  ScrapPriceQueryService query;
  final MockMultipartFile file =
      new MockMultipartFile("file", "source.xlsx", "application/octet-stream", new byte[] {1});

  @BeforeEach
  void setup() {
    context = new AnnotationConfigApplicationContext(Config.class);
    controller = context.getBean(ScrapPriceController.class);
    imports = context.getBean(ScrapPriceImportService.class);
    query = context.getBean(ScrapPriceQueryService.class);
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    context.close();
  }

  @Test
  void listPermissionDoesNotAllowImportOrEdit() throws Exception {
    authenticate("price:scrap:list");
    controller.list("2026-10", null, null, null, 1, 20);
    verify(query).list("HOUSEHOLD", "2026-10", null, null, null, 1, 20);
    assertThatThrownBy(() -> controller.preview(file, "2026-10"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.confirm(file, "2026-10", "token", () -> "test"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.edit(1L, null, () -> "test"))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(imports);
  }

  @Test
  void importUsesAuthenticatedUnitAndRequiresAnActor() throws Exception {
    authenticate("price:scrap:import");
    controller.preview(file, "2026-10");
    verify(imports).preview(new byte[] {1}, "source.xlsx", "HOUSEHOLD", "2026-10");
    controller.confirm(file, "2026-10", "token", () -> "finance");
    verify(imports)
        .confirm(new byte[] {1}, "source.xlsx", "HOUSEHOLD", "2026-10", "token", "finance");
    assertThatThrownBy(() -> controller.confirm(file, "2026-10", "token", null))
        .hasMessageContaining("登录");
  }

  @Test
  void noPermissionCannotReadPrices() {
    authenticate("unrelated");
    assertThatThrownBy(() -> controller.list("2026-10", null, null, null, 1, 20))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> controller.detail(1L)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(query);
  }

  private void authenticate(String permission) {
    var authentication =
        new UsernamePasswordAuthenticationToken(
            "test", "", List.of(new SimpleGrantedAuthority(permission)));
    authentication.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "HOUSEHOLD"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Configuration
  @EnableMethodSecurity
  static class Config {
    @Bean(name = "ss")
    PermissionService permissions() {
      return new PermissionService();
    }

    @Bean
    ScrapPriceImportService imports() {
      return mock(ScrapPriceImportService.class);
    }

    @Bean
    ScrapPriceQueryService query() {
      return mock(ScrapPriceQueryService.class);
    }

    @Bean
    ScrapPriceController controller(ScrapPriceImportService imports, ScrapPriceQueryService query) {
      return new ScrapPriceController(imports, query);
    }
  }
}
