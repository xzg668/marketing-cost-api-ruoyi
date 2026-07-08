package com.sanhua.marketingcost.scheduler;

import com.sanhua.marketingcost.dto.CmsSyncPublishRunResponse;
import com.sanhua.marketingcost.service.CmsSyncPublishService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class CmsSyncPublishScheduler {

  private final CmsSyncPublishService cmsSyncPublishService;
  private final boolean enabled;

  public CmsSyncPublishScheduler(
      CmsSyncPublishService cmsSyncPublishService,
      @Value("${cms.sync-publish.enabled:true}") boolean enabled) {
    this.cmsSyncPublishService = cmsSyncPublishService;
    this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${cms.sync-publish.poll-interval-ms:60000}")
  public void pollReadySignal() {
    if (!enabled) {
      return;
    }
    try {
      CmsSyncPublishRunResponse response = cmsSyncPublishService.runNextReady();
      if (response.isExecuted()) {
        log.info(
            "CMS sync publish finished, signalId={}, batchNo={}, status={}, message={}",
            response.getSignalId(),
            response.getBatchNo(),
            response.getStatus(),
            response.getMessage());
      }
    } catch (RuntimeException e) {
      log.warn("CMS sync publish poll failed", e);
    }
  }
}
