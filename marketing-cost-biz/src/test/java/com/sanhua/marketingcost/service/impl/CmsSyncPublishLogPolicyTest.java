package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceGenerateResponse;
import com.sanhua.marketingcost.entity.CmsSyncPublishSignal;
import com.sanhua.marketingcost.mapper.CmsSyncPublishSignalMapper;
import com.sanhua.marketingcost.service.CmsAuxSubjectSourceEffectiveService;
import com.sanhua.marketingcost.service.CmsSalaryCostSourceEffectiveService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class CmsSyncPublishLogPolicyTest {
  @Test
  void publishesWithChangeAwareSynchronizationAndPersistsAllCountsInBatchSummary() {
    var signals = mock(CmsSyncPublishSignalMapper.class);
    var jdbc = mock(JdbcTemplate.class);
    var transaction = mock(TransactionTemplate.class);
    var salary = mock(CmsSalaryCostSourceEffectiveService.class);
    var auxiliary = mock(CmsAuxSubjectSourceEffectiveService.class);
    var signal = new CmsSyncPublishSignal();
    signal.setId(1L);
    signal.setCostYear(2026);
    signal.setBusinessUnitType("COMMERCIAL");
    signal.setBatchNo("sync-test");
    when(signals.selectOne(any())).thenReturn(signal);
    when(signals.update(isNull(), any(Wrapper.class))).thenReturn(1);
    when(jdbc.queryForObject(anyString(), eq(Long.class), anyString(), anyString())).thenReturn(1L);
    when(transaction.execute(any())).thenAnswer(invocation -> {
      TransactionCallback<?> callback = invocation.getArgument(0);
      return callback.doInTransaction(mock(TransactionStatus.class));
    });
    var salaryResult = new CmsEffectiveSourceGenerateResponse();
    salaryResult.setInsertedCount(2);
    salaryResult.setUpdatedCount(3);
    salaryResult.setSkippedCount(100);
    salaryResult.setRemovedCount(1);
    salaryResult.setBlockedCount(4);
    var auxResult = new CmsEffectiveSourceGenerateResponse();
    auxResult.setSkippedCount(1000);
    auxResult.setRemovedCount(2);
    when(salary.synchronizeDefaultSources(2026, "SYSTEM_CMS_SYNC", "COMMERCIAL")).thenReturn(salaryResult);
    when(auxiliary.synchronizeDefaultSources(2026, "SYSTEM_CMS_SYNC", "COMMERCIAL")).thenReturn(auxResult);
    var service = new CmsSyncPublishServiceImpl(signals, jdbc, transaction, salary, auxiliary, 360);

    var result = service.runNextReady();

    assertThat(result.getStatus()).isEqualTo("SUCCESS");
    assertThat(result.getMessage()).contains("inserted=2", "updated=3", "removed=3", "skipped=1100", "blocked=4");
    verify(salary).synchronizeDefaultSources(2026, "SYSTEM_CMS_SYNC", "COMMERCIAL");
    verify(auxiliary).synchronizeDefaultSources(2026, "SYSTEM_CMS_SYNC", "COMMERCIAL");
    verify(salary, never()).generateDefaultSources(2026, "SYSTEM_CMS_SYNC", "COMMERCIAL");
    verify(auxiliary, never()).generateDefaultSources(2026, "SYSTEM_CMS_SYNC", "COMMERCIAL");
    assertThat(mockingDetails(jdbc).getInvocations()).noneSatisfy(invocation -> {
      assertThat(invocation.getMethod().getName()).isEqualTo("update");
      assertThat(invocation.getArgument(0, String.class)).contains("DELETE FROM cms_cost_source_effective");
    });
  }
}
