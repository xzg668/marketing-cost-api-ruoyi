package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.entity.CostRunTask;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiFunction;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.DigestUtils;

/** Short global admission only. Arithmetic and customer persistence never hold this mutex. */
@Service
public class MonthlyRepriceAdmission {
  private static final Logger log = LoggerFactory.getLogger(MonthlyRepriceAdmission.class);
  private final DataSource dataSource;
  private final JdbcTemplate jdbc;
  private final MonthlyRepriceCapacity capacity;
  private final TransactionTemplate transaction;
  private final java.util.concurrent.atomic.AtomicLong nextPolicyWarning = new java.util.concurrent.atomic.AtomicLong();

  public MonthlyRepriceAdmission(DataSource dataSource, PlatformTransactionManager manager,
      MonthlyRepriceCapacity capacity) {
    this.dataSource = dataSource;
    this.jdbc = new JdbcTemplate(dataSource);
    this.capacity = capacity;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    transaction.setTimeout(15);
  }

  List<CostRunTask> claim(int requested, BiFunction<Integer, String, List<CostRunTask>> claim) {
    var rules = capacity.rules();
    return transaction.execute(status -> {
      // Reuse the transaction connection: admission must not consume a second pool slot.
      var connection = DataSourceUtils.getConnection(dataSource);
      String key = lockKey(connection);
      if (!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT GET_LOCK(?,0)", Integer.class, key)))
        return List.of();
      long started = System.nanoTime();
      int[] claimed = {0};
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCompletion(int completion) {
          try {
            // The row transaction has committed before pacing, but the global mutex still
            // serializes admission. A burst is at most one second and the global active limit.
            if (completion == STATUS_COMMITTED) pace(started, claimed[0], rules.claimsPerSecond());
          } finally { release(connection, key); }
        }
      });
      var live = jdbc.queryForObject("""
          SELECT COUNT(*), COALESCE(SUM(COALESCE(JSON_UNQUOTE(
            JSON_EXTRACT(result_summary_json,'$.admissionPolicy')),'')<>?),0)
          FROM lp_cost_run_task WHERE scene='MONTHLY_REPRICE' AND status='RUNNING'
            AND lock_expire_time>NOW(6)
          """, (rs, row) -> new long[] {rs.getLong(1), rs.getLong(2)}, rules.identity());
      // A rolling configuration change may only start after existing incompatible leases drain.
      // No customer is marked failed for a capacity/configuration condition.
      if (live[1] != 0) {
        long now=System.nanoTime(), next=nextPolicyWarning.get();
        if ((next==0 || now-next>=0) && nextPolicyWarning.compareAndSet(next,now+TimeUnit.MINUTES.toNanos(1)))
          log.warn("调价容量参数与{}个活跃租约不同，等待原任务完成或统一worker配置；本实例策略={}",live[1],rules.identity());
        return List.of();
      }
      long maximum = jdbc.queryForObject("SELECT @@GLOBAL.max_connections", Long.class);
      long connected = jdbc.queryForObject("SHOW GLOBAL STATUS LIKE 'Threads_connected'",
          (rs, row) -> rs.getLong(2));
      int allowed = rules.allowance(requested, live[0], maximum, connected);
      if (allowed == 0) return List.of();
      var result = claim.apply(allowed, rules.identity());
      if (result.size() > allowed) throw new IllegalStateException("调价领取超过数据库容量许可");
      claimed[0] = result.size();
      return List.copyOf(result);
    });
  }

  private static String lockKey(Connection connection) {
    try {
      String catalog = connection.getCatalog();
      if (catalog == null || catalog.isBlank()) throw new IllegalStateException("调价领取必须指定数据库");
      return "monthly-reprice-admission:" + DigestUtils.md5DigestAsHex(catalog.getBytes(StandardCharsets.UTF_8));
    } catch (SQLException failure) { throw new IllegalStateException("无法识别调价数据库", failure); }
  }

  private static void pace(long started, int claimed, int rate) {
    long deadline = started + TimeUnit.SECONDS.toNanos(1) * claimed / rate;
    long remaining;
    while (!Thread.currentThread().isInterrupted() && (remaining = deadline - System.nanoTime()) > 0)
      LockSupport.parkNanos(remaining);
    // Interrupting shutdown must still return already committed leases to the queue.
  }

  private static void release(Connection connection, String key) {
    try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
      statement.setString(1, key);
      try (var result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) != 1) throw new SQLException("Admission lock release failed");
      }
    } catch (SQLException failure) {
      // Never return a session still owning an advisory lock to the pool, or turn committed
      // leases into an apparent failed claim. Expiry is the fallback if the process disappears.
      try { connection.abort(Runnable::run); }
      catch (SQLException abortFailure) { failure.addSuppressed(abortFailure); }
      log.error("调价领取锁释放异常，已隔离数据库连接", failure);
    }
  }
}
