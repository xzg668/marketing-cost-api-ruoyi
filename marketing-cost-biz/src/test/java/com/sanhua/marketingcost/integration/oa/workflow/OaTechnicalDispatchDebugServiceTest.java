package com.sanhua.marketingcost.integration.oa.workflow;

import static com.sanhua.marketingcost.integration.oa.workflow.OaTechnicalDispatchRequestBuilderTest.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.service.SysUserService;
import com.sanhua.marketingcost.service.oa.OaTechnicalDispatchDebugService;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class OaTechnicalDispatchDebugServiceTest {
  private final SysUserService users = mock(SysUserService.class);
  private final OaWorkflowClient client = mock(OaWorkflowClient.class);
  private final ValidatorFactory validation = Validation.buildDefaultValidatorFactory();
  private final OaTechnicalDispatchRequest request = command(product("1", "P", assignment("0002", "甲", "SALARY")));
  private OaTechnicalDispatchDebugService service;
  private SysUser user;

  @BeforeEach void setup() {
    user = new SysUser(); user.setUserId(7L); user.setUserName("quoter"); user.setEmployeeNo("0001");
    user.setStatus("0"); user.setDelFlag("0");
    when(users.findByUsername("quoter")).thenReturn(user);
    service = new OaTechnicalDispatchDebugService(users,
        new OaTechnicalDispatchRequestBuilder(new ObjectMapper(), validation.getValidator()), client);
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
        "quoter", null, List.of(new SimpleGrantedAuthority("ROLE_admin"))));
  }

  @AfterEach void clear() { validation.close(); SecurityContextHolder.clearContext(); }

  @Test void previewUsesCurrentAccountEmployeeNumberAndNeverSends() {
    var preview = service.preview(request);
    assertThat(preview.body().path("userid").asText()).isEqualTo("0001");
    assertThat(preview.operatorSource()).isEqualTo("CURRENT_USER");
    assertThat(preview.actorUserId()).isEqualTo(7L);
    assertThat(preview.query()).containsEntry("userType", "JOB_NUM");
    verifyNoInteractions(client);
    verify(users).findByUsername("quoter");
    verifyNoMoreInteractions(users);
  }

  @Test void explicitDebugOperatorIsRecordedSeparatelyFromLoggedInActor() {
    var explicit = new OaTechnicalDispatchRequest(request.requestId(), request.processCode(), "0009", request.products());
    var preview = service.preview(explicit);
    assertThat(preview.body().path("userid").asText()).isEqualTo("0009");
    assertThat(preview.operatorSource()).isEqualTo("DEBUG_INPUT");
    assertThat(preview.actorUserId()).isEqualTo(7L);
  }

  @Test void missingEmployeeNumberBlocksBeforeAnyNetworkCall() {
    user.setEmployeeNo(null);
    assertThatThrownBy(() -> service.send(request)).hasMessageContaining("当前账号未维护工号");
    verifyNoInteractions(client);
  }

  @Test void invalidScopeIsRejectedBeforeSending() {
    assertThatThrownBy(() -> service.send(command(product("1", "P", assignment("0002", "甲", "ALL")))))
        .hasMessageContaining("不支持的补录模块");
    verifyNoInteractions(client);
  }

  @Test void shortTaskSessionCannotUseDebugEvenWithElevatedAuthorities() {
    var auth = (UsernamePasswordAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
    auth.setDetails(Map.of("technicalDataTaskId", 3L));
    assertThatThrownBy(() -> service.send(request)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(users, client);
  }

  @Test void disabledAccountCannotUseDebug() {
    user.setStatus("1");
    assertThatThrownBy(() -> service.send(request)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(client);
  }

  @Test void sendReturnsRealOutcomeAndOnlyReadsIdentityFromBusinessDatabase() {
    var outcome = new OaWorkflowResult("call-1", OaWorkflowResult.Status.UNKNOWN, 504, "OA_RESPONSE_UNCONFIRMED",
        "结果未确认", null, null, 100);
    when(client.submit(any())).thenReturn(outcome);
    var sent = service.send(request);
    assertThat(sent.result()).isSameAs(outcome);
    verify(client).submit(sent.request().body());
    verify(users).findByUsername("quoter");
    verifyNoMoreInteractions(users, client);
  }
}
