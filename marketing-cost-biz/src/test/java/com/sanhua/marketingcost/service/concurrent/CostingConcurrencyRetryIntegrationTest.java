package com.sanhua.marketingcost.service.concurrent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

/** Actual Spring interceptor ordering and MySQL rollback/read-view behavior. */
@Tag("integration")
class CostingConcurrencyRetryIntegrationTest {
  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.0").withDatabaseName("costing_retry_test");
  private static AnnotationConfigApplicationContext spring;
  private static JdbcTemplate jdbc;
  private static JdbcTemplate independent;
  private static DataSourceTransactionManager manager;
  private static WriteBoundary boundary;

  @Configuration
  @EnableAspectJAutoProxy(proxyTargetClass = true)
  @EnableTransactionManagement
  static class ConfigurationUnderTest {}

  public static class WriteBoundary {
    @RetryOnConcurrency
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public <T> T execute(Supplier<T> action) {
      return action.get();
    }
  }

  @BeforeAll
  static void start() {
    MYSQL.start();
    var source =
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(source);
    independent =
        new JdbcTemplate(
            new DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    manager = new DataSourceTransactionManager(source);
    jdbc.execute("CREATE TABLE inputs(id INT PRIMARY KEY, value INT NOT NULL)");
    jdbc.execute("CREATE TABLE attempts(id INT PRIMARY KEY)");
    jdbc.execute("CREATE TABLE lp_quote_cost_run_version(version_no VARCHAR(64))");
    jdbc.execute(
        "CREATE TABLE lp_quote_cost_version_sequence(sequence_day DATE PRIMARY KEY,last_number"
            + " BIGINT NOT NULL)");
    spring = new AnnotationConfigApplicationContext();
    spring.register(
        ConfigurationUnderTest.class, CostingConcurrencyRetryAspect.class, WriteBoundary.class);
    spring.registerBean("transactionManager", DataSourceTransactionManager.class, () -> manager);
    spring.registerBean(JdbcTemplate.class, () -> jdbc);
    spring.register(com.sanhua.marketingcost.service.costing.QuoteCostVersionSequence.class);
    spring.refresh();
    boundary = spring.getBean(WriteBoundary.class);
  }

  @BeforeEach
  void reset() {
    jdbc.update("DELETE FROM inputs");
    jdbc.update("INSERT INTO inputs VALUES (1,0),(2,0)");
    jdbc.update("DELETE FROM attempts");
    jdbc.update("DELETE FROM lp_quote_cost_run_version");
    jdbc.update("DELETE FROM lp_quote_cost_version_sequence");
  }

  @AfterAll
  static void stop() {
    if (spring != null) spring.close();
    MYSQL.stop();
  }

  @Test
  void retryRollsBackAllWritesAndReadsNewlyCommittedInputs() {
    var calls = new AtomicInteger();
    int value =
        boundary.execute(
            () -> {
              int attempt = calls.incrementAndGet();
              int input = jdbc.queryForObject("SELECT value FROM inputs WHERE id=1", Integer.class);
              jdbc.update("INSERT INTO attempts VALUES (?)", attempt);
              if (attempt == 1) {
                independent.update("UPDATE inputs SET value=7 WHERE id=1");
                throw new CannotAcquireLockException("simulated concurrency conflict");
              }
              return input;
            });
    assertThat(value).isEqualTo(7);
    assertThat(calls).hasValue(2);
    assertThat(jdbc.queryForList("SELECT id FROM attempts", Integer.class)).containsExactly(2);
  }

  @Test
  void realDeadlockRestartsOnlyTheVictimAndBothOperationsComplete() throws Exception {
    var barrier = new CyclicBarrier(2);
    var calls = new AtomicInteger();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var first = pool.submit(() -> deadlockingWrite(1, 2, barrier, calls));
      var second = pool.submit(() -> deadlockingWrite(2, 1, barrier, calls));
      first.get(20, TimeUnit.SECONDS);
      second.get(20, TimeUnit.SECONDS);
    }
    assertThat(calls.get()).isGreaterThanOrEqualTo(3).isLessThanOrEqualTo(5);
    assertThat(jdbc.queryForList("SELECT value FROM inputs ORDER BY id", Integer.class))
        .containsExactly(2, 2);
    assertThat(jdbc.queryForList("SELECT id FROM attempts ORDER BY id", Integer.class))
        .containsExactly(1, 2);
  }

  private void deadlockingWrite(int first, int second, CyclicBarrier barrier, AtomicInteger calls) {
    var attempts = new AtomicInteger();
    boundary.execute(
        () -> {
          calls.incrementAndGet();
          int attempt = attempts.incrementAndGet();
          jdbc.update("UPDATE inputs SET value=value+1 WHERE id=?", first);
          if (attempt == 1) {
            try {
              barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception failure) {
              throw new IllegalStateException(failure);
            }
          }
          jdbc.update("UPDATE inputs SET value=value+1 WHERE id=?", second);
          jdbc.update("INSERT INTO attempts VALUES (?)", first);
          return null;
        });
  }

