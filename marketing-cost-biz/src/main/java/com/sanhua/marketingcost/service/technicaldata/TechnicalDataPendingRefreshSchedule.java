package com.sanhua.marketingcost.service.technicaldata;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Poll only durable changed work; opening or paging the workbench never schedules checks. */
@Component
@ConditionalOnProperty(name="quote.technical-data.pending-refresh.enabled",havingValue="true")
@lombok.extern.slf4j.Slf4j
public class TechnicalDataPendingRefreshSchedule {
  private boolean initialized;
  private final TechnicalDataPendingState state;
  private final TechnicalDataPendingRefresh refresh;
  public TechnicalDataPendingRefreshSchedule(TechnicalDataPendingState state,TechnicalDataPendingRefresh refresh) {
    this.state=state;this.refresh=refresh;
  }
  @Scheduled(fixedDelayString="${quote.technical-data.pending-refresh.delay-ms:1000}")
  public void tick() {
    try {if(!initialized) {state.initializeLegacy();initialized=true;} refresh.runBatch();}
    catch(RuntimeException failure) {log.warn("待办状态更新暂未完成，等待重试",failure);}
  }
}
