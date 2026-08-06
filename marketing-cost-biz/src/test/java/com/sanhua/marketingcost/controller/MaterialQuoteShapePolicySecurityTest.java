package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyEnabledRequest;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyRequest;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.MaterialQuoteShapePolicyService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class MaterialQuoteShapePolicySecurityTest {

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("只有查看权限时可查询，但不能新增、修改、启停或删除")
  void viewerCannotMutate() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(SecurityConfig.class)) {
      MaterialQuoteShapePolicyController controller =
          context.getBean(MaterialQuoteShapePolicyController.class);
      authenticate("bom-data:material-shape-policy:list");

      assertThat(
              controller
                  .list(null, null, null, null, null, null, null, null)
                  .isSuccess())
          .isTrue();
      assertThatThrownBy(() -> controller.create(new MaterialQuoteShapePolicyRequest()))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () -> controller.update(1L, new MaterialQuoteShapePolicyRequest()))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(
              () -> controller.setEnabled(1L, enabledRequest(0)))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> controller.delete(1L))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  @DisplayName("启停权限只允许启停，不能编辑或删除")
  void togglerCannotEdit() {
    try (AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext(SecurityConfig.class)) {
      MaterialQuoteShapePolicyController controller =
          context.getBean(MaterialQuoteShapePolicyController.class);
      MaterialQuoteShapePolicyService service =
          context.getBean(MaterialQuoteShapePolicyService.class);
      var row = MaterialQuoteShapePolicyControllerTest.response(1L, "PURCHASE", 0);
      when(service.get(1L)).thenReturn(row);
      when(service.setEnabled(1L, 0)).thenReturn(row);
      authenticate("bom-data:material-shape-policy:toggle");

      assertThat(controller.setEnabled(1L, enabledRequest(0)).isSuccess()).isTrue();
      assertThatThrownBy(
              () -> controller.update(1L, new MaterialQuoteShapePolicyRequest()))
          .isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> controller.delete(1L))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  private static MaterialQuoteShapePolicyEnabledRequest enabledRequest(int enabled) {
    MaterialQuoteShapePolicyEnabledRequest request =
        new MaterialQuoteShapePolicyEnabledRequest();
    request.setEnabled(enabled);
    return request;
  }

  private static void authenticate(String permission) {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "finance",
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
    MaterialQuoteShapePolicyService policyService() {
      return mock(MaterialQuoteShapePolicyService.class);
    }

    @Bean
    MaterialQuoteShapePolicyController controller(
        MaterialQuoteShapePolicyService service) {
      return new MaterialQuoteShapePolicyController(service);
    }
  }
}
