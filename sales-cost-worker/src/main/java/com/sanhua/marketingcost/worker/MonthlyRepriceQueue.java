package com.sanhua.marketingcost.worker;

import com.sanhua.marketingcost.entity.CostRunTask;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded continuous consumption. Claimed work fits available execution slots, with no local backlog. */
final class MonthlyRepriceQueue implements AutoCloseable {
  private static final Logger log = LoggerFactory.getLogger(MonthlyRepriceQueue.class);
  record Settings(int concurrency, int claimBatchSize, long idleInitialMs, long idleMaximumMs,
      long checkIntervalMs, int shutdownSeconds) {
    Settings {
      if (concurrency < 1 || concurrency > 16 || claimBatchSize < 1 || claimBatchSize > 64
          || idleInitialMs < 1 || idleInitialMs > idleMaximumMs || idleMaximumMs > 60000
          || checkIntervalMs < 1 || checkIntervalMs > 60000 || shutdownSeconds < 1 || shutdownSeconds > 300)
        throw new IllegalArgumentException("月度调价并发须1～16、领取上限1～64，退避/检查须1～60000ms，停机等待须1～300s");
    }
  }
  private final Settings settings;
  private final IntFunction<List<CostRunTask>> claim;
  private final Consumer<CostRunTask> execute;
  private final Runnable check;
  private final ExecutorService executions;
  private final ExecutorService checker;
  private final AtomicBoolean checking = new AtomicBoolean();
  private final AtomicInteger active = new AtomicInteger();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicBoolean closing = new AtomicBoolean();
  private final Semaphore changed = new Semaphore(0);
  private final Thread coordinator;

  MonthlyRepriceQueue(Settings settings, IntFunction<List<CostRunTask>> claim,
      Consumer<CostRunTask> execute, Runnable check) {
    this.settings = settings; this.claim = claim; this.execute = execute; this.check = check;
    var sequence = new AtomicInteger();
    executions = Executors.newFixedThreadPool(settings.concurrency(), r ->
        daemon(r, "monthly-reprice-execute-" + sequence.incrementAndGet()));
    checker = Executors.newSingleThreadExecutor(r -> daemon(r,"monthly-reprice-check"));
    coordinator = daemon(this::consume, "monthly-reprice-dispatch");
  }

  void start() {
    if (!closing.get() && started.compareAndSet(false, true)) coordinator.start();
  }

  private void consume() {
    long idle = settings.idleInitialMs(), nextClaim = 0, nextCheck = 0;
    while (!closing.get()) {
      boolean found = false;
      int free = settings.concurrency() - active.get();
      if (free > 0 && System.nanoTime() >= nextClaim) {
        try {
          int limit = Math.min(free, settings.claimBatchSize());
          var tasks = claim.apply(limit);
          if (tasks.size() > limit) throw new IllegalStateException("调价领取数超过可用执行槽位");
          for (var task : tasks) {
            active.incrementAndGet();
            try { executions.execute(() -> run(task)); }
            catch (RejectedExecutionException stopped) { active.decrementAndGet(); throw stopped; }
          }
          found = !tasks.isEmpty();
        } catch (Exception failure) {
          log.warn("调价队列领取失败，将退避后继续", failure);
        }
        nextClaim = found ? 0 : System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(idle);
        idle = found ? settings.idleInitialMs() : Math.min(settings.idleMaximumMs(), idle * 2);
      }
      if (closing.get()) break;
      if (System.nanoTime() >= nextCheck) {
        nextCheck = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settings.checkIntervalMs());
        if(checking.compareAndSet(false,true)) checker.execute(()->{
          try {check.run();}
          catch(Exception failure) {log.warn("调价批次检查失败，下次检查继续",failure);}
          finally {checking.set(false);}
        });
      }
      if (found) continue;
      try {
        long wait = Math.min(settings.idleMaximumMs(), untilMillis(nextCheck));
        if (active.get() < settings.concurrency()) wait = Math.min(wait, untilMillis(nextClaim));
        if (changed.tryAcquire(wait, TimeUnit.MILLISECONDS)) {
          changed.drainPermits(); nextClaim = 0; idle = settings.idleInitialMs();
        }
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt(); break;
      }
    }
  }

  private static long untilMillis(long deadline) {
    return Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
  }

  private void run(CostRunTask task) {
    try { execute.accept(task); }
    catch (Exception failure) { log.warn("调价任务收尾失败 task={}，租约保留供接管", task.getId(), failure); }
    finally { active.decrementAndGet(); changed.release(); }
  }

  @Override public void close() {
    if (!closing.compareAndSet(false, true)) return;
    changed.release();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.shutdownSeconds());
    try {
      if (started.get()) coordinator.join(Math.max(1, remainingMillis(deadline)));
      if (coordinator.isAlive()) coordinator.interrupt();
      executions.shutdown();
      checker.shutdown();
      if (!executions.awaitTermination(Math.max(1, remainingMillis(deadline)), TimeUnit.MILLISECONDS)) {
        executions.shutdownNow();
        log.warn("调价停机等待超时；未完成任务由数据库租约接管");
      }
      if(!checker.awaitTermination(Math.max(1,remainingMillis(deadline)),TimeUnit.MILLISECONDS)) checker.shutdownNow();
    } catch (InterruptedException interrupted) {
      coordinator.interrupt(); executions.shutdownNow(); checker.shutdownNow(); Thread.currentThread().interrupt();
    }
  }

  private static long remainingMillis(long deadline) {
    return TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
  }
  private static Thread daemon(Runnable action, String name) {
    var thread = new Thread(action, name); thread.setDaemon(true); return thread;
  }
}
