package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
@DisplayName("QCBP-26 数据库死锁回滚与安全重试")
class CollaborationDatabaseDeadlockRecoveryIntegrationTest extends BomMapperTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  private final String marker = "Q26-DL-" + UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void schema() throws Exception {
    try (Connection connection = openConnection(); Statement statement = connection.createStatement();
        InputStream input = CollaborationDatabaseDeadlockRecoveryIntegrationTest.class
            .getResourceAsStream("/db/V206__quote_bom_price_collaboration_schema.sql")) {
      assertThat(input).isNotNull();
      for (String fragment : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
        if (!fragment.isBlank()) statement.execute(fragment);
      }
    }
  }

  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM lp_quote_collaboration_task WHERE collaboration_no LIKE ?", marker + "%");
  }

  @Test
  void deadlockRollsBackOneTransactionAndRetryInStableOrderRecovers() throws Exception {
    long one = insertTask(1);
    long two = insertTask(2);
    CountDownLatch firstLocks = new CountDownLatch(2);
    CountDownLatch secondUpdates = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      Future<Throwable> left = executor.submit(
          () -> opposingUpdate(one, two, firstLocks, secondUpdates));
      Future<Throwable> right = executor.submit(
          () -> opposingUpdate(two, one, firstLocks, secondUpdates));
      assertThat(firstLocks.await(10, TimeUnit.SECONDS)).isTrue();
      secondUpdates.countDown();
      var failures = java.util.stream.Stream.of(
          left.get(20, TimeUnit.SECONDS), right.get(20, TimeUnit.SECONDS))
          .filter(java.util.Objects::nonNull).toList();

      assertThat(failures).hasSize(1);
      assertThat(failures.get(0)).isInstanceOf(TransientDataAccessException.class);
    }

    TransactionTemplate retry = new TransactionTemplate(transactionManager);
    retry.executeWithoutResult(status -> {
      jdbc.update("UPDATE lp_quote_collaboration_task SET last_error_code='RECOVERED' WHERE id=?", one);
      jdbc.update("UPDATE lp_quote_collaboration_task SET last_error_code='RECOVERED' WHERE id=?", two);
    });
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_collaboration_task WHERE id IN (?,?) AND last_error_code='RECOVERED'",
        Integer.class, one, two)).isEqualTo(2);
  }

  private Throwable opposingUpdate(
      long first, long second, CountDownLatch firstLocks, CountDownLatch secondUpdates) {
    try {
      new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
        jdbc.update("UPDATE lp_quote_collaboration_task SET last_error_code='LOCKED' WHERE id=?", first);
        firstLocks.countDown();
        try {
          if (!secondUpdates.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("死锁同步超时");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("死锁同步被中断", interrupted);
        }
        jdbc.update("UPDATE lp_quote_collaboration_task SET last_error_code='SECOND' WHERE id=?", second);
      });
      return null;
    } catch (Throwable failure) {
      return failure;
    }
  }

  private long insertTask(int index) {
    String no = marker + "-" + index;
    long oaFormId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    jdbc.update("""
        INSERT INTO lp_quote_collaboration_task
          (collaboration_no,oa_form_id,oa_no,round_no,business_unit_type,accounting_month,
           source_system,master_status)
        VALUES (?,?,?,?,?,'2026-08','TEST','WAIT_TECH')
        """, no, oaFormId, no, 1, "COMMERCIAL");
    return jdbc.queryForObject(
        "SELECT id FROM lp_quote_collaboration_task WHERE collaboration_no=?", Long.class, no);
  }
}
