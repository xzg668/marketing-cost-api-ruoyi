package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.service.jjb.JjbVersionAccess;
import com.sanhua.marketingcost.service.reprice.MonthlyRepricePreparationService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class MonthlyRepricePreparationControllerTest {
  final JjbVersionAccess access=mock(JjbVersionAccess.class);
  final MonthlyRepricePreparationService service=mock(MonthlyRepricePreparationService.class);
  final MonthlyRepricePreparationController controller=new MonthlyRepricePreparationController(access,service);
  @BeforeEach void login(){var a=new UsernamePasswordAuthenticationToken("finance",null);a.setDetails(Map.of("businessUnitType","COMMERCIAL"));SecurityContextHolder.getContext().setAuthentication(a);}
  @AfterEach void logout(){SecurityContextHolder.clearContext();}
  @Test void sourceProductAndAuthenticatedUnitDefineTheTargetMonthContext(){
    var v=new QuoteCostRunVersion();v.setOaNo("OA");v.setProductCode("REAL-PRODUCT");v.setStatus("SUCCESS");var item=new OaFormItem();item.setId(7L);
    when(access.read("OA",7L,9L)).thenReturn(new JjbVersionAccess.Owned(v,new OaForm(),item,"COMMERCIAL"));
    var request=new MonthlyRepricePreparationController.Request("OA",7L,9L,YearMonth.of(2026,11),LocalDateTime.of(2026,11,1,0,0),"220",null,List.of());
    controller.preview(request,()->"finance");
    var ctx=org.mockito.ArgumentCaptor.forClass(com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.Context.class);verify(service).preview(ctx.capture(),eq(List.of()));
    assertThat(ctx.getValue().businessUnitType()).isEqualTo("COMMERCIAL");assertThat(ctx.getValue().productCode()).isEqualTo("REAL-PRODUCT");assertThat(ctx.getValue().consumerOrg()).isEqualTo("220");assertThat(ctx.getValue().month()).isEqualTo(YearMonth.of(2026,11));
  }
  @Test void deniedSourceOrMissingLoginCannotReachPriceReads(){
    var request=new MonthlyRepricePreparationController.Request("OTHER",7L,9L,YearMonth.of(2026,11),LocalDateTime.of(2026,11,1,0,0),"210",null,List.of());
    assertThatThrownBy(()->controller.preview(request,null)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(access,service);
    when(access.read("OTHER",7L,9L)).thenThrow(new org.springframework.security.access.AccessDeniedException("scope"));assertThatThrownBy(()->controller.preview(request,()->"finance")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);verifyNoInteractions(service);
  }
}
