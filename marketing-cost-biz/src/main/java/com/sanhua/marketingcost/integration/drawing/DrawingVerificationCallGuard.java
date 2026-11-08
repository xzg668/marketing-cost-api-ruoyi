package com.sanhua.marketingcost.integration.drawing;

import static com.sanhua.marketingcost.integration.drawing.DrawingVerificationException.Code.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** API 与 worker 共用图库服务锁；只包围一次 HTTP 调用，不开启业务事务。 */
@Component
public class DrawingVerificationCallGuard {
  private static final Logger log = LoggerFactory.getLogger(DrawingVerificationCallGuard.class);
  private final DataSource dataSource;
  private final Duration queueTimeout;

  @Autowired
  public DrawingVerificationCallGuard(DataSource dataSource) {
    this(dataSource, Duration.ofMinutes(30));
  }

  DrawingVerificationCallGuard(DataSource dataSource, Duration queueTimeout) {
    this.dataSource = dataSource;
    this.queueTimeout = queueTimeout;
  }

  public <T> T execute(URI endpoint, Supplier<T> call) {
    checkInterrupted();
    String key = key(endpoint);
    // Dedicated connection: the lock must remain held until the complete HTTP body is read.
    try (Connection connection = dataSource.getConnection()) {
      try {
        acquire(connection, key);
      } catch (SQLException failure) {
        // The server may have granted the lock before the connection failed/interrupted.
        abort(connection, failure);
        throw failure;
      }
      try {
        checkInterrupted();
        return call.get();
      } finally {
        release(connection, key);
      }
    } catch (SQLException failure) {
      checkInterrupted();
      throw new DrawingVerificationException(TRANSPORT_ERROR, "电子图库调用排队失败，请稍后重试",
          null, null, failure);
    }
  }

  private void acquire(Connection connection, String key) throws SQLException {
    long deadline = System.nanoTime() + queueTimeout.toNanos();
    try (var statement = connection.prepareStatement("SELECT GET_LOCK(?,?)")) {
      statement.setString(1, key);
      while (true) {
        checkInterrupted();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
          throw new DrawingVerificationException(TIMEOUT, "电子图库调用排队超时，请稍后重试",
              null, null, null);
        }
        // Short waits allow cancellation without sending a second HTTP request.
        statement.setDouble(2, Math.min(1.0, remaining / 1_000_000_000.0));
        try (var result = statement.executeQuery()) {
          if (!result.next()) throw new SQLException("图库服务锁无返回结果");
          int acquired = result.getInt(1);
          if (result.wasNull()) throw new SQLException("图库服务锁获取失败");
          if (acquired == 1) return;
        }
      }
    }
  }

  private static void checkInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw new DrawingVerificationException(INTERRUPTED, "电子图库调用排队已中断",
          null, null, null);
    }
  }

  private String key(URI endpoint) {
    int port = endpoint.getPort() >= 0 ? endpoint.getPort()
        : ("https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80);
    String service = endpoint.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    try {
      return "drawing-http:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(service.getBytes(StandardCharsets.UTF_8))).substring(0, 48);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private void release(Connection connection, String key) {
    try (var statement = connection.prepareStatement("SELECT RELEASE_LOCK(?)")) {
      statement.setString(1, key);
      try (var result = statement.executeQuery()) {
        if (!result.next() || result.getInt(1) != 1) throw new SQLException("图库服务锁未正常释放");
      }
    } catch (SQLException failure) {
      // Do not return a pooled connection that could still own the named lock.
      abort(connection, failure);
      log.warn("图库服务锁连接已作废", failure);
    }
  }

  private void abort(Connection connection, SQLException failure) {
    try {
      connection.abort(Runnable::run);
    } catch (SQLException abortFailure) {
      failure.addSuppressed(abortFailure);
    }
  }
}
