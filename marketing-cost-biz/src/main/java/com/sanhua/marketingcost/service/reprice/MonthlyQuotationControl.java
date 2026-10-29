package com.sanhua.marketingcost.service.reprice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Serializes acceptance against quotation publication, without a whole-batch transaction. */
@Component
public class MonthlyQuotationControl {
  public static class QuotationBlocked extends IllegalStateException {
    public QuotationBlocked() { super("当前业务单元正在月度调价，业务总监确认后才能报价"); }
  }

  private record Lease(String unit, String key, Connection connection) {}
  private final DataSource dataSource;
  private final ThreadLocal<Lease> held = new ThreadLocal<>();

  public MonthlyQuotationControl(DataSource dataSource) { this.dataSource = dataSource; }

  public boolean blocked(String unit) {
    MonthlyRepriceContract.unit(unit);
    Lease existing = held.get();
    if (existing != null && existing.unit().equals(unit)) return blocked(existing.connection(), unit);
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(true);
      return blocked(connection, unit);
    } catch (SQLException failure) {
      throw new DataAccessResourceFailureException("无法检查当前业务单元的报价状态", failure);
    }
  }

  public void requireAllowed(String unit) {
    if (blocked(unit)) throw new QuotationBlocked();
  }

  public <T> T ordinaryWrite(String unit, Supplier<T> work) {
    return serialized(unit, () -> {
      requireAllowed(unit);
      return work.get();
    });
  }

  public <T> T serialized(String unit, Supplier<T> work) {
    MonthlyRepriceContract.unit(unit);
    Lease existing = held.get();
    if (existing != null) {
      if (!existing.unit().equals(unit)) throw new IllegalStateException("一次报价写入不能跨业务单元");
      return work.get();
    }
    Lease lease = acquire(unit);
    held.set(lease);
    boolean deferred = TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.isActualTransactionActive();
    if (deferred) {
      // Nested services cannot release the fence before their owner's transaction commits.
      TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override public void afterCompletion(int status) { release(lease); }
      });
    }
    try { return work.get(); }
    finally { if (!deferred) release(lease); }
  }

  private Lease acquire(String unit) {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
    while (System.nanoTime() < deadline) {
      try {
        Connection connection = dataSource.getConnection();
        boolean acquired = false;
        try {
          connection.setAutoCommit(true);
          String key = key(connection.getCatalog(), unit);
          try (var statement = connection.prepareStatement("SELECT GET_LOCK(?,0)")) {
            statement.setString(1, key);
            try (var result = statement.executeQuery()) {
              acquired = result.next() && result.getInt(1) == 1;
            }
          }
          if (acquired) return new Lease(unit, key, connection);
        } finally {
          // Waiting callers must not exhaust the pool needed by the current publisher.
          if (!acquired) connection.close();
        }
        Thread.sleep(20);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new CannotAcquireLockException("报价状态协调已中断", interrupted);
      } catch (SQLException failure) {
        throw new DataAccessResourceFailureException("无法协调业务单元的报价状态", failure);
      }
    }
    throw new CannotAcquireLockException("当前报价操作尚未结束，请稍后重试");
  }

  private boolean blocked(Connection connection, String unit) {
    try (var statement = connection.prepareStatement("""
        SELECT EXISTS(SELECT 1 FROM lp_monthly_reprice_batch
          WHERE business_unit_type=? AND
            ((contract_version=1 AND status<>'CONFIRMED') OR
             (contract_version IS NULL AND status IN ('CREATED','PREPARING','RUNNING','WAIT_CONFIRM'))))
        """)) {
      statement.setString(1, unit);
      try (var result = statement.executeQuery()) {
        if (!result.next()) throw new SQLException("报价控制未返回结果");
        return result.getBoolean(1);
      }
    } catch (SQLException failure) {
      throw new DataAccessResourceFailureException("无法检查当前业务单元的报价状态", failure);
    }
  }

  private String key(String database, String unit) {
    try {
      return "quotation-control:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest((database + ":" + unit).getBytes(StandardCharsets.UTF_8))).substring(0, 44);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private void release(Lease lease) {
    held.remove();
    try (Connection connection = lease.connection()) {
      try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
        statement.setString(1, lease.key());
        try (var result = statement.executeQuery()) {
          if (!result.next() || result.getInt(1) != 1) connection.abort(Runnable::run);
        }
      } catch (SQLException failure) {
        connection.abort(Runnable::run);
      }
    } catch (SQLException failure) {
      throw new DataAccessResourceFailureException("报价控制连接未能正常释放", failure);
    }
  }
}
