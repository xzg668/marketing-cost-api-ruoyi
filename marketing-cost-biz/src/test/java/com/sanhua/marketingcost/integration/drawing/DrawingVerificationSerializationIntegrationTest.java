package com.sanhua.marketingcost.integration.drawing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Tag("integration")
@Testcontainers
class DrawingVerificationSerializationIntegrationTest {
  @Container
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
      .withDatabaseName("drawing_serial_test");

  private DrawingVerificationCallGuard guard() {
    // Independent clients and connections, as in the API and worker processes.
    return new DrawingVerificationCallGuard(dataSource());
  }

  private DriverManagerDataSource dataSource() {
    return new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
  }

  @Test
  void simultaneousProductsWaitForThePreviousCompleteResponse() throws Exception {
    var calls = new AtomicInteger();
    var active = new AtomicInteger();
    var maximum = new AtomicInteger();
    var firstBody = new CountDownLatch(1);
    var finishBody = new CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      server.setExecutor(threads);
      server.createContext("/api/drawings/verify", exchange -> {
        int count = active.incrementAndGet();
        maximum.accumulateAndGet(count, Math::max);
        calls.incrementAndGet();
        exchange.getRequestBody().readAllBytes();
        byte[] body = (count == 1 ? DrawingVerificationFixtures.initial("serial-test").toString()
            : "{\"code\":\"busy\"}").getBytes(StandardCharsets.UTF_8);
        try {
          exchange.sendResponseHeaders(count == 1 ? 200 : 429, body.length);
          exchange.getResponseBody().write(body, 0, body.length - 1);
          exchange.getResponseBody().flush();
          firstBody.countDown();
          finishBody.await(5, TimeUnit.SECONDS);
          active.decrementAndGet();
          exchange.getResponseBody().write(body, body.length - 1, 1);
        } catch (InterruptedException stopped) {
          Thread.currentThread().interrupt();
        } finally {
          exchange.close();
        }
      });
      server.start();
      var start = new CountDownLatch(1);
      var ready = new CountDownLatch(3);
      var pending = new ArrayList<Future<DrawingVerificationPayload>>();
      for (int i = 0; i < 3; i++) {
        var properties = new DrawingVerificationProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.setRequestTimeout(Duration.ofSeconds(5));
        var json = new ObjectMapper();
        var client = new HttpDrawingVerificationClient(properties, new DrawingVerificationCodec(json), json, guard());
        int product = i;
        pending.add(threads.submit(() -> {
          ready.countDown();
          start.await();
          return client.verify(new DrawingVerificationClient.Query("PRODUCT-" + product,
              "request-" + product, (long) product, "SERIAL-TEST", (long) product, "2026-11"));
        }));
      }
      assertThat(ready.await(3, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      try {
        assertThat(firstBody.await(3, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(350);
        assertThat(calls.get()).as("only one request while its response body is incomplete").isEqualTo(1);
      } finally {
        finishBody.countDown();
      }
      for (var call : pending) assertThat(call.get(5, TimeUnit.SECONDS).summary().taskId()).isEqualTo("serial-test");
      assertThat(calls.get()).isEqualTo(3);
      assertThat(maximum.get()).isEqualTo(1);
    } finally {
      finishBody.countDown();
      server.stop(0);
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {429, 503, 200})
  void failedHttpOrInvalidResponseReleasesTheNextCall(int status) throws Exception {
    var count = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/api/drawings/verify", exchange -> {
      exchange.getRequestBody().readAllBytes();
      int n = count.incrementAndGet();
      byte[] body = (n == 1 ? "{\"code\":\"busy\"}"
          : DrawingVerificationFixtures.initial("after-failure").toString()).getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(n == 1 ? status : 200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      var properties = new DrawingVerificationProperties();
      properties.setEnabled(true);
      properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
      var json = new ObjectMapper();
      var client = new HttpDrawingVerificationClient(properties, new DrawingVerificationCodec(json), json, guard());
      var query = new DrawingVerificationClient.Query("DRAWING", "error-case", null, null, null, null);
      assertThatThrownBy(() -> client.verify(query)).isInstanceOf(DrawingVerificationException.class);
      var nextClient = new HttpDrawingVerificationClient(properties, new DrawingVerificationCodec(json), json, guard());
      assertThat(nextClient.verify(query).summary().taskId()).isEqualTo("after-failure");
      assertThat(count.get()).isEqualTo(2);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void queueTimeoutDoesNotExecuteAndDoesNotReleaseTheOtherOwnersLock() throws Exception {
    URI endpoint = URI.create("http://queue.test:8011/verify");
    var entered = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = threads.submit(() -> guard().execute(endpoint, () -> {
        entered.countDown();
        try { finish.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { throw new RuntimeException(error); }
        return "first";
      }));
      try {
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 2; i++) {
          var waiting = new DrawingVerificationCallGuard(dataSource(), Duration.ofMillis(120));
          assertThatThrownBy(() -> waiting.execute(endpoint, () -> { throw new AssertionError("must not call HTTP"); }))
              .isInstanceOfSatisfying(DrawingVerificationException.class, e -> assertThat(e.getCode())
                  .isEqualTo(DrawingVerificationException.Code.TIMEOUT));
        }
      } finally {
        finish.countDown();
      }
      assertThat(first.get(3, TimeUnit.SECONDS)).isEqualTo("first");
      assertThat(guard().execute(endpoint, () -> "next")).isEqualTo("next");
    }
  }

  @Test
  void interruptedWaiterNeverCallsHttpAndLeavesTheQueueUsable() throws Exception {
    URI endpoint = URI.create("http://interrupt.test:8011/verify");
    var failure = new AtomicReference<Throwable>();
    var interrupted = new AtomicReference<Boolean>();
    guard().execute(endpoint, () -> {
      Thread waiter = Thread.startVirtualThread(() -> {
        try {
          guard().execute(endpoint, () -> { throw new AssertionError("must not call HTTP"); });
        } catch (Throwable e) { failure.set(e); interrupted.set(Thread.currentThread().isInterrupted()); }
      });
      try {
        Thread.sleep(150);
        waiter.interrupt();
        waiter.join(2500);
      } catch (InterruptedException e) { throw new RuntimeException(e); }
      assertThat(waiter.isAlive()).isFalse();
      return null;
    });
    assertThat(interrupted.get()).isTrue();
    assertThat(failure.get()).isInstanceOfSatisfying(DrawingVerificationException.class,
        e -> assertThat(e.getCode()).isEqualTo(DrawingVerificationException.Code.INTERRUPTED));
    assertThat(guard().execute(endpoint, () -> "next")).isEqualTo("next");
  }

  @Test
  void endpointPathsAndDifferentClientsShareTheSameEngineLock() {
    var first = guard();
    var second = new DrawingVerificationCallGuard(dataSource(), Duration.ofMillis(100));
    first.execute(URI.create("http://ENGINE.test/verify"), () -> {
      assertThatThrownBy(() -> second.execute(URI.create("http://engine.test:80/other"),
          () -> { throw new AssertionError("same engine cannot overlap"); }))
          .isInstanceOf(DrawingVerificationException.class).hasMessageContaining("排队超时");
      return null;
    });
  }
}
