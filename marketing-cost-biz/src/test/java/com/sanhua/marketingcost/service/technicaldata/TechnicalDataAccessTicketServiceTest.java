package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAccessTicketExchangeRequest;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.SysUser;
import com.sanhua.marketingcost.integration.oa.*;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaGateway;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.security.JwtUtils;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class TechnicalDataAccessTicketServiceTest {
  private final TechnicalDataOaGateway gateway = mock(TechnicalDataOaGateway.class);
  private final TechnicalDataOaUserDirectory users = mock(TechnicalDataOaUserDirectory.class);
  private final QuoteTechTaskMapper tasks = mock(QuoteTechTaskMapper.class);
  private final QuoteTechModuleMapper modules = mock(QuoteTechModuleMapper.class);
  private final JwtUtils jwt = new JwtUtils(Base64.getEncoder().encodeToString("unit-personal-session-secret-at-least-32".getBytes()), 86_400_000L);
  private final OaPeer peer = new OaPeer("OA", "UNIT", Set.of("COMMERCIAL"));
  private TechnicalDataAccessTicketServiceImpl service;
  private QuoteTechTask task;

  @BeforeEach void setup() {
    var tx = mock(PlatformTransactionManager.class);
    when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    service = new TechnicalDataAccessTicketServiceImpl(gateway, users, tasks, jwt, mock(TechnicalDataAuditLogService.class),
        new OaMessageCodec(new ObjectMapper().findAndRegisterModules()), tx, modules);
    task = new QuoteTechTask(); task.setId(101L); task.setAssigneeUserId(999L); task.setTaskStatus("IN_PROGRESS");
    task.setActiveFlag(1); task.setExternalSystem("OA"); task.setOaEnvironment("UNIT"); task.setBusinessUnitType("COMMERCIAL");
    var module = new QuoteTechModule(); module.setRequiredFlag(1); module.setAssigneeUserId(201L); module.setModuleType("PRICE");
    when(tasks.selectByIdForUpdate(101L)).thenReturn(task);
    when(modules.selectByTaskId(101L)).thenReturn(List.of(module));
    when(gateway.peer()).thenReturn(peer);
    when(gateway.exchange(101L, "one-use-code")).thenReturn(new TechnicalDataOaGateway.Identity("oa-li", 101L));
    when(users.actor(peer, "oa-li")).thenReturn(new TechnicalDataActor(201L, "李工", Set.of("technical:data:task:edit")));
    var user = new SysUser(); user.setUserId(201L); user.setUserName("li");
    when(users.activeUser(201L)).thenReturn(user);
  }

  @Test void actualOaUserCanOpenAssignedModuleAlthoughNotProductDefault() {
    var result = service.exchange(new TechnicalDataAccessTicketExchangeRequest(101L, "one-use-code"));
    assertThat(result.userId()).isEqualTo(201L);
    assertThat(result.entryPath()).isEqualTo("/technical-data-access/tasks/101");
    assertThat(jwt.isTechnicalDataSession(result.accessToken())).isTrue();
    assertThat(jwt.extractTechnicalDataTaskId(result.accessToken())).isEqualTo(101L);
  }

  @Test void invalidOrConsumedIdentityMustBeRejectedByOaBeforeCreatingSession() {
    when(gateway.exchange(101L, "one-use-code")).thenThrow(OaIntegrationException.invalid("CODE_USED", "身份码已使用"));
    assertThatThrownBy(() -> service.exchange(new TechnicalDataAccessTicketExchangeRequest(101L, "one-use-code")))
        .hasMessageContaining("身份码已使用");
    verifyNoInteractions(users);
  }

  @Test void defaultUserWithoutActualModulesCannotUseTheSharedUrl() {
    when(users.actor(peer, "oa-li")).thenReturn(new TechnicalDataActor(999L, "原默认人", Set.of("technical:data:task:edit")));
    assertThatThrownBy(() -> service.exchange(new TechnicalDataAccessTicketExchangeRequest(101L, "one-use-code")))
        .isInstanceOf(TechnicalDataTaskException.class).hasMessageContaining("无权");
  }

  @Test void canceledOrDifferentEnvironmentTaskDoesNotCreateSession() {
    task.setOaEnvironment("OTHER");
    assertThatThrownBy(() -> service.exchange(new TechnicalDataAccessTicketExchangeRequest(101L, "one-use-code")))
        .isInstanceOf(TechnicalDataTaskException.class);
    task.setOaEnvironment("UNIT"); task.setActiveFlag(0);
    assertThatThrownBy(() -> service.exchange(new TechnicalDataAccessTicketExchangeRequest(101L, "one-use-code")))
        .isInstanceOf(TechnicalDataTaskException.class);
  }
}
