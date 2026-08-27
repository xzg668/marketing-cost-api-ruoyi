package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.BusinessChangeLog;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CollaborationTaskLogServiceTest {

  @Test
  void recordsOnlyInternalBusinessHistory() {
    BusinessChangeLogMapper mapper = mock(BusinessChangeLogMapper.class);
    when(mapper.insert(any(BusinessChangeLog.class))).thenReturn(1);
    CollaborationTaskLogService service = new CollaborationTaskLogService(mapper);
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setId(12L);
    task.setTaskStatus("BOM_IN_PROGRESS");
    task.setUpdatedBy(601L);
    task.setUpdatedByName("王工");

    service.record(task, "TECH_TASK_UPDATED", "开始补录 BOM");

    ArgumentCaptor<BusinessChangeLog> captor = ArgumentCaptor.forClass(BusinessChangeLog.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue()).satisfies(log -> {
      assertThat(log.getBizDomain()).isEqualTo("QUOTE_COLLABORATION");
      assertThat(log.getBizType()).isEqualTo("PRODUCT_TASK_EVENT");
      assertThat(log.getBizId()).isEqualTo(12L);
      assertThat(log.getFieldName()).isEqualTo("TECH_TASK_UPDATED");
      assertThat(log.getAfterValue()).isEqualTo("BOM_IN_PROGRESS");
      assertThat(log.getChangeReason()).isEqualTo("开始补录 BOM");
      assertThat(log.getChangeSource()).isEqualTo("SYSTEM");
    });
  }
}
