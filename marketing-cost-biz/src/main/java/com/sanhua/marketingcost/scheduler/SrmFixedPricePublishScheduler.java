package com.sanhua.marketingcost.scheduler;

import com.sanhua.marketingcost.service.srm.SrmFixedPricePublishService;
import com.sanhua.marketingcost.service.srm.SrmFixedPricePublishService.PublishResult;
import com.sanhua.marketingcost.service.srm.SrmFixedPricePublishService.Status;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 在 EasyData 每日落地完成后发布上一日 SRM 固定采购价。 */
@Slf4j
@Component
public class SrmFixedPricePublishScheduler {

  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  private final SrmFixedPricePublishService publishService;
  private final boolean enabled;

  public SrmFixedPricePublishScheduler(
      SrmFixedPricePublishService publishService,
      @Value("${srm.fixed-price.enabled:true}") boolean enabled) {
    this.publishService = publishService;
    this.enabled = enabled;
  }

  @Scheduled(
      cron = "${srm.fixed-price.cron:0 */10 1-4 * * *}",
      zone = "Asia/Shanghai")
  public void publishYesterday() {
    if (!enabled) {
      return;
    }
    LocalDate batchDate = LocalDate.now(BUSINESS_ZONE).minusDays(1);
    try {
      PublishResult result = publishService.publishIfReady(batchDate);
      if (result.status() == Status.PUBLISHED) {
        log.info(
            "SRM fixed price published: batchDate={}, rawRows={}, validRows={}, skippedRows={}, insertedRows={}",
            result.batchDate(),
            result.rawRows(),
            result.validRows(),
            result.skippedRows(),
            result.insertedRows());
      } else if (result.status() == Status.REJECTED) {
        log.warn(
            "SRM fixed price rejected: batchDate={}, rawRows={}, validRows={}, reason={}",
            result.batchDate(),
            result.rawRows(),
            result.validRows(),
            result.message());
      } else {
        log.debug(
            "SRM fixed price not published: batchDate={}, status={}, reason={}",
            result.batchDate(),
            result.status(),
            result.message());
      }
    } catch (RuntimeException exception) {
      log.error("SRM fixed price publish failed: batchDate={}", batchDate, exception);
    }
  }
}
