package com.sanhua.marketingcost.controller;

import static com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceQuery.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sanhua.marketingcost.config.GlobalExceptionHandler;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MonthlyMaterialPriceControllerTest {
  AnnotationConfigApplicationContext context;
  MonthlyPriceQueryService service;
  MockMvc mvc;
  static final String BASE = "/api/v1/monthly-material-prices";

  @BeforeEach
  void setup() {
    context = new AnnotationConfigApplicationContext(Config.class);
    service = context.getBean(MonthlyPriceQueryService.class);
    mvc =
        MockMvcBuilders.standaloneSetup(context.getBean(MonthlyMaterialPriceController.class))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @AfterEach
  void close() {
    SecurityContextHolder.clearContext();
    context.close();
  }

  void login(String... permissions) {
    var auth =
        new UsernamePasswordAuthenticationToken(
            "finance", null, Arrays.stream(permissions).map(SimpleGrantedAuthority::new).toList());
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }

  @ParameterizedTest
  @ValueSource(strings = {"?month=2026-10", "/12", "/12/references"})
  void noPermissionCannotRead(String path) throws Exception {
    login("unrelated");
    mvc.perform(get(BASE + path)).andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void listAlwaysUsesAuthenticatedUnitAndAnExplicitMonth() throws Exception {
    login("price:monthly-material:list");
    var filter = new Filter("2026-10", "001a", "220", null, null, null, null, null, null, 2, 20);
    when(service.list("COMMERCIAL", filter)).thenReturn(new Page<>(List.of(), 0, 2, 20));
    mvc.perform(
            get(BASE)
                .param("month", "2026-10")
                .param("materialCode", "001a")
                .param("organization", "220")
                .param("page", "2")
                .param("businessUnitType", "HOUSEHOLD"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.page").value(2));
    verify(service).list("COMMERCIAL", filter);
  }

  @ParameterizedTest
  @ValueSource(strings = {"2026-13", "2026-1", "0000-01", "bad", ""})
  void invalidMonthsCannotQuery(String month) throws Exception {
    login("price:monthly-material:list");
    mvc.perform(get(BASE).param("month", month)).andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "page=0",
        "page=-1",
        "size=101",
        "size=0",
        "organization=230",
        "pricingType=MAKE",
        "taxBasis=UNKNOWN",
        "currency=cny"
      })
  void invalidScopeAndPaginationAreRejected(String input) throws Exception {
    login("price:monthly-material:list");
    var parts = input.split("=");
    mvc.perform(get(BASE).param("month", "2026-10").param(parts[0], parts[1]))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "price:monthly-material:references",
        "ingest:quote:list",
        "price:monthly-material:query"
      })
  void reverseReferencesRequireBothPermissions(String permission) throws Exception {
    login(permission);
    mvc.perform(get(BASE + "/12/references")).andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void bothReferencePermissionsCanReadExactMonthlyIdentity() throws Exception {
    login("price:monthly-material:references", "ingest:quote:list");
    when(service.references("COMMERCIAL", 12, 1, 20)).thenReturn(new Page<>(List.of(), 0, 1, 20));
    mvc.perform(get(BASE + "/12/references")).andExpect(status().isOk());
    verify(service).references("COMMERCIAL", 12, 1, 20);
  }

  @Test
  void noManualMutationRoutesExist() throws Exception {
    login("*:*:*");
    for (var method :
        List.of(
            org.springframework.http.HttpMethod.POST,
            org.springframework.http.HttpMethod.PUT,
            org.springframework.http.HttpMethod.DELETE))
      mvc.perform(request(method, BASE + "/12")).andExpect(status().isMethodNotAllowed());
    verifyNoInteractions(service);
  }

  @Configuration
  @EnableMethodSecurity
  static class Config {
    @Bean("ss")
    PermissionService permissions() {
      return new PermissionService();
    }

    @Bean
    MonthlyPriceQueryService service() {
      return mock(MonthlyPriceQueryService.class);
    }

    @Bean
    MonthlyMaterialPriceController controller(MonthlyPriceQueryService service) {
      return new MonthlyMaterialPriceController(service);
    }
  }
}
