package com.sanhua.marketingcost.controller;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sanhua.marketingcost.config.GlobalExceptionHandler;
import com.sanhua.marketingcost.dto.MaterialPriceTypePageResponse;
import com.sanhua.marketingcost.dto.MaterialPriceTypeRequest;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.MaterialPriceTypeService;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MaterialPriceTypeControllerTest {
  private AnnotationConfigApplicationContext context;
  private MaterialPriceTypeService service;
  private MockMvc mvc;

  @BeforeEach
  void setup() {
    context = new AnnotationConfigApplicationContext(Config.class);
    service = context.getBean(MaterialPriceTypeService.class);
    mvc = MockMvcBuilders.standaloneSetup(context.getBean(MaterialPriceTypeController.class))
        .setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @AfterEach
  void clear() {
    SecurityContextHolder.clearContext();
    context.close();
  }

  @Test
  void listForwardsMonthlyFiltersWithoutWriting() throws Exception {
    login("base:material-price-type:list");
    when(service.page("M1", "2026-10", "220", 2, 10))
        .thenReturn(new MaterialPriceTypePageResponse(0, List.of()));
    mvc.perform(get("/api/v1/material-price-types").param("period", "2026-10")
            .param("materialCode", "M1").param("priceOrgCode", "220")
            .param("page", "2").param("pageSize", "10"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.total").value(0));
    verify(service).page("M1", "2026-10", "220", 2, 10);
    verifyNoMoreInteractions(service);
  }

  @Test
  void monthIsRequiredAndInvalidMonthReturnsBadRequest() throws Exception {
    login("base:material-price-type:list");
    mvc.perform(get("/api/v1/material-price-types"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
    when(service.page(null, "2026-13", null, 1, 20))
        .thenThrow(new IllegalArgumentException("价格月份格式必须为 yyyy-MM"));
    mvc.perform(get("/api/v1/material-price-types").param("period", "2026-13"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
  }

  @Test
  void staleRevisionReturnsConflictWithRefreshMessage() throws Exception {
    login("base:material-price-type:edit");
    when(service.update(eq(7L), any(MaterialPriceTypeRequest.class)))
        .thenThrow(new OptimisticLockingFailureException("取价顺序已被修改，请刷新后重试"));
    mvc.perform(patch("/api/v1/material-price-types/7/priority")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":\"old\",\"restoreDefault\":true}"))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409))
        .andExpect(jsonPath("$.msg").value("取价顺序已被修改，请刷新后重试"));
  }

  @Test
  void readPermissionCannotChangePriority() throws Exception {
    login("base:material-price-type:list");
    mvc.perform(patch("/api/v1/material-price-types/7/priority")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":\"v\",\"restoreDefault\":true}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void listPermissionIsRequired() throws Exception {
    login("base:material-price-type:edit");
    mvc.perform(get("/api/v1/material-price-types").param("period", "2026-10"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(service);
  }

  @Test
  void unknownTypeCannotReachService() throws Exception {
    login("base:material-price-type:edit");
    mvc.perform(patch("/api/v1/material-price-types/7/priority")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedRevision\":\"v\",\"order\":[\"UNKNOWN\"]}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(service);
  }

  private void login(String permission) {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        "finance", null, List.of(new SimpleGrantedAuthority(permission))));
  }

  @Configuration
  @EnableMethodSecurity
  static class Config {
    @Bean("ss") PermissionService permissions() { return new PermissionService(); }
    @Bean MaterialPriceTypeService service() { return mock(MaterialPriceTypeService.class); }
    @Bean MaterialPriceTypeController controller(MaterialPriceTypeService service) {
      return new MaterialPriceTypeController(service);
    }
  }
}
