package com.sanhua.marketingcost.integration.oa.directory;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OaPersonDirectorySyncScheduler {
  private static final Logger log = LoggerFactory.getLogger(OaPersonDirectorySyncScheduler.class);

  private final OaPersonDirectoryProperties properties;
  private final OaPersonDirectoryService service;
  private final AtomicBoolean running = new AtomicBoolean();

  public OaPersonDirectorySyncScheduler(
      OaPersonDirectoryProperties properties, OaPersonDirectoryService service) {
    this.properties = properties;
    this.service = service;
  }

  @Scheduled(
      cron = "${integration.oa-person-directory.cron:0 15 2 * * *}",
      zone = "${integration.oa-person-directory.zone:Asia/Shanghai}")
  public void scheduledSync() {
    synchronizeSafely("daily");
  }

  @EventListener(ApplicationReadyEvent.class)
  public void synchronizeOnStartup() {
    if (!properties.isEnabled() || !properties.isRunAtStartup()) {
      return;
    }
    Thread.ofVirtual().name("oa-person-directory-startup-sync").start(
        () -> synchronizeSafely("startup"));
  }

  private void synchronizeSafely(String trigger) {
    if (!properties.isEnabled() || !running.compareAndSet(false, true)) {
      return;
    }
    try {
      OaPersonDirectoryRepository.SyncResult result = service.synchronize();
      log.info(
          "OA person directory synchronized: trigger={}, batchId={}, total={}, selectable={}, missingDepartments={}",
          trigger, result.batchId(), result.totalPeople(), result.selectablePeople(),
          result.missingDepartments());
    } catch (RuntimeException exception) {
      // 不打印请求、令牌或凭证；旧批次由仓储事务保证继续有效。
      log.error("OA person directory synchronization failed: trigger={}, reason={}",
          trigger, exception.getMessage());
    } finally {
      running.set(false);
    }
  }
}
