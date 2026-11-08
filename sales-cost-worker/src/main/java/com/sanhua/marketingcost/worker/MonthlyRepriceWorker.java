package com.sanhua.marketingcost.worker;

import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceCostingService;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceDispatchService;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceInstance;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The 1130 worker only claims contract-version-one tasks; old MONTHLY_REPRICE rows stay retired. */
@Component
public class MonthlyRepriceWorker {
  private static final Logger log=LoggerFactory.getLogger(MonthlyRepriceWorker.class);
  private final MonthlyRepriceDispatchService dispatch;
  private final MonthlyRepriceCostingService costing;
  private final CostRunWorkerProperties properties;
  private final boolean enabled;
  private final String workerId;
  private final MonthlyRepriceQueue queue;
  private final MonthlyRepriceWorkerResources resources;
  private final int concurrency;
  private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->{
    var t=new Thread(r,"monthly-reprice-heartbeat");t.setDaemon(true);return t;
  });
  public MonthlyRepriceWorker(MonthlyRepriceDispatchService dispatch,MonthlyRepriceCostingService costing,
      CostRunWorkerProperties properties,MonthlyRepriceInstance instance,
      MonthlyRepriceWorkerSettings settings,MonthlyRepriceWorkerResources resources) {
    this.dispatch=dispatch;this.costing=costing;this.properties=properties;this.enabled=settings.isWorkerEnabled();
    workerId=instance.value();
    this.resources=resources;concurrency=settings.getConcurrency();
    queue = new MonthlyRepriceQueue(settings.queueSettings(), limit -> dispatch.claimBatch(workerId, limit),
        this::execute, dispatch::checkCompleted);
    log.info("调价worker实例 {} enabled={} concurrency={} claimBatchLimit={}", workerId,
        enabled && properties.isEnabled(), settings.getConcurrency(), settings.getClaimBatchSize());
  }
  @EventListener(ApplicationReadyEvent.class)
  public void start() {
    if (enabled && properties.isEnabled()) {
      resources.verify(workerId,concurrency,properties);
      queue.start();
    }
  }

  private void execute(com.sanhua.marketingcost.entity.CostRunTask task) {
    String worker = workerId;
    var previous = SecurityContextHolder.getContext();
    var context = SecurityContextHolder.createEmptyContext();
    var auth = new UsernamePasswordAuthenticationToken("monthly-reprice-worker", null, List.of());
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, task.getBusinessUnitType()));
    context.setAuthentication(auth); SecurityContextHolder.setContext(context);
    var renewal = heartbeat.scheduleAtFixedRate(() -> {
      try { dispatch.heartbeat(task, worker); }
      catch (Exception ex) { log.warn("调价租约续期失败 task={}", task.getId()); }
    }, 30, 30, TimeUnit.SECONDS);
    try { costing.execute(MonthlyRepriceCostingService.Lease.from(task)); }
    catch (Exception failure) {
      dispatch.fail(task, worker, failure);
      log.warn("调价任务失败 task={}: {}", task.getId(), failure.getMessage());
    } finally { renewal.cancel(false); SecurityContextHolder.setContext(previous); }
  }

  @PreDestroy public void close() {
    // Keep renewing in-flight work while shutdown drains it; no new leases after the coordinator stops.
    queue.close(); heartbeat.shutdownNow();
  }
}
