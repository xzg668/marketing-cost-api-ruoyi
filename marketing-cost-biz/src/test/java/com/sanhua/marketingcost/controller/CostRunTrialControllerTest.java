package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.CostRunTrialRequest;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.BusinessUnitRepriceLockGuard;
import com.sanhua.marketingcost.service.CostRunProgressStore;
import com.sanhua.marketingcost.service.CostRunTrialService;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

class CostRunTrialControllerTest {

  private CostRunTrialService costRunTrialService;
  private CostRunProgressStore progressStore;
  private BusinessUnitRepriceLockGuard repriceLockGuard;
  private CostRunTrialController controller;

  @BeforeEach
  void setUp() {
    costRunTrialService = Mockito.mock(CostRunTrialService.class);
    progressStore = new CostRunProgressStore();
    repriceLockGuard = Mockito.mock(BusinessUnitRepriceLockGuard.class);
    controller = new CostRunTrialController(costRunTrialService, progressStore, repriceLockGuard);
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("trial：先做月度调价锁校验，再入队和提交异步核算")
  void runChecksMonthlyRepriceLockBeforeEnqueue() {
    Authentication authentication = authentication("alice", "COMMERCIAL");
    when(costRunTrialService.run("OA-001", "alice", "COMMERCIAL"))
        .thenReturn(CompletableFuture.completedFuture(null));
    CostRunTrialRequest request = new CostRunTrialRequest();
    request.setOaNo(" OA-001 ");

    controller.run(request, authentication);

    verify(repriceLockGuard).assertCostRunAllowed("OA-001");
    verify(costRunTrialService).run("OA-001", "alice", "COMMERCIAL");
  }

  @Test
  @DisplayName("trial：锁定业务单元直接调接口也失败，且不会留下 QUEUED 进度")
  void runRejectsWhenMonthlyRepriceLockActive() {
    Authentication authentication = authentication("alice", "COMMERCIAL");
    Mockito.doThrow(new IllegalStateException("当前业务单元正在月度调价"))
        .when(repriceLockGuard)
        .assertCostRunAllowed("OA-LOCKED");
    CostRunTrialRequest request = new CostRunTrialRequest();
    request.setOaNo("OA-LOCKED");

    assertThatThrownBy(() -> controller.run(request, authentication))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("月度调价");

    verify(costRunTrialService, never()).run("OA-LOCKED", "alice", "COMMERCIAL");
    assertThat(progressStore.get("OA-LOCKED").getStatus()).isEqualTo("IDLE");
    assertThat(progressStore.getQueueDepth()).isZero();
  }

  @Test
  @DisplayName("T31：重复点击同一个 OA 时只提交一次，第二次不创建新的运行任务")
  void duplicateRunDoesNotSubmitAnotherTask() {
    Authentication authentication = authentication("alice", "COMMERCIAL");
    when(costRunTrialService.run("OA-DUP", "alice", "COMMERCIAL"))
        .thenReturn(new CompletableFuture<>());
    CostRunTrialRequest request = new CostRunTrialRequest();
    request.setOaNo("OA-DUP");

    controller.run(request, authentication);
    var second = controller.run(request, authentication);

    assertThat(second.getData()).contains("已在试算中");
    verify(costRunTrialService, Mockito.times(1)).run("OA-DUP", "alice", "COMMERCIAL");
    assertThat(progressStore.get("OA-DUP").getStatus()).isEqualTo("QUEUED");
  }

  private Authentication authentication(String username, String businessUnitType) {
    User principal = new User(username, "N/A", java.util.List.of());
    UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    authentication.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, businessUnitType));
    SecurityContextHolder.getContext().setAuthentication(authentication);
    return authentication;
  }
}
