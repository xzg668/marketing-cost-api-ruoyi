package com.sanhua.marketingcost.service.costing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.stereotype.Component;

/** Serializes one quotation item/month across API and worker processes, across stage commits. */
@Component
public class ProductCostingExecutionGuard {
  private static final Logger log = LoggerFactory.getLogger(ProductCostingExecutionGuard.class);
  private final DataSource dataSource;

  public ProductCostingExecutionGuard(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  public <T> T execute(ProductCostingContext scope, Supplier<T> work) {
    // A dedicated connection owns the advisory lock. Business stages retain their existing
    // short transactions; different items/months never share this lock.
    try (Connection connection = dataSource.getConnection()) {
      String key = key(connection.getCatalog(), scope);
      try (var statement = connection.prepareStatement("SELECT GET_LOCK(?,30)")) {
        statement.setString(1, key);
        try (var result = statement.executeQuery()) {
          if (!result.next() || result.getInt(1) != 1)
            throw new CannotAcquireLockException("本产品正在核算，请稍后重试");
        }
      }
      try {
        return work.get();
      } finally {
        release(connection, key);
      }
    } catch (SQLException failure) {
      throw new DataAccessResourceFailureException("无法协调本产品的并发核算", failure);
    }
  }

  private String key(String database, ProductCostingContext scope) {
    String identity = database + ":" + scope.itemId() + ":" + scope.periodMonth();
    try {
      return "quote-item:"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(identity.getBytes(StandardCharsets.UTF_8)))
              .substring(0, 48);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private void release(Connection connection, String key) {
    try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
      statement.setString(1, key);
      try (var result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) != 1) throw new SQLException("核算互斥锁未正常释放");
      }
    } catch (SQLException failure) {
      // Never return a connection holding a named lock to the pool.
      try {
        connection.abort(Runnable::run);
      } catch (SQLException abortFailure) {
        failure.addSuppressed(abortFailure);
      }
      log.warn("核算互斥连接已作废", failure);
    }
  }
}
