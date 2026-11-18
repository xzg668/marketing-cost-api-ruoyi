package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.MakePartPriceTrialService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class MakePartPriceTrialControllerTest {
  AnnotationConfigApplicationContext context;
  MakePartPriceTrialController controller;
  MakePartPriceTrialService trials;
  @BeforeEach void start() {
    context=new AnnotationConfigApplicationContext(Config.class);
    controller=context.getBean(MakePartPriceTrialController.class);trials=context.getBean(MakePartPriceTrialService.class);
  }
  @AfterEach void stop() { SecurityContextHolder.clearContext();context.close(); }
  @Test void listPermissionAllowsTrialWithoutEditAndUsesAuthenticatedBusinessUnit() {
    authenticate("price:make-part-calc:list");
    var request=new MakePartPriceTrialService.Request("basis",new java.math.BigDecimal("100000"));controller.trial(7,request);
    verify(trials).calculate(7,request);
  }
  @Test void noListPermissionCannotTrial() {
    authenticate("price:make-part-calc:generate");
    assertThatThrownBy(() -> controller.trial(7,new MakePartPriceTrialService.Request("basis",new java.math.BigDecimal("100000"))))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(trials);
  }
  void authenticate(String permission) {
    var auth=new UsernamePasswordAuthenticationToken("test","",List.of(new SimpleGrantedAuthority(permission)));
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE,"HOUSEHOLD"));SecurityContextHolder.getContext().setAuthentication(auth);
  }
  @Configuration @EnableMethodSecurity static class Config {
    @Bean(name="ss") PermissionService permissions() {return new PermissionService();}
    @Bean MakePartPriceTrialService trials() {return mock(MakePartPriceTrialService.class);}
    @Bean MakePartPriceTrialController controller(MakePartPriceTrialService trials) {return new MakePartPriceTrialController(trials);}
  }
}
