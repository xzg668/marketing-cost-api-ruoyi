package com.sanhua.marketingcost.worker;

import com.sanhua.marketingcost.enums.CostRunTaskScene;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "cost.run.worker")
public class CostRunWorkerProperties {

  private boolean enabled = true;
  private String id;
  private int threads = 4;
  private int claimBatchSize = 20;
  private int lockTimeoutMinutes = 10;
  private int maxRetryCount = 3;
  private int writeBatchSize = 100;
  private long pollIntervalMs = 5000L;
  private Set<CostRunTaskScene> scenes =
      EnumSet.of(CostRunTaskScene.QUOTE, CostRunTaskScene.MONTHLY_REPRICE);
  private String generatedId;

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public int getThreads() {
    return positiveOrDefault(threads, 4);
  }

  public void setThreads(int threads) {
    this.threads = threads;
  }

  public int getClaimBatchSize() {
    return positiveOrDefault(claimBatchSize, 20);
  }

  public void setClaimBatchSize(int claimBatchSize) {
    this.claimBatchSize = claimBatchSize;
  }

  public int getLockTimeoutMinutes() {
    return positiveOrDefault(lockTimeoutMinutes, 10);
  }

  public void setLockTimeoutMinutes(int lockTimeoutMinutes) {
    this.lockTimeoutMinutes = lockTimeoutMinutes;
  }

  public int getMaxRetryCount() {
    return Math.max(maxRetryCount, 0);
  }

  public void setMaxRetryCount(int maxRetryCount) {
    this.maxRetryCount = maxRetryCount;
  }

  public int getWriteBatchSize() {
    return positiveOrDefault(writeBatchSize, 100);
  }

  public void setWriteBatchSize(int writeBatchSize) {
    this.writeBatchSize = writeBatchSize;
  }

  public long getPollIntervalMs() {
    return pollIntervalMs > 0 ? pollIntervalMs : 5000L;
  }

  public void setPollIntervalMs(long pollIntervalMs) {
    this.pollIntervalMs = pollIntervalMs;
  }

  public Set<CostRunTaskScene> getScenes() {
    if (scenes == null || scenes.isEmpty()) {
      return defaultScenes();
    }
    return EnumSet.copyOf(scenes);
  }

  public void setScenes(Set<CostRunTaskScene> scenes) {
    this.scenes = scenes == null || scenes.isEmpty()
        ? defaultScenes()
        : EnumSet.copyOf(scenes);
  }

  public String resolvedWorkerId() {
    if (StringUtils.hasText(id)) {
      return id.trim();
    }
    if (!StringUtils.hasText(generatedId)) {
      generatedId = defaultWorkerId();
    }
    return generatedId;
  }

  private int positiveOrDefault(int value, int defaultValue) {
    return value > 0 ? value : defaultValue;
  }

  private EnumSet<CostRunTaskScene> defaultScenes() {
    return EnumSet.of(CostRunTaskScene.QUOTE, CostRunTaskScene.MONTHLY_REPRICE);
  }

  private String defaultWorkerId() {
    String host = "unknown-host";
    try {
      host = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException ignored) {
      // Worker id still includes pid/random suffix, so hostname lookup failure must not block startup.
    }
    String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
    String pid = runtimeName == null ? "unknown-pid" : runtimeName.split("@")[0];
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    return host + "-" + pid + "-" + suffix;
  }
}
