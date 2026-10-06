package com.sanhua.marketingcost.integration.oa.oauth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.integration.oa.OaIntegrationProperties;
import com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectoryProperties;
import com.sanhua.marketingcost.security.JwtUtils;
import java.util.*;
import java.net.URI;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

class OaOAuthServiceTest {
  OaOAuthClient client = mock(OaOAuthClient.class);
  JdbcTemplate jdbc = mock(JdbcTemplate.class);
  JwtUtils jwt = new JwtUtils(Base64.getEncoder().encodeToString(new byte[32]),7200000);
  OaOAuthService service;
  String key="a".repeat(43);
  @BeforeEach void setup() {
    var integration = new OaIntegrationProperties(); integration.getOutbound().setFrontendBaseUrl("http://quote.test");
    var directory = new OaPersonDirectoryProperties(); directory.setSourceSystem("OA"); directory.setEnvironment("TEST");
    service = new OaOAuthService(client,integration,directory,jdbc,jwt);
    when(jdbc.queryForList(anyString(),eq(String.class),eq(10L),eq("OA"),eq("TEST"))).thenReturn(List.of("COMMERCIAL"));
    when(client.authorizeUrl(anyString())).thenAnswer(i -> "http://oa.test?state="+i.getArgument(0));
    when(client.authenticate("code")).thenReturn(new OaOAuthClient.Identity("00123","技术员",7200));
  }
  String start() {
    return URI.create(service.begin(new OaOAuthService.BeginRequest(10L,null,null,key)).authorizeUrl()).getQuery().substring(6);
  }
  @Test void bindsCallbackAndOneUseHandoffToOriginalBrowserAndDocument() {
    String state=start();
    String redirect=service.callback("code",state);
    String ticket=redirect.split("#ticket=")[1];
    assertThatThrownBy(() -> service.callback("code",state)).isInstanceOf(OaOAuthException.class);
    assertThatThrownBy(() -> service.exchange(new OaOAuthService.ExchangeRequest(ticket,"b".repeat(43))))
        .isInstanceOf(OaOAuthException.class);
    var result=service.exchange(new OaOAuthService.ExchangeRequest(ticket,key));
    assertThat(result.entryPath()).isEqualTo("/collaboration/technical-data/forms/10");
    assertThat(jwt.isOaSession(result.accessToken())).isTrue();
    assertThat(jwt.getUsernameFromToken(result.accessToken())).isEqualTo("00123");
    assertThat(jwt.extractOaFormId(result.accessToken())).isEqualTo(10);
    assertThatThrownBy(() -> service.exchange(new OaOAuthService.ExchangeRequest(ticket,key))).isInstanceOf(OaOAuthException.class);
    verify(client,times(1)).authenticate("code");
  }
  @Test void forgedStateDoesNotContactOa() {
    assertThatThrownBy(() -> service.callback("code","x".repeat(43))).isInstanceOf(OaOAuthException.class);
    verifyNoInteractions(client);
  }
  @Test void noCallerSuppliedRedirectAndRejectsUnknownDocument() {
    assertThatThrownBy(() -> service.begin(new OaOAuthService.BeginRequest(20L,null,null,key))).isInstanceOf(OaOAuthException.class);
    assertThatThrownBy(() -> service.begin(new OaOAuthService.BeginRequest(10L,null,"../../admin",key))).isInstanceOf(OaOAuthException.class);
  }
  @Test void failedAuthenticationConsumesStateAndCannotBeReplayed() {
    String state=start(); when(client.authenticate("code")).thenThrow(new OaOAuthException("1010","expired"));
    assertThatThrownBy(() -> service.callback("code",state)).hasMessage("expired");
    assertThatThrownBy(() -> service.callback("code",state)).hasMessageContaining("已过期");
    verify(client,times(1)).authenticate("code");
  }
}
