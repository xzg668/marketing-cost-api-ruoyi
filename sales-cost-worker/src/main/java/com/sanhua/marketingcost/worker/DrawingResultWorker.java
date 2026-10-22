package com.sanhua.marketingcost.worker;

import com.sanhua.marketingcost.mapper.DrawingVerificationTaskMapper;
import com.sanhua.marketingcost.service.electronicdrawing.DrawingResultConsumptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 回传已提交后独立消费；进程重启仍能从 PENDING 结果继续。 */
@Component
public class DrawingResultWorker {
  private static final Logger log = LoggerFactory.getLogger(DrawingResultWorker.class);
  private final DrawingVerificationTaskMapper tasks;
  private final DrawingResultConsumptionService consumer;
  private final CostRunWorkerProperties properties;

  public DrawingResultWorker(
      DrawingVerificationTaskMapper tasks,
      DrawingResultConsumptionService consumer,
      CostRunWorkerProperties properties) {
    this.tasks = tasks;
    this.consumer = consumer;
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${quote.drawing-verification.consume-poll-interval-ms:5000}")
  public void scheduledScan() {
    scanOnce();
  }

  public int scanOnce() {
    if (!properties.isEnabled()) return 0;
    var pending = tasks.findPendingConsumption(20);
    for (var task : pending) {
      try {
        consumer.consume(task.getId());
      } catch (RuntimeException failure) {
        log.error(
            "drawing result worker failed: localTaskId={} resultId={} exceptionType={}",
            task.getId(),
            task.getCurrentResultId(),
            failure.getClass().getSimpleName());
      }
    }
    return pending.size();
  }
}