  @Test
  void nestedTransactionPropagatesToItsOwnerInsteadOfRetryingRollbackOnlyWork() {
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                new TransactionTemplate(manager)
                    .execute(
                        tx ->
                            boundary.execute(
                                () -> {
                                  calls.incrementAndGet();
                                  jdbc.update("INSERT INTO attempts VALUES (1)");
                                  throw new CannotAcquireLockException("nested conflict");
                                })))
        .isInstanceOf(CannotAcquireLockException.class);
    assertThat(calls).hasValue(1);
    assertThat(jdbc.queryForList("SELECT id FROM attempts")).isEmpty();
  }

  @Test
  void retriesAreBoundedAndBusinessErrorsAreNotRetried() {
    var calls = new AtomicInteger();
    assertThatThrownBy(
            () ->
                boundary.execute(
                    () -> {
                      jdbc.update("INSERT INTO attempts VALUES (?)", calls.incrementAndGet());
                      throw new CannotAcquireLockException("persistent contention");
                    }))
        .isInstanceOf(CannotAcquireLockException.class)
        .hasMessage("persistent contention");
    assertThat(calls).hasValue(4);
    assertThat(jdbc.queryForList("SELECT id FROM attempts")).isEmpty();
    calls.set(0);
    assertThatThrownBy(
            () ->
                boundary.execute(
                    () -> {
                      calls.incrementAndGet();
                      throw new IllegalArgumentException("missing price");
                    }))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(calls).hasValue(1);
  }

  @Test
  void interruptedRequestDoesNotRetryAndPreservesInterruptStatus() {
    var calls = new AtomicInteger();
    try {
      assertThatThrownBy(
              () ->
                  boundary.execute(
                      () -> {
                        calls.incrementAndGet();
                        Thread.currentThread().interrupt();
                        throw new CannotAcquireLockException("cancelled");
                      }))
          .isInstanceOf(CannotAcquireLockException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(calls).hasValue(1);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void concurrentVersionReservationsContinueExistingNumbersAndSurviveOwnerRollback()
      throws Exception {
    var sequence =
        spring.getBean(com.sanhua.marketingcost.service.costing.QuoteCostVersionSequence.class);
    var day = java.time.LocalDate.of(2026, 10, 15);
    String prefix = "COST-20261015-";
    jdbc.update("INSERT INTO lp_quote_cost_run_version VALUES ('COST-20261015-0120-V1')");
    var numbers = new java.util.HashSet<Long>();
    try (var pool = Executors.newFixedThreadPool(4)) {
      var jobs = new java.util.ArrayList<java.util.concurrent.Future<Long>>();
      for (int i = 0; i < 40; i++) jobs.add(pool.submit(() -> sequence.next(day, prefix)));
      for (var job : jobs) numbers.add(job.get(20, TimeUnit.SECONDS));
    }
    assertThat(numbers).hasSize(40).contains(121L, 160L);
    assertThatThrownBy(
            () ->
                boundary.execute(
                    () -> {
                      assertThat(sequence.next(day, prefix)).isEqualTo(161L);
                      throw new IllegalArgumentException("costing validation failed");
                    }))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(sequence.next(day, prefix)).isEqualTo(162L);
  }

  @Test
  void productGuardCoordinatesIndependentInstancesAndAlwaysReleasesPooledConnections()
      throws Exception {
    try (var pool = new com.zaxxer.hikari.HikariDataSource()) {
      pool.setJdbcUrl(MYSQL.getJdbcUrl());
      pool.setUsername(MYSQL.getUsername());
      pool.setPassword(MYSQL.getPassword());
      pool.setMaximumPoolSize(4);
      var api = new com.sanhua.marketingcost.service.costing.ProductCostingExecutionGuard(pool);
      var worker = new com.sanhua.marketingcost.service.costing.ProductCostingExecutionGuard(pool);
      var active = new AtomicInteger();
      var maximum = new AtomicInteger();
      var start = new CyclicBarrier(4);
      try (var threads = Executors.newFixedThreadPool(4)) {
        var jobs = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (int n = 0; n < 4; n++) {
          var guard = n % 2 == 0 ? api : worker;
          jobs.add(
              threads.submit(
                  () -> {
                    try {
                      start.await(10, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                      throw new IllegalStateException(failure);
                    }
                    guard.execute(
                        scope(1L),
                        () -> {
                          maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                          try {
                            Thread.sleep(30);
                          } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                          } finally {
                            active.decrementAndGet();
                          }
                          return null;
                        });
                  }));
        }
        for (var job : jobs) job.get(15, TimeUnit.SECONDS);
      }
      assertThat(maximum).hasValue(1);
      assertThatThrownBy(
              () ->
                  api.execute(
                      scope(1L),
                      () -> {
                        throw new IllegalArgumentException("missing input");
                      }))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(worker.execute(scope(1L), () -> "released")).isEqualTo("released");

      var concurrent = new CyclicBarrier(2);
      try (var threads = Executors.newFixedThreadPool(2)) {
        var first = threads.submit(() -> api.execute(scope(1L), () -> rendezvous(concurrent)));
        var second = threads.submit(() -> worker.execute(scope(2L), () -> rendezvous(concurrent)));
        assertThat(first.get(15, TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(15, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private boolean rendezvous(CyclicBarrier barrier) {
    try {
      barrier.await(10, TimeUnit.SECONDS);
      return true;
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
  }

  private com.sanhua.marketingcost.service.costing.ProductCostingContext scope(long itemId) {
    var form = new com.sanhua.marketingcost.entity.OaForm();
    form.setId(1L);
    form.setOaNo("OA-TEST");
    var item = new com.sanhua.marketingcost.entity.OaFormItem();
    item.setId(itemId);
    return new com.sanhua.marketingcost.service.costing.ProductCostingContext(
        form, item, "PRODUCT", "2026-10", "test", null);
  }
}
