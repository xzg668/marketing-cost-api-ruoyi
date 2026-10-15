package com.sanhua.marketingcost.service.concurrent;

import java.util.concurrent.ThreadLocalRandom;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** The retry surrounds Spring's transaction interceptor, including commit and rollback. */
@Aspect
@Component
@Order(Ordered.LOWEST_PRECEDENCE - 1)
public class CostingConcurrencyRetryAspect {
  private static final Logger log = LoggerFactory.getLogger(CostingConcurrencyRetryAspect.class);
  private static final int MAX_ATTEMPTS = 4;

  @Around("@annotation(com.sanhua.marketingcost.service.concurrent.RetryOnConcurrency)")
  public Object execute(ProceedingJoinPoint operation) throws Throwable {
    // A nested REQUIRED call cannot restart its owner's transaction. Propagate to the outer
    // boundary.
    if (TransactionSynchronizationManager.isActualTransactionActive()) return operation.proceed();
    for (int attempt = 1; ; attempt++) {
      try {
        return operation.proceed();
      } catch (ConcurrencyFailureException conflict) {
        if (attempt == MAX_ATTEMPTS || Thread.currentThread().isInterrupted()) throw conflict;
        long delay = (50L << (attempt - 1)) + ThreadLocalRandom.current().nextLong(26);
        log.info(
            "核算并发写入已回滚，重试 {}/{}：{}，等待 {}ms",
            attempt + 1,
            MAX_ATTEMPTS,
            operation.getSignature().toShortString(),
            delay);
        try {
          Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          conflict.addSuppressed(interrupted);
          throw conflict;
        }
      }
    }
  }
}
