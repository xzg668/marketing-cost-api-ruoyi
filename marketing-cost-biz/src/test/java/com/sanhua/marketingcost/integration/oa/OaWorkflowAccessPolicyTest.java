package com.sanhua.marketingcost.integration.oa;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.security.PermissionService;
import com.sanhua.marketingcost.service.costing.QuoteCostingSubmissionService;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class OaWorkflowAccessPolicyTest {
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }

  @ParameterizedTest
  @ValueSource(strings = {"bu_staff", "bu_director", "admin"})
  void enabledAccountWithoutEmployeeNumberCanCostIncomingQuote(String role) {
    var jdbc = mock(JdbcTemplate.class);
    var document = new HashMap<String, Object>();
    document.put("source_version", 1L);
    var account = new HashMap<String, Object>();
    account.put("user_id", 1L);
    account.put("employee_no", null);
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(call -> {
      String sql = call.getArgument(0);
      if (sql.contains("FROM lp_oa_quote_document")) return List.of(document);
      if (sql.contains("FROM sys_user")) return List.of(account);
      return List.of();
    });
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        "opened-account", null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    var policy = new OaWorkflowAccessPolicy(jdbc, new OaMessageCodec(new ObjectMapper()),
        new PermissionService(), mock(QuoteCostingSubmissionService.class));

    assertThat(policy.view(5).canCost()).as(role + " can calculate without an OA employee number").isTrue();
    assertThatCode(() -> policy.requireCosting(5)).doesNotThrowAnyException();
    assertThat(policy.view(5).canArrange()).isFalse();
  }
}
