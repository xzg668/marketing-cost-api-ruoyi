package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.DrawingVerificationTask;
import com.sanhua.marketingcost.mapper.DrawingVerificationTaskMapper;
import com.sanhua.marketingcost.service.electronicdrawing.DrawingResultConsumptionService;
import java.util.List;
import org.junit.jupiter.api.Test;

class DrawingResultWorkerTest {
  final DrawingVerificationTaskMapper tasks = mock(DrawingVerificationTaskMapper.class);
  final DrawingResultConsumptionService consumer = mock(DrawingResultConsumptionService.class);
  final CostRunWorkerProperties properties = new CostRunWorkerProperties();
  final DrawingResultWorker worker = new DrawingResultWorker(tasks, consumer, properties);

  @Test
  void disabledWorkerDoesNotReadOrConsume() {
    properties.setEnabled(false);
    assertThat(worker.scanOnce()).isZero();
    verifyNoInteractions(tasks, consumer);
  }

  @Test
  void resumesPersistedPendingTasksAndOneFailureDoesNotStopOtherResults() {
    properties.setEnabled(true);
    var first = new DrawingVerificationTask();
    first.setId(1L);
    first.setCurrentResultId(101L);
    var second = new DrawingVerificationTask();
    second.setId(2L);
    second.setCurrentResultId(102L);
    when(tasks.findPendingConsumption(20)).thenReturn(List.of(first, second));
    when(consumer.consume(1L)).thenThrow(new IllegalStateException("database unavailable"));
    assertThat(worker.scanOnce()).isEqualTo(2);
    verify(consumer).consume(1L);
    verify(consumer).consume(2L);
  }
}
