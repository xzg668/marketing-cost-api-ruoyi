package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceWorkflowService;
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

class MonthlyRepriceWorkflowControllerTest {
  @Configuration @EnableMethodSecurity static class Config {
    @Bean PermissionService ss(){return new PermissionService();}
    @Bean MonthlyRepriceWorkflowService workflow(){return mock(MonthlyRepriceWorkflowService.class);}
    @Bean MonthlyRepriceWorkflowController controller(MonthlyRepriceWorkflowService workflow){return new MonthlyRepriceWorkflowController(workflow);}
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  @ParameterizedTest @ValueSource(strings={"*:*:*","price:monthly-reprice:operate","ROLE_bu_staff","ingest:quote:cost-run:execute"})
  void allThreeCommandsRejectGenericAndOldAuthorities(String authority){
    try(var context=new AnnotationConfigApplicationContext(Config.class)){
      auth(authority);var controller=context.getBean(MonthlyRepriceWorkflowController.class);
      assertThatThrownBy(controller::start).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(()->controller.retry("batch")).isInstanceOf(AccessDeniedException.class);
      assertThatThrownBy(()->controller.confirm("batch",new MonthlyRepriceWorkflowController.Confirmation(1))).isInstanceOf(AccessDeniedException.class);
      verifyNoInteractions(context.getBean(MonthlyRepriceWorkflowService.class));
    }
  }
  @ParameterizedTest @ValueSource(strings={"ROLE_ADMIN","ROLE_admin","ROLE_BU_DIRECTOR","ROLE_bu_director"})
  void adminAndDirectorCanReadAndUseAllThreeCommands(String authority){
    try(var context=new AnnotationConfigApplicationContext(Config.class)){
      auth(authority);var controller=context.getBean(MonthlyRepriceWorkflowController.class);
      controller.current();controller.start();controller.retry("batch");controller.confirm("batch",new MonthlyRepriceWorkflowController.Confirmation(7));
      var workflow=context.getBean(MonthlyRepriceWorkflowService.class);
      verify(workflow).current();verify(workflow).start();verify(workflow).retry("batch");verify(workflow).confirm("batch",7);
    }
  }
  private static void auth(String authority){SecurityContextHolder.getContext().setAuthentication(
      new UsernamePasswordAuthenticationToken("fixture",null,List.of(new SimpleGrantedAuthority(authority))));}
}
