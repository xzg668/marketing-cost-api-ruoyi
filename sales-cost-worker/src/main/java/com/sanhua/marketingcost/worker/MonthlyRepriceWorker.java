package com.sanhua.marketingcost.worker;

import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceCostingService;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceDispatchService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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
  private final ScheduledExecutorService heartbeat=Executors.newSingleThreadScheduledExecutor(r->{
    var t=new Thread(r,"monthly-reprice-heartbeat");t.setDaemon(true);return t;
  });
  public MonthlyRepriceWorker(MonthlyRepriceDispatchService dispatch,MonthlyRepriceCostingService costing,
      CostRunWorkerProperties properties,@Value("${monthly.reprice.worker-enabled:true}") boolean enabled) {
    this.dispatch=dispatch;this.costing=costing;this.properties=properties;this.enabled=enabled;
  }
  @Scheduled(fixedDelayString="${monthly.reprice.poll-interval-ms:1000}")
  public void poll() {
    if(!enabled || !properties.isEnabled()) return;
    try {
      dispatch.checkCompleted();
      String worker=properties.resolvedWorkerId();var task=dispatch.claim(worker);
      if(task==null) return;
      var previous=SecurityContextHolder.getContext();var context=SecurityContextHolder.createEmptyContext();
      var auth=new UsernamePasswordAuthenticationToken("monthly-reprice-worker",null,List.of());
      auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE,task.getBusinessUnitType()));
      context.setAuthentication(auth);SecurityContextHolder.setContext(context);
      var renewal=heartbeat.scheduleAtFixedRate(()->{
        try {dispatch.heartbeat(task,worker);} catch(Exception ex) {log.warn("调价租约续期失败 task={}",task.getId());}
      },30,30,TimeUnit.SECONDS);
      try {costing.execute(task.getSourceNo(),task.getId(),task.getExecutionNo());}
      catch(Exception failure) {dispatch.fail(task,worker,failure);log.warn("调价任务失败 task={}: {}",task.getId(),failure.getMessage());}
      finally {renewal.cancel(false);SecurityContextHolder.setContext(previous);}
      dispatch.checkCompleted();
    } catch(Exception failure) {log.warn("调价后台调度失败",failure);}
  }
  @PreDestroy public void close() {heartbeat.shutdownNow();}
}
