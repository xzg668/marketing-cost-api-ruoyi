package com.sanhua.marketingcost.integration.oa.directory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OaPersonDirectorySyncSchedulerTest {
  private final OaPersonDirectoryProperties properties = new OaPersonDirectoryProperties();
  private final OaPersonDirectoryService service = mock(OaPersonDirectoryService.class);
  private final OaPersonDirectorySyncScheduler scheduler =
      new OaPersonDirectorySyncScheduler(properties, service);

  @Test
  void startupSyncAndDailySyncDoNotOverlap() throws Exception {
    properties.setEnabled(true);
    properties.setRunAtStartup(true);
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    when(service.synchronize()).thenAnswer(invocation -> {
      entered.countDown();
      if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("同步测试等待超时");
      return new OaPersonDirectoryRepository.SyncResult("batch", 1, 1, List.of());
    });
    try {
      scheduler.synchronizeOnStartup();
      org.assertj.core.api.Assertions.assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      scheduler.scheduledSync();
      verify(service, times(1)).synchronize();
    } finally {
      release.countDown();
    }
  }

  @Test
  void failedSyncAllowsNextScheduledAttempt() {
    properties.setEnabled(true);
    when(service.synchronize()).thenThrow(new OaPersonDirectoryException("test unavailable"))
        .thenReturn(new OaPersonDirectoryRepository.SyncResult("batch", 1, 1, List.of()));
    scheduler.scheduledSync();
    scheduler.scheduledSync();
    verify(service, times(2)).synchronize();
  }

  @Test
  void explicitDisablePreventsBothTriggers() {
    properties.setEnabled(false);
    properties.setRunAtStartup(true);
    scheduler.synchronizeOnStartup();
    scheduler.scheduledSync();
    verify(service, never()).synchronize();
  }
}
