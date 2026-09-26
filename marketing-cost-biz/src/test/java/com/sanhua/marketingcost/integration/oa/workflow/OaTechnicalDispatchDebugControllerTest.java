package com.sanhua.marketingcost.integration.oa.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.config.GlobalExceptionHandler;
import com.sanhua.marketingcost.controller.OaTechnicalDispatchDebugController;
import com.sanhua.marketingcost.service.oa.OaTechnicalDispatchDebugService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OaTechnicalDispatchDebugControllerTest {
  private static final String ROOT = "/api/v1/integration/oa/debug/technical-dispatch";
  private final OaTechnicalDispatchDebugService service = mock(OaTechnicalDispatchDebugService.class);
  private final ApplicationContextRunner context = new ApplicationContextRunner()
      .withUserConfiguration(Config.class).withBean(OaTechnicalDispatchDebugService.class, () -> service);
  private final ObjectMapper json = new ObjectMapper();

  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @Test void debugRoutesAreNotRegisteredByDefault() {
    context.run(application -> assertThat(application).doesNotHaveBean(OaTechnicalDispatchDebugController.class));
  }

  @ParameterizedTest @ValueSource(strings = {"preview", "send"})
  void ordinaryBusinessUserCannotInvokeDebug(String action) {
    context.withPropertyValues("integration.oa-workflow.debug-enabled=true").run(application -> {
      var mvc = MockMvcBuilders.standaloneSetup(application.getBean(OaTechnicalDispatchDebugController.class))
          .setControllerAdvice(new GlobalExceptionHandler()).build();
      // 已登录报价员或技术管理员权限均不能替代系统管理员角色。
      for (String authority : List.of("ingest:quote:cost-run:execute", "technical:data:admin:operate", "ROLE_bu_staff", "*:*:*")) {
        authenticate(authority);
        mvc.perform(post(ROOT + "/" + action).contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isForbidden());
      }
      verifyNoInteractions(service);
    });
  }

  @ParameterizedTest @ValueSource(strings = {"preview", "send"})
  void systemAdminCanCallBothExplicitActions(String action) {
    context.withPropertyValues("integration.oa-workflow.debug-enabled=true").run(application -> {
      var mvc = MockMvcBuilders.standaloneSetup(application.getBean(OaTechnicalDispatchDebugController.class))
          .setControllerAdvice(new GlobalExceptionHandler()).build();
      authenticate("ROLE_admin");
      mvc.perform(post(ROOT + "/" + action).contentType(MediaType.APPLICATION_JSON).content(body()))
          .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
      if (action.equals("preview")) { verify(service).preview(any()); verify(service, never()).send(any()); }
      else { verify(service).send(any()); verify(service, never()).preview(any()); }
    });
  }

  @Test void invalidBodyIsRejectedBeforeApplicationService() {
    context.withPropertyValues("integration.oa-workflow.debug-enabled=true").run(application -> {
      var mvc = MockMvcBuilders.standaloneSetup(application.getBean(OaTechnicalDispatchDebugController.class))
          .setControllerAdvice(new GlobalExceptionHandler()).build();
      authenticate("ROLE_admin");
      mvc.perform(post(ROOT + "/send").contentType(MediaType.APPLICATION_JSON)
          .content("{\"requestId\":\"1\",\"processCode\":\"FI-SC-005\",\"products\":[]}"))
          .andExpect(status().isBadRequest());
      verifyNoInteractions(service);
    });
  }

  private String body() throws Exception {
    return json.writeValueAsString(OaTechnicalDispatchRequestBuilderTest.command(
        OaTechnicalDispatchRequestBuilderTest.product("1", "A",
            OaTechnicalDispatchRequestBuilderTest.assignment("001", "甲", "SALARY"))));
  }

  private void authenticate(String authority) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        "admin", null, List.of(new SimpleGrantedAuthority(authority))));
  }

  @Configuration(proxyBeanMethods = false)
  @EnableMethodSecurity
  @Import(OaTechnicalDispatchDebugController.class)
  static class Config {}
}
