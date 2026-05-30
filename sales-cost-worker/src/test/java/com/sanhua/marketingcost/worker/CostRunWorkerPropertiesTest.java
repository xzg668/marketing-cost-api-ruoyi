package com.sanhua.marketingcost.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.enums.CostRunTaskScene;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

class CostRunWorkerPropertiesTest {

  @Test
  void costRunWorkerIsEnabledForQuoteAndMonthlyRepriceByDefault() {
    CostRunWorkerProperties properties = new CostRunWorkerProperties();

    assertThat(properties.isEnabled()).isTrue();
    assertThat(properties.getScenes())
        .containsExactlyInAnyOrder(CostRunTaskScene.QUOTE, CostRunTaskScene.MONTHLY_REPRICE);
  }

  @Test
  void costRunWorkerPrefixBindsConfiguration() {
    MockEnvironment environment = new MockEnvironment()
        .withProperty("cost.run.worker.enabled", "true")
        .withProperty("cost.run.worker.id", " cost-run-gray-1 ")
        .withProperty("cost.run.worker.threads", "8")
        .withProperty("cost.run.worker.claim-batch-size", "50")
        .withProperty("cost.run.worker.lock-timeout-minutes", "12")
        .withProperty("cost.run.worker.max-retry-count", "5")
        .withProperty("cost.run.worker.write-batch-size", "200")
        .withProperty("cost.run.worker.poll-interval-ms", "2000")
        .withProperty("cost.run.worker.scenes", "QUOTE,MONTHLY_REPRICE");
    CostRunWorkerProperties properties = new CostRunWorkerProperties();

    Binder.get(environment)
        .bind("cost.run.worker", Bindable.ofInstance(properties));

    assertThat(properties.isEnabled()).isTrue();
    assertThat(properties.resolvedWorkerId()).isEqualTo("cost-run-gray-1");
    assertThat(properties.getThreads()).isEqualTo(8);
    assertThat(properties.getClaimBatchSize()).isEqualTo(50);
    assertThat(properties.getLockTimeoutMinutes()).isEqualTo(12);
    assertThat(properties.getMaxRetryCount()).isEqualTo(5);
    assertThat(properties.getWriteBatchSize()).isEqualTo(200);
    assertThat(properties.getPollIntervalMs()).isEqualTo(2000L);
    assertThat(properties.getScenes())
        .containsExactlyInAnyOrder(CostRunTaskScene.QUOTE, CostRunTaskScene.MONTHLY_REPRICE);
  }

  @Test
  void invalidNumericValuesFallBackToSafeDefaults() {
    CostRunWorkerProperties properties = new CostRunWorkerProperties();
    properties.setThreads(0);
    properties.setClaimBatchSize(-1);
    properties.setLockTimeoutMinutes(0);
    properties.setMaxRetryCount(-1);
    properties.setWriteBatchSize(0);
    properties.setPollIntervalMs(0L);
    properties.setScenes(EnumSet.noneOf(CostRunTaskScene.class));

    assertThat(properties.getThreads()).isEqualTo(4);
    assertThat(properties.getClaimBatchSize()).isEqualTo(20);
    assertThat(properties.getLockTimeoutMinutes()).isEqualTo(10);
    assertThat(properties.getMaxRetryCount()).isZero();
    assertThat(properties.getWriteBatchSize()).isEqualTo(100);
    assertThat(properties.getPollIntervalMs()).isEqualTo(5000L);
    assertThat(properties.getScenes())
        .containsExactlyInAnyOrder(CostRunTaskScene.QUOTE, CostRunTaskScene.MONTHLY_REPRICE);
  }

  @Test
  void defaultWorkerIdIsGeneratedWhenIdMissing() {
    CostRunWorkerProperties properties = new CostRunWorkerProperties();

    assertThat(properties.resolvedWorkerId()).isNotBlank();
    assertThat(properties.resolvedWorkerId()).isEqualTo(properties.resolvedWorkerId());
  }
}
