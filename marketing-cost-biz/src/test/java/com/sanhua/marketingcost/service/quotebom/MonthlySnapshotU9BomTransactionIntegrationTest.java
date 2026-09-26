package com.sanhua.marketingcost.service.quotebom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

/** 用真实 MySQL 读视图验证首次核算，单纯 mock Mapper 无法复现独立事务导致的假缺口。 */
@Tag("integration")
class MonthlySnapshotU9BomTransactionIntegrationTest {
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
      .withDatabaseName("quote_monthly_transaction_test")
      .withUsername("test")
      .withPassword("test");
  private static AnnotationConfigApplicationContext spring;
  private static JdbcTemplate jdbc;
  private static TransactionTemplate costing;
  private static QuoteBomMonthlySnapshotMapper mapper;
  private static CurrentU9BomGateway gateway;
  private static final LiveU9BomGateway LIVE = mock(LiveU9BomGateway.class);

  @BeforeAll
  static void start() throws Exception {
    MYSQL.start();
    DataSource source = new DriverManagerDataSource(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(source);
    String migration = resource("db/V140__quote_bom_monthly_snapshot.sql");
    int createStart = migration.indexOf("CREATE TABLE IF NOT EXISTS");
    jdbc.execute(migration.substring(createStart, migration.indexOf(';', createStart) + 1));
    jdbc.execute("ALTER TABLE lp_quote_bom_monthly_snapshot ADD price_org_code VARCHAR(32)");
    jdbc.execute(resource("db/V233__u9_monthly_first_query_snapshot.sql"));

    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(source);
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.addMapper(QuoteBomMonthlySnapshotMapper.class);
    factory.setConfiguration(configuration);
    mapper = new SqlSessionTemplate(factory.getObject())
        .getMapper(QuoteBomMonthlySnapshotMapper.class);
    var manager = new DataSourceTransactionManager(source);
    spring = new AnnotationConfigApplicationContext();
    spring.register(Transactions.class);
    spring.registerBean("transactionManager", DataSourceTransactionManager.class, () -> manager);
    spring.registerBean(QuoteBomMonthlySnapshotMapper.class, () -> mapper);
    spring.registerBean(LiveU9BomGateway.class, () -> LIVE);
    spring.registerBean(MonthlySnapshotU9BomGateway.class);
    spring.refresh();
    gateway = spring.getBean(CurrentU9BomGateway.class);
    costing = new TransactionTemplate(manager);
    costing.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  @BeforeEach
  void clearIsolatedTestData() {
    jdbc.execute("TRUNCATE TABLE lp_quote_bom_monthly_snapshot");
    reset(LIVE);
    when(LIVE.readLive(any())).thenAnswer(invocation -> {
      QuoteBomReadContext scope = invocation.getArgument(0);
      return CurrentU9BomResult.available("U9", "V1", "RAW-" + scope.accountingMonth(),
          42, "f".repeat(64));
    });
  }

  @Test
  void firstCostingCanReadItsNewSnapshotWithoutLeavingTheOuterReadView() {
    var september = context("2026-09");
    Long id = costing.execute(tx -> {
      // 发起核算先查 OA / BOM 状态，已建立 REPEATABLE READ 读视图。
      assertThat(jdbc.queryForObject(
          "SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot", Integer.class)).isZero();
      var result = gateway.read(september);
      assertThat(result.monthlySnapshotCreated()).isTrue();
      var saved = mapper.selectById(result.monthlySnapshotId());
      assertThat(saved).as("后续 BOM 状态查询必须看到本次生成的月快照").isNotNull();
      assertThat(saved.getSnapshotIdentityKey())
          .isEqualTo(U9MonthlySnapshotIdentity.from(september).identityKey());
      assertThat(saved.getBomBatchId()).isEqualTo("RAW-2026-09");
      assertThat(mapper.selectU9MonthlyByIdentity(saved.getSnapshotIdentityKey()).getId())
          .as("第一棵有效 BOM 树也应能查到同一个月快照").isEqualTo(saved.getId());
      return saved.getId();
    });
    assertThat(mapper.selectById(id).getSyncStatus()).isEqualTo("SUCCESS");
    verify(LIVE).readLive(september);
  }

  @Test
  void sameMonthReentryAndRefreshReuseSnapshotWhileNextMonthReadsRawAgain() {
    var august = context("2026-08");
    var september = context("2026-09");
    var first = costing.execute(tx -> gateway.read(august));
    var reentry = costing.execute(tx -> gateway.read(august));
    var refresh = costing.execute(tx -> gateway.read(august));
    var nextMonth = costing.execute(tx -> gateway.read(september));

    assertThat(reentry.monthlySnapshotId()).isEqualTo(first.monthlySnapshotId());
    assertThat(refresh.monthlySnapshotId()).isEqualTo(first.monthlySnapshotId());
    assertThat(reentry.monthlySnapshotCreated()).isFalse();
    assertThat(nextMonth.monthlySnapshotId()).isNotEqualTo(first.monthlySnapshotId());
    assertThat(mapper.selectById(first.monthlySnapshotId()).getBomBatchId()).isEqualTo("RAW-2026-08");
    assertThat(mapper.selectById(nextMonth.monthlySnapshotId()).getBomBatchId()).isEqualTo("RAW-2026-09");
    verify(LIVE, times(1)).readLive(august);
    verify(LIVE, times(1)).readLive(september);
  }

  @Test
  void failedCostingTransactionDoesNotLeaveAnIndependentlyCommittedSnapshot() {
    assertThatThrownBy(() -> costing.executeWithoutResult(tx -> {
      gateway.read(context("2026-09"));
      throw new IllegalStateException("simulate BOM build failure");
    })).isInstanceOf(IllegalStateException.class);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot", Integer.class)).isZero();
  }

  @Test
  void concurrentFirstCostingsBothReadTheSingleCommittedMonthlySnapshot() throws Exception {
    var start = new CyclicBarrier(2);
    try (var pool = Executors.newFixedThreadPool(2)) {
      java.util.concurrent.Callable<Long> launch = () -> costing.execute(tx -> {
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot", Integer.class)).isZero();
        try {
          start.await(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
          throw new IllegalStateException(exception);
        }
        var result = gateway.read(context("2026-09"));
        var visible = mapper.selectCurrentById(result.monthlySnapshotId());
        assertThat(visible).as("并发复用不能把另一个请求刚提交的快照误报为缺失").isNotNull();
        assertThat(visible.getSyncStatus()).isEqualTo("SUCCESS");
        return visible.getId();
      });
      var first = pool.submit(launch);
      var second = pool.submit(launch);
      assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
    }
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot", Integer.class)).isOne();
    verify(LIVE, times(1)).readLive(any());
  }

  @AfterAll
  static void stop() {
    if (spring != null) spring.close();
    MYSQL.stop();
  }

  private static String resource(String path) throws Exception {
    return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
  }

  private static QuoteBomReadContext context(String month) {
    return new QuoteBomReadContext(22L, 282L, "OA-MONTHLY-TEST", month,
        "COMMERCIAL", "1053900000062", "板式热交换器", "规格", "型号", "220", "PLATE",
        LocalDate.parse(month + "-06"), LocalDateTime.parse(month + "-06T12:00:00"));
  }

  @Configuration
  @EnableTransactionManagement
  static class Transactions {}
}
