package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.pricing.MaterialPriceSourceService;
import com.sanhua.marketingcost.service.pricing.MaterialPriceSourceRefresh;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class MaterialPriceSourceSecurityTest {
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @ParameterizedTest
  @ValueSource(strings = {"ROLE_bu_staff", "ROLE_bu_director", "ROLE_admin", "ROLE_BU_STAFF",
      "ROLE_finance_reviewer,price:material-sources:edit",
      "price:material-sources:list,quote:costing:bom:alternative-select"})
  void quoteStaffDirectorAndAdminCanSaveSingleAndBatchWithoutFinanceGrant(String authorities) {
    try (var context = new AnnotationConfigApplicationContext(Config.class)) {
      authenticate(authorities);
      var controller = context.getBean(MaterialPriceSourceController.class);
      assertThat(controller.change(null, () -> "quoter").isSuccess()).isTrue();
      assertThat(controller.changeBatch(List.of(), () -> "quoter").isSuccess()).isTrue();
      var service = context.getBean(MaterialPriceSourceService.class);
      verify(service).change(eq("COMMERCIAL"), isNull(), eq("quoter"));
      verify(service).changeBatch(eq("COMMERCIAL"), eq(List.of()), eq("quoter"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"price:material-sources:list", "ROLE_viewer", "quote:costing:bom:alternative-select"})
  void readOnlyAndIncompleteQuotationPermissionsCannotSave(String authorities) {
    try (var context = new AnnotationConfigApplicationContext(Config.class)) {
      authenticate(authorities);
      var controller = context.getBean(MaterialPriceSourceController.class);
      assertThatThrownBy(() -> controller.change(null, () -> "viewer")).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> controller.changeBatch(List.of(), () -> "viewer")).isInstanceOf(AccessDeniedException.class);
      verifyNoInteractions(context.getBean(MaterialPriceSourceService.class));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"ROLE_bu_staff,*:*:*", "price:material-sources:list,quote:costing:bom:alternative-select"})
  void savingPermissionDoesNotGrantSourceRefresh(String authorities) {
    try (var context = new AnnotationConfigApplicationContext(Config.class)) {
      authenticate(authorities);
      assertThatThrownBy(() -> context.getBean(MaterialPriceSourceController.class).refresh(() -> "quoter"))
          .isInstanceOf(AccessDeniedException.class);
      verifyNoInteractions(context.getBean(MaterialPriceSourceRefresh.class));
    }
  }

  private void authenticate(String authorities) {
    var authentication = new UsernamePasswordAuthenticationToken("actor", null,
        Arrays.stream(authorities.split(",")).map(SimpleGrantedAuthority::new).toList());
    authentication.setDetails(java.util.Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(authentication);
  }

  @Configuration @EnableMethodSecurity
  static class Config {
    @Bean("ss") PermissionService permissions() { return new PermissionService(); }
    @Bean MaterialPriceSourceService service() { return mock(MaterialPriceSourceService.class); }
    @Bean MaterialPriceSourceRefresh refresh() { return mock(MaterialPriceSourceRefresh.class); }
    @Bean MaterialPriceSourceController controller(MaterialPriceSourceService service, MaterialPriceSourceRefresh refresh) {
      return new MaterialPriceSourceController(service, refresh);
    }
  }
}
