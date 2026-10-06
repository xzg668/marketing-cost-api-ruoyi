package com.sanhua.marketingcost.integration.oa.oauth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.directory.*;
import com.sanhua.marketingcost.security.*;
import com.sanhua.marketingcost.service.SysUserService;
import com.sanhua.marketingcost.service.technicaldata.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;

class OaOAuthSecurityTest {
  JwtUtils jwt = new JwtUtils(Base64.getEncoder().encodeToString(new byte[32]),7200000);
  OaPersonDirectoryRepository directory = mock(OaPersonDirectoryRepository.class);
  SysUserService users = mock(SysUserService.class);
  UserDetailsService details = mock(UserDetailsService.class);
  JwtAuthenticationFilter filter;
  @BeforeEach void setup() {
    SecurityContextHolder.clearContext();
    var properties = new OaPersonDirectoryProperties(); properties.setEnvironment("TEST");
    filter = new JwtAuthenticationFilter(jwt,details,users,properties,directory);
  }
  @AfterEach void close() { SecurityContextHolder.clearContext(); }
  void authenticate(String path, String environment) throws Exception {
    var request = new MockHttpServletRequest("GET",path);
    request.addHeader("Authorization","Bearer "+jwt.generateOaSessionToken("00123","本人","COMMERCIAL",10,environment,600));
    filter.doFilter(request,new MockHttpServletResponse(),(req,res)->{});
  }
  @Test void mapsEmployeeNumberWithoutInheritingAdminPermissions() throws Exception {
    when(directory.findActiveByEmployeeNo("00123")).thenReturn(new OaPersonDirectoryIdentity(1L,"00123"));
    authenticate("/api/v2/technical-data/forms/10/workbench","TEST");
    var actor=new SecurityTechnicalDataActorProvider(users).current();
    assertThat(actor.userId()).isEqualTo(1);
    assertThat(actor.oaSession()).isTrue();
    assertThat(actor.admin()).isFalse();
    assertThat(actor.canPublish()).isFalse();
    assertThat(actor.canAccessForm(11L)).isFalse();
    verifyNoInteractions(users,details);
  }
  @Test void viewerDoesNotNeedSystemAccount() throws Exception {
    authenticate("/api/v1/auth/oa/me","TEST");
    var actor=new SecurityTechnicalDataActorProvider(users).current();
    assertThat(actor.userId()).isNull();
    assertThat(actor.canReadTask(task(10))).isTrue();
    assertThat(actor.canReadTask(task(11))).isFalse();
    assertThat(actor.canEdit()).isFalse();
    assertThat(actor.canEditModule(task(10),module(null))).isFalse();
  }
  @Test void collaborationTokenCannotCallNormalAccountOrCostingApis() throws Exception {
    authenticate("/api/v1/auth/me","TEST");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    authenticate("/api/v1/quote-requests","TEST");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    authenticate("/api/v2/technical-data/forms/10/workbench","PROD");
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
  @Test void onlyOwnActiveModuleIsEditableAndReturnRestoresEditing() {
    var actor=new TechnicalDataActor(1L,"技术员",Set.of("technical:data:task:list","technical:data:task:edit"),10L);
    var module=module(1L); var task=task(10);
    assertThat(actor.canEditModule(task,module)).isTrue();
    assertThat(actor.canEditModule(task,module(2L))).isFalse();
    assertThat(actor.canEditModule(task(11),module)).isFalse();
    module.setModuleStatus("SUBMITTED"); assertThat(actor.canEditModule(task,module)).isFalse();
    module.setModuleStatus("RETURNED"); assertThat(actor.canEditModule(task,module)).isTrue();
    module.setOaEditAllowed(0); assertThat(actor.canEditModule(task,module)).isFalse();
  }
  QuoteTechTask task(long formId) {
    var task=new QuoteTechTask();task.setId(5L);task.setOaFormId(formId);task.setActiveFlag(1);
    task.setTaskStatus("PENDING");task.setOaAssignmentVersion(1);task.setExternalTaskStatus("PUBLISHED");return task;
  }
  QuoteTechModule module(Long owner) {
    var module=new QuoteTechModule();module.setRequiredFlag(1);module.setAssigneeUserId(owner);
    module.setModuleStatus("PENDING");module.setOaEditAllowed(1);return module;
  }
}
