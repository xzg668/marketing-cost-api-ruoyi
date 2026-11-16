package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.materialshape.QuoteOrderMaterialShapes;
import java.security.Principal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class QuoteOrderMaterialShapePermissionTest {
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  private boolean allowed(String... authorities) throws Exception {
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("quote-user", "unused",
        Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    var context=new StandardEvaluationContext();context.setBeanResolver((ctx,name) -> new PermissionService());
    String expression=QuoteOrderMaterialShapeController.class.getMethod("change",String.class,QuoteOrderMaterialShapes.Change.class,Principal.class)
        .getAnnotation(PreAuthorize.class).value();
    return Boolean.TRUE.equals(new SpelExpressionParser().parseExpression(expression).getValue(context,Boolean.class));
  }
  @Test void staffDirectorAdminAndExplicitQuoterCanChangeOrderShapes() throws Exception {
    for(String role:List.of("bu_staff","bu_director","admin")) assertThat(allowed("ingest:quote:list","ROLE_"+role)).as(role).isTrue();
    assertThat(allowed("ingest:quote:list","quote:costing:bom:alternative-select")).isTrue();
    assertThat(allowed("ingest:quote:list","ROLE_finance_reviewer","price:material-sources:edit")).isTrue();
  }
  @Test void quoteReadAndEditAuthorizationAreBothRequired() throws Exception {
    assertThat(allowed("ingest:quote:list","ROLE_viewer")).isFalse();
    assertThat(allowed("ROLE_bu_staff")).isFalse();
    assertThat(allowed("ingest:quote:list","price:material-sources:edit","ROLE_viewer")).isFalse();
    assertThat(allowed()).isFalse();
  }
}
