package com.sanhua.marketingcost.worker;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Independent monthly queue limits; ordinary quotation worker settings remain separate. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "monthly.reprice")
public class MonthlyRepriceWorkerSettings {
  private boolean workerEnabled = true;
  private int concurrency = 1;
  private int claimBatchSize = 8;
  private long idleBackoffInitialMs = 50;
  private long pollIntervalMs = 1000;
  private long checkIntervalMs = 1000;
  private int shutdownTimeoutSeconds = 120;

  MonthlyRepriceQueue.Settings queueSettings() {
    return new MonthlyRepriceQueue.Settings(concurrency, claimBatchSize, idleBackoffInitialMs,
        pollIntervalMs, checkIntervalMs, shutdownTimeoutSeconds);
  }
}
