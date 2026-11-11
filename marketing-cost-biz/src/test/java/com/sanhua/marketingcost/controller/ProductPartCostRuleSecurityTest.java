package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.ProductPartCostRuleService;
import com.sanhua.marketingcost.service.ProductPartCostRuleExcel;
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

class ProductPartCostRuleSecurityTest {
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @ParameterizedTest @ValueSource(strings={"ROLE_admin","ROLE_bu_director","ROLE_ADMIN","ROLE_BU_DIRECTOR"})
  void actualRolesCanManageAllOperations(String role) throws Exception {
    try(var context=new AnnotationConfigApplicationContext(Config.class)) {
      authenticate(role); var controller=context.getBean(ProductPartCostRuleController.class);
      assertThat(controller.list(new ProductPartCostRuleService.Query(null,null,null,null,null,null)).isSuccess()).isTrue();
      assertThat(controller.get(1).isSuccess()).isTrue();
      assertThat(controller.create(null).isSuccess()).isTrue();
      assertThat(controller.update(1,null).isSuccess()).isTrue();
      assertThat(controller.delete(1,1).isSuccess()).isTrue();
      assertThat(controller.template().getStatusCode().is2xxSuccessful()).isTrue();
      assertThat(controller.importExcel(new org.springframework.mock.web.MockMultipartFile("file","rules.xlsx",null,new byte[]{1})).isSuccess()).isTrue();
    }
  }

  @ParameterizedTest @ValueSource(strings={"ROLE_bu_staff","*:*:*","rules:product-part-cost:list","ROLE_finance_reviewer"})
  void otherRolesAndWildcardCannotAccessEvenReads(String role) {
    try(var context=new AnnotationConfigApplicationContext(Config.class)) {
      authenticate(role); var controller=context.getBean(ProductPartCostRuleController.class);
      assertThatThrownBy(() -> controller.list(new ProductPartCostRuleService.Query(null,null,null,null,null,null))).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> controller.get(1)).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> controller.create(null)).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> controller.update(1,null)).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> controller.delete(1,1)).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(controller::template).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(() -> controller.importExcel(null)).isInstanceOf(AccessDeniedException.class);
      verifyNoInteractions(context.getBean(ProductPartCostRuleService.class));
      verifyNoInteractions(context.getBean(ProductPartCostRuleExcel.class));
    }
  }
  private void authenticate(String role) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("actor",null,List.of(new SimpleGrantedAuthority(role))));
  }
  @Configuration @EnableMethodSecurity
  static class Config {
    @Bean("ss") PermissionService permissions() { return new PermissionService(); }
    @Bean ProductPartCostRuleService service() { return mock(ProductPartCostRuleService.class); }
    @Bean ProductPartCostRuleExcel excel() throws Exception {
      var excel=mock(ProductPartCostRuleExcel.class);
      when(excel.parse(any())).thenReturn(new ProductPartCostRuleExcel.Parsed(List.of(),List.of())); return excel;
    }
    @Bean ProductPartCostRuleController controller(ProductPartCostRuleService service, ProductPartCostRuleExcel excel) { return new ProductPartCostRuleController(service,excel); }
  }
}
