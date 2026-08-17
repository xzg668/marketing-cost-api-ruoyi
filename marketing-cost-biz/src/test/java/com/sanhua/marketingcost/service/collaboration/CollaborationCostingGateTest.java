package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(MockitoExtension.class)
class CollaborationCostingGateTest {
  @Mock JdbcTemplate jdbc;
  @Mock QuoteCollaborationTaskRepository repository;
  @Mock CollaborationProductStateService productStateService;
  @Mock CollaborationMasterStateService masterStateService;
  @Mock CollaborationCurrentPrincipalProvider principalProvider;
  private CollaborationCostingGate gate;

  @BeforeEach
  void setUp() {
    gate = new CollaborationCostingGate(jdbc, repository, productStateService,
        masterStateService, principalProvider);
  }

  @Test
  void legacyItemWithoutCollaborationKeepsExistingCostingPath() {
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());

    assertThatCode(() -> gate.requireReadyAndStart(11L, "COMMERCIAL"))
        .doesNotThrowAnyException();

    verify(productStateService, never()).transition(anyLong(), anyInt(), any(), any(), any());
  }

  @Test
  void blocksServerSideWhenFinanceAndRepriceAreNotReady() throws Exception {
    stubRow("WAIT_SOURCE", "WAIT_FINANCE");

    assertThatThrownBy(() -> gate.requireReadyAndStart(11L, "COMMERCIAL"))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("尚未完成财务审核和重新取价");
  }

  @Test
  void readyLinkStartsExistingSixStepCostingExactlyOnce() throws Exception {
    stubRow("READY", "READY_FOR_COSTING");
    QuoteCollaborationProductTask task = task("READY_FOR_COSTING");
    when(repository.findProductTaskById(21L,
        new CollaborationScope("COMMERCIAL", "C01"))).thenReturn(Optional.of(task));
    CollaborationPrincipal operator = new CollaborationPrincipal(
        31L, "核算员", Set.of(CollaborationRole.COSTING_OPERATOR));
    when(principalProvider.current()).thenReturn(operator);

    gate.requireReadyAndStart(11L, "COMMERCIAL");

    verify(productStateService).transition(21L, 3,
        new CollaborationScope("COMMERCIAL", "C01"), ProductAction.START_COSTING, operator);
  }

  @SuppressWarnings("unchecked")
  private void stubRow(String linkStatus, String productStatus) throws Exception {
    ResultSet rs = mock(ResultSet.class);
    when(rs.getLong("product_task_id")).thenReturn(21L);
    when(rs.getString("link_status")).thenReturn(linkStatus);
    when(rs.getString("task_status")).thenReturn(productStatus);
    when(rs.getString("business_unit_type")).thenReturn("COMMERCIAL");
    when(rs.getString("applicable_org_code")).thenReturn("C01");
    when(jdbc.query(anyString(), any(RowMapper.class), any(), any()))
        .thenAnswer(invocation -> List.of(((RowMapper<Object>) invocation.getArgument(1))
            .mapRow(rs, 0)));
  }

  private static QuoteCollaborationProductTask task(String status) {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(21L);
    task.setOriginCollaborationId(1L);
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("C01");
    task.setTaskStatus(status);
    task.setTaskVersion(3);
    return task;
  }
}
