package com.sanhua.marketingcost.worker;

import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDatabase;
import com.sanhua.marketingcost.service.reprice.MonthlyRepriceCapacity;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Check both physical pools before accepting work; ordinary quotation keeps its own capacity. */
@Component
public class MonthlyRepriceWorkerResources {
  private static final Logger log = LoggerFactory.getLogger(MonthlyRepriceWorkerResources.class);
  private final DataSource application;
  private final MonthlyPriceDatabase monthly;
  private final MonthlyRepriceCapacity capacity;

  public MonthlyRepriceWorkerResources(DataSource application, MonthlyPriceDatabase monthly,
      MonthlyRepriceCapacity capacity) {
    this.application = application;
    this.monthly = monthly;
    this.capacity = capacity;
  }

  void verify(String instance, int concurrency, CostRunWorkerProperties quotation) {
    try {
      var pool = application.unwrap(HikariDataSource.class);
      int quoteThreads = quotation.getScenes().isEmpty() ? 0 : quotation.getThreads();
      verifyLimits(pool.getMaximumPoolSize(), monthly.maximumPoolSize(), concurrency, quoteThreads);
      log.info("调价实例容量 instance={} primaryPool={} monthlyPool={} monthlyConcurrency={} quotationThreads={} globalPolicy={}",
          instance, pool.getMaximumPoolSize(), monthly.maximumPoolSize(), concurrency, quoteThreads,capacity.policyIdentity());
    } catch (SQLException failure) { throw new IllegalStateException("无法核对调价数据库连接池容量", failure); }
  }

  static void verifyLimits(int primaryPool, int monthlyPool, int concurrency, int quoteThreads) {
    // Each product: advisory lock + outer transaction + independent inner transaction.
    // Dispatch, heartbeat, scheduler and the separate checker (mutex + paged read) remain available.
    long requiredPrimary = 3L * (concurrency + (long) quoteThreads) + 5;
    long requiredMonthly = concurrency + (long) quoteThreads;
    if (concurrency < 1 || quoteThreads < 0 || primaryPool < requiredPrimary || monthlyPool < requiredMonthly)
      throw new IllegalArgumentException("调价并发与普通报价预留需要主连接池至少" + requiredPrimary
          + "、月价连接池至少" + requiredMonthly + "；请增加连接池容量或降低调价并发");
  }
}
