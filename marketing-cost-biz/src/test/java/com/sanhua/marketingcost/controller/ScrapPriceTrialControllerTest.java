package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.scrap.ScrapPriceTrialService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class ScrapPriceTrialControllerTest {
  AnnotationConfigApplicationContext context;
  ScrapPriceTrialController controller;
  ScrapPriceTrialService trials;
  @BeforeEach void start() {
    context=new AnnotationConfigApplicationContext(Config.class);
    controller=context.getBean(ScrapPriceTrialController.class);trials=context.getBean(ScrapPriceTrialService.class);
  }
  @AfterEach void stop() { SecurityContextHolder.clearContext();context.close(); }
  @Test void listPermissionAllowsTrialWithoutEditAndUsesAuthenticatedBusinessUnit() {
    authenticate("price:scrap:list");
    var request=new ScrapPriceTrialService.Request(1L,Map.of());controller.trial(7,request);
    verify(trials).trial("HOUSEHOLD",7,request);
  }
  @Test void noListPermissionCannotTrial() {
    authenticate("price:scrap:edit");
    assertThatThrownBy(() -> controller.trial(7,new ScrapPriceTrialService.Request(1L,Map.of())))
        .isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(trials);
  }
  void authenticate(String permission) {
    var auth=new UsernamePasswordAuthenticationToken("test","",List.of(new SimpleGrantedAuthority(permission)));
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE,"HOUSEHOLD"));SecurityContextHolder.getContext().setAuthentication(auth);
  }
  @Configuration @EnableMethodSecurity static class Config {
    @Bean(name="ss") PermissionService permissions() {return new PermissionService();}
    @Bean ScrapPriceTrialService trials() {return mock(ScrapPriceTrialService.class);}
    @Bean ScrapPriceTrialController controller(ScrapPriceTrialService trials) {return new ScrapPriceTrialController(trials);}
  }
}
