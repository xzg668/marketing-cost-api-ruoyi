package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.security.*;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryTrialService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class HouseholdInquiryTrialControllerTest {
  AnnotationConfigApplicationContext context;
  HouseholdInquiryTrialController controller;
  HouseholdInquiryTrialService trials;
  @BeforeEach void start() {
    context=new AnnotationConfigApplicationContext(Config.class);
    controller=context.getBean(HouseholdInquiryTrialController.class);trials=context.getBean(HouseholdInquiryTrialService.class);
  }
  @AfterEach void stop() {SecurityContextHolder.clearContext();context.close();}
  @Test void queryPermissionCanTrialWithoutImportOrDownloadAndUsesAuthenticatedScope() {
    authenticate("price:household-inquiry:query");
    var request=new HouseholdInquiryTrialService.Request("2026-11",null,null);
    controller.trial(7,request);verify(trials).trial("HOUSEHOLD",7,request);
  }
  @Test void importOnlyAndListOnlyCannotTrial() {
    for(String permission:List.of("price:household-inquiry:import","price:household-inquiry:list")) {
      authenticate(permission);
      assertThatThrownBy(()->controller.trial(7,new HouseholdInquiryTrialService.Request("2026-11",null,null)))
          .isInstanceOf(AccessDeniedException.class);
    }
    verifyNoInteractions(trials);
  }
  void authenticate(String permission) {
    var auth=new UsernamePasswordAuthenticationToken("test","",List.of(new SimpleGrantedAuthority(permission)));
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE,"HOUSEHOLD"));SecurityContextHolder.getContext().setAuthentication(auth);
  }
  @Configuration @EnableMethodSecurity static class Config {
    @Bean(name="ss") PermissionService permissions() {return new PermissionService();}
    @Bean HouseholdInquiryTrialService trials() {return mock(HouseholdInquiryTrialService.class);}
    @Bean HouseholdInquiryTrialController controller(HouseholdInquiryTrialService trials) {return new HouseholdInquiryTrialController(trials);}
  }
}
