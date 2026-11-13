package com.sanhua.marketingcost.scheduler;

import com.sanhua.marketingcost.service.pricing.MaterialPriceSourceRefresh;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MaterialPriceSourceScheduler {
  private final MaterialPriceSourceRefresh refresh;
  private final boolean enabled;
  public MaterialPriceSourceScheduler(MaterialPriceSourceRefresh refresh,@Value("${price.material-sources.enabled:true}") boolean enabled) {
    this.refresh=refresh;this.enabled=enabled;
  }
  @Scheduled(cron="${price.material-sources.cron:0 0 4 * * *}",zone="Asia/Shanghai")
  public void update() {
    if (!enabled) return;
    for (String unit:List.of("COMMERCIAL","HOUSEHOLD")) {
      try { log.info("物料多价格来源定时更新：unit={},result={}",unit,refresh.refresh(unit,"SCHEDULED_0400")); }
      catch (RuntimeException failure) { log.error("物料多价格来源更新失败，原已生成月份保留：unit={}",unit,failure); }
    }
  }
}
