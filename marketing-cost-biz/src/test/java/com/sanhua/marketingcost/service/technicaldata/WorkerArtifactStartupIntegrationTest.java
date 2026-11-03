package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** 用一次性 MySQL 验证实际发布 worker JAR 的依赖装配，禁止领取核算或外部任务。 */
@Tag("integration")
@EnabledIfSystemProperty(named = "worker.startup.jar", matches = ".+")
class WorkerArtifactStartupIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Test void releaseWorkerStartsWithItsActualRuntimeDependencies() throws Exception {
    Path log = Path.of(System.getProperty("worker.startup.log"));
    var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
        "-jar", System.getProperty("worker.startup.jar"),
        "--cost.run.worker.enabled=false", "--monthly.reprice.enabled=false",
        "--quote.drawing-verification.enabled=false", "--integration.oa-person-directory.enabled=false",
        "--integration.oa-person-directory.run-at-startup=false", "--cms.sync-publish.enabled=false",
        "--srm.fixed-price.enabled=false", "--monthly.reprice.go-live-months.COMMERCIAL=2026-11");
    builder.environment().put("SPRING_DATASOURCE_URL", MYSQL.getJdbcUrl());
    builder.environment().put("SPRING_DATASOURCE_USERNAME", MYSQL.getUsername());
    builder.environment().put("SPRING_DATASOURCE_PASSWORD", MYSQL.getPassword());
    builder.environment().put("LOG_PATH", log.getParent().resolve("worker-isolated-logs").toString());
    builder.redirectErrorStream(true).redirectOutput(log.toFile());
    Process process = builder.start();
    try {
      long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
      boolean started = false;
      while (process.isAlive() && System.nanoTime() < deadline) {
        if (Files.readString(log).contains("Started CostRunWorkerApplication")) {
          started = true;
          break;
        }
        Thread.sleep(200);
      }
      assertThat(started).as("Actual release worker started; inspect %s", log).isTrue();
      assertThat(process.isAlive()).isTrue();
    } finally {
      process.destroy();
      if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
    }
  }
}
