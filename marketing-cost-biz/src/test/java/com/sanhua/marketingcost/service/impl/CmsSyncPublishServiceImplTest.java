package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.CmsSyncPublishSignal;
import com.sanhua.marketingcost.mapper.CmsSyncPublishSignalMapper;
import com.sanhua.marketingcost.service.CmsAuxSubjectSourceEffectiveService;
import com.sanhua.marketingcost.service.CmsSalaryCostSourceEffectiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

class CmsSyncPublishServiceImplTest {
  private CmsSyncPublishSignalMapper signals;
  private JdbcTemplate jdbc;
  private TransactionTemplate transaction;
  private CmsSalaryCostSourceEffectiveService salary;
  private CmsAuxSubjectSourceEffectiveService auxiliary;
  private CmsSyncPublishServiceImpl service;

  @BeforeEach
  void setUp() {
    signals = mock(CmsSyncPublishSignalMapper.class);
    jdbc = mock(JdbcTemplate.class);
    transaction = mock(TransactionTemplate.class);
    salary = mock(CmsSalaryCostSourceEffectiveService.class);
    auxiliary = mock(CmsAuxSubjectSourceEffectiveService.class);
    service = new CmsSyncPublishServiceImpl(signals,jdbc,transaction,salary,auxiliary,360);
  }

  @Test
  void noReadySignalDoesNotInspectOrRefreshTablesDuringEtl() {
    var result = service.runNextReady();
    assertThat(result.isExecuted()).isFalse();
    assertThat(result.getStatus()).isEqualTo("SKIPPED");
    verifyNoInteractions(jdbc, transaction, salary, auxiliary);
  }

  @Test
  void runningPublicationPreventsAnotherRefresh() {
    when(signals.selectCount(any())).thenReturn(1L);
    assertThat(service.runNextReady().isExecuted()).isFalse();
    verifyNoInteractions(jdbc, transaction, salary, auxiliary);
  }

  @Test
  void lostSignalClaimDoesNotStartRefresh() {
    var signal = new CmsSyncPublishSignal();
    signal.setId(3L);
    signal.setCostYear(2026);
    signal.setBusinessUnitType("COMMERCIAL");
    when(signals.selectOne(any())).thenReturn(signal);
    assertThat(service.runNextReady().isExecuted()).isFalse();
    verifyNoInteractions(jdbc, transaction, salary, auxiliary);
  }
}
