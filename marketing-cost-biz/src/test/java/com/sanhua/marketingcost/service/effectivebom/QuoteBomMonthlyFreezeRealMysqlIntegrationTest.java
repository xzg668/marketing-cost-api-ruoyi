package com.sanhua.marketingcost.service.effectivebom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mysql.cj.jdbc.MysqlDataSource;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
@DisplayName("QEB-09 客户场景月度冻结真实MySQL事务")
class QuoteBomMonthlyFreezeRealMysqlIntegrationTest {

  private static final DockerImageName MYSQL_IMAGE =
      DockerImageName.parse("mysql:8.4").asCompatibleSubstituteFor("mysql");

  @SuppressWarnings("resource")
  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(MYSQL_IMAGE)
          .withDatabaseName("marketing_cost")
          .withUsername("root")
          .withPassword("root123")
          .withCommand(
              "--sql-mode=NO_ENGINE_SUBSTITUTION",
              "--default-storage-engine=InnoDB",
              "--character-set-server=utf8mb4",
              "--collation-server=utf8mb4_0900_ai_ci");

  private static DataSource dataSource;
  private static JdbcTemplate jdbcTemplate;
  private static JdbcEffectivePersistence persistence;
  private static QuoteBomMonthlyFreezeService service;

  @BeforeAll
  static void setUpDatabaseAndService() throws Exception {
    MYSQL.start();
    MysqlDataSource mysqlDataSource = new MysqlDataSource();
    mysqlDataSource.setUrl(MYSQL.getJdbcUrl());
    mysqlDataSource.setUser(MYSQL.getUsername());
    mysqlDataSource.setPassword(MYSQL.getPassword());
    dataSource = mysqlDataSource;
    jdbcTemplate = new JdbcTemplate(dataSource);
    createSchema();

    QuoteBomMonthlyFreezeRepository repository =
        new QuoteBomMonthlyFreezeRepositoryImpl(jdbcTemplate);
    persistence = new JdbcEffectivePersistence(jdbcTemplate);
    QuoteBomMonthlyFreezeServiceImpl target =
        new QuoteBomMonthlyFreezeServiceImpl(
            repository,
            persistence,
            Clock.fixed(
                Instant.parse("2026-08-04T03:00:00Z"), ZoneOffset.UTC));
    service = transactionalProxy(target, new DataSourceTransactionManager(dataSource));
  }

  @AfterAll
  static void tearDownDatabase() {
    MYSQL.stop();
  }

  @BeforeEach
  void resetTables() {
    jdbcTemplate.update("DELETE FROM qeb09_effective_build");
    jdbcTemplate.update("DELETE FROM lp_quote_bom_status");
    jdbcTemplate.update("DELETE FROM lp_quote_bom_monthly_snapshot");
    persistence.reset();
  }

  @AfterEach
  void removeFailureTriggers() {
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS qeb09_fail_snapshot_update");
    jdbcTemplate.execute("DROP TRIGGER IF EXISTS qeb09_fail_status_update");
  }

  @Test
  @DisplayName("首次冻结后同场景重试直接复用且不受月中新候选影响")
  void firstFreezeThenRetryReusesImmutableHistoricalBuild() {
    QuoteBomMonthlyFreezeKey key = key("2026-08", "CUSTOMER-A", "BOX", "210");
    seedCard(11L, 101L, 1001L, key);
    seedStatus(21L, 1002L, key);

    QuoteBomMonthlyFreezeResult first =
        service.freeze(command(key, 1001L, candidate("2026-08", "BOX", "210")));
    EffectiveBomVariantInput changedMidMonth =
        candidate("2026-09", "BAG", "220");
    QuoteBomMonthlyFreezeResult retry =
        service.freeze(command(key, 1002L, changedMidMonth));

    assertThat(first.reusedFrozenSnapshot()).isFalse();
    assertThat(retry.reusedFrozenSnapshot()).isTrue();
    assertThat(retry.buildBatchId()).isEqualTo(first.buildBatchId());
    assertThat(persistence.calls()).isEqualTo(1);
    assertThat(scalarInt("SELECT COUNT(*) FROM qeb09_effective_build")).isOne();
    assertThat(
            scalarString(
                "SELECT freeze_status FROM lp_quote_bom_monthly_snapshot WHERE id=11"))
        .isEqualTo("FROZEN");
    assertThat(
            scalarString(
                "SELECT costing_build_batch_id FROM lp_quote_bom_status WHERE oa_form_item_id=1002"))
        .isEqualTo(first.buildBatchId());
  }

  @Test
  @DisplayName("不同客户和包装分别冻结，相同结果跨客户共用一棵树")
  void customerAndPackageAreIsolatedWhileSameResultSharesBuild() {
    QuoteBomMonthlyFreezeKey customerA =
        key("2026-08", "CUSTOMER-A", "BOX", "210");
    QuoteBomMonthlyFreezeKey customerB =
        key("2026-08", "CUSTOMER-B", "BOX", "210");
    QuoteBomMonthlyFreezeKey bag =
        key("2026-08", "CUSTOMER-A", "BAG", "210");
    seedCard(11L, 101L, 1001L, customerA);
    seedCard(12L, 102L, 1002L, customerB);
    seedCard(13L, 103L, 1003L, bag);

    QuoteBomMonthlyFreezeResult resultA =
        service.freeze(command(customerA, 1001L, candidate("2026-08", "BOX", "210")));
    QuoteBomMonthlyFreezeResult resultB =
        service.freeze(command(customerB, 1002L, candidate("2026-08", "BOX", "210")));
    QuoteBomMonthlyFreezeResult resultBag =
        service.freeze(command(bag, 1003L, candidate("2026-08", "BAG", "210")));

    assertThat(resultB.reusedEffectiveBuild()).isTrue();
    assertThat(resultB.buildBatchId()).isEqualTo(resultA.buildBatchId());
    assertThat(resultBag.buildBatchId()).isNotEqualTo(resultA.buildBatchId());
    assertThat(scalarInt("SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot"))
        .isEqualTo(3);
    assertThat(scalarInt("SELECT COUNT(*) FROM qeb09_effective_build"))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("210和220同料号严格隔离")
  void priceOrganizationsDoNotCrossTrees() {
    QuoteBomMonthlyFreezeKey org210 =
        key("2026-08", "CUSTOMER-A", "BOX", "210");
    QuoteBomMonthlyFreezeKey org220 =
        key("2026-08", "CUSTOMER-A", "BOX", "220");
    seedCard(11L, 101L, 1001L, org210);
    seedCard(12L, 102L, 1002L, org220);

    QuoteBomMonthlyFreezeResult result210 =
        service.freeze(command(org210, 1001L, candidate("2026-08", "BOX", "210")));
    QuoteBomMonthlyFreezeResult result220 =
        service.freeze(command(org220, 1002L, candidate("2026-08", "BOX", "220")));

    assertThat(result220.buildBatchId()).isNotEqualTo(result210.buildBatchId());
    assertThat(scalarInt("SELECT COUNT(*) FROM qeb09_effective_build"))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("下一月重新冻结，不沿用上月结果")
  void nextMonthRecomputes() {
    QuoteBomMonthlyFreezeKey august =
        key("2026-08", "CUSTOMER-A", "BOX", "210");
    QuoteBomMonthlyFreezeKey september =
        key("2026-09", "CUSTOMER-A", "BOX", "210");
    seedCard(11L, 101L, 1001L, august);
    seedCard(12L, 102L, 1002L, september);

    QuoteBomMonthlyFreezeResult augustResult =
        service.freeze(command(august, 1001L, candidate("2026-08", "BOX", "210")));
    QuoteBomMonthlyFreezeResult septemberResult =
        service.freeze(
            command(september, 1002L, candidate("2026-09", "BOX", "210")));

    assertThat(septemberResult.buildBatchId())
        .isNotEqualTo(augustResult.buildBatchId());
    assertThat(persistence.calls()).isEqualTo(2);
  }

  @Test
  @DisplayName("QEB-15 OA有100个产品时只冻结当前1个，其余99个保持原状态")
  void oneHundredProductOaFreezesOnlyTheRequestedProduct() {
    long targetItemId = 1042L;
    QuoteBomMonthlyFreezeKey targetKey = null;
    for (int index = 1; index <= 100; index++) {
      String productCode = "P-" + String.format("%03d", index);
      QuoteBomMonthlyFreezeKey key =
          key("2026-08", productCode, "CUSTOMER-A", "BOX", "210");
      seedCard(1000L + index, 2000L + index, 1000L + index, key);
      if (1000L + index == targetItemId) {
        targetKey = key;
      }
    }

    assertThat(targetKey).isNotNull();
    QuoteBomMonthlyFreezeResult result =
        service.freeze(
            command(
                targetKey,
                targetItemId,
                candidate("2026-08", "P-042", "BOX", "210")));

    assertThat(result.reusedFrozenSnapshot()).isFalse();
    assertThat(scalarInt(
            "SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot WHERE freeze_status='FROZEN'"))
        .isOne();
    assertThat(scalarInt(
            "SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot WHERE freeze_status='DRAFT'"))
        .isEqualTo(99);
    assertThat(scalarInt(
            "SELECT COUNT(*) FROM lp_quote_bom_status WHERE costing_build_batch_id IS NOT NULL"))
        .isOne();
    assertThat(scalarInt(
            "SELECT COUNT(*) FROM lp_quote_bom_status WHERE costing_build_batch_id IS NULL"))
        .isEqualTo(99);
    assertThat(
            scalarString(
                "SELECT product_code FROM lp_quote_bom_monthly_snapshot "
                    + "WHERE freeze_status='FROZEN'"))
        .isEqualTo("P-042");
    assertThat(
            scalarString(
                "SELECT costing_build_batch_id FROM lp_quote_bom_status "
                    + "WHERE oa_form_item_id=1042"))
        .isEqualTo(result.buildBatchId());
    assertThat(scalarInt("SELECT COUNT(*) FROM qeb09_effective_build")).isOne();
  }

  @Test
  @DisplayName("QEB-15 90个客户选标准、10个客户选替代时100张关系只共用两棵树")
  void oneHundredCustomerScenariosShareExactlyTwoBuilds() {
    for (int index = 1; index <= 100; index++) {
      String customerCode = "CUSTOMER-" + String.format("%03d", index);
      QuoteBomMonthlyFreezeKey key =
          key("2026-08", "P", customerCode, "BOX", "210");
      long rowId = 3000L + index;
      seedCard(rowId, 4000L + index, 5000L + index, key);
      EffectiveBomVariantInput variant =
          index <= 90
              ? candidate("2026-08", "P", "BOX", "210")
              : alternativeCandidate("2026-08", "BOX", "210");
      service.freeze(command(key, 5000L + index, variant));
    }

    assertThat(scalarInt(
            "SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot WHERE freeze_status='FROZEN'"))
        .isEqualTo(100);
    assertThat(scalarInt(
            "SELECT COUNT(*) FROM lp_quote_bom_status WHERE costing_build_batch_id IS NOT NULL"))
        .isEqualTo(100);
    assertThat(scalarInt("SELECT COUNT(*) FROM qeb09_effective_build")).isEqualTo(2);
    assertThat(
            jdbcTemplate.query(
                "SELECT COUNT(*) FROM lp_quote_bom_monthly_snapshot "
                    + "GROUP BY effective_build_batch_id ORDER BY COUNT(*) DESC",
                (resultSet, rowNumber) -> resultSet.getInt(1)))
        .containsExactly(90, 10);
    assertThat(
            jdbcTemplate.query(
                "SELECT node_count FROM qeb09_effective_build ORDER BY build_batch_id",
                (resultSet, rowNumber) -> resultSet.getInt(1)))
        .containsOnly(2);
    assertThat(persistence.calls()).isEqualTo(100);
  }

  @Test
  @DisplayName("20个并发首次确认只有一个请求保存并冻结")
  void twentyConcurrentFirstFreezesProduceOneResult() throws Exception {
    QuoteBomMonthlyFreezeKey key =
        key("2026-08", "CUSTOMER-A", "BOX", "210");
    seedCard(11L, 101L, 1001L, key);
    int concurrency = 20;
    ExecutorService executor = Executors.newFixedThreadPool(concurrency);
    CountDownLatch ready = new CountDownLatch(concurrency);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<QuoteBomMonthlyFreezeResult>> futures = new ArrayList<>();
    try {
      for (int index = 0; index < concurrency; index++) {
        futures.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                  return service.freeze(
                      command(key, 1001L, candidate("2026-08", "BOX", "210")));
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      List<QuoteBomMonthlyFreezeResult> results = new ArrayList<>();
      for (Future<QuoteBomMonthlyFreezeResult> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }

      assertThat(results)
          .extracting(QuoteBomMonthlyFreezeResult::buildBatchId)
          .containsOnly(results.getFirst().buildBatchId());
      assertThat(results)
          .filteredOn(result -> !result.reusedFrozenSnapshot())
          .hasSize(1);
      assertThat(persistence.calls()).isEqualTo(1);
      assertThat(scalarInt("SELECT COUNT(*) FROM qeb09_effective_build"))
          .isOne();
      assertThat(
              scalarString(
                  "SELECT freeze_status FROM lp_quote_bom_monthly_snapshot WHERE id=11"))
          .isEqualTo("FROZEN");
    } finally {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  @DisplayName("节点保存中途失败时节点、卡片和OA状态全部回滚")
  void nodePersistenceFailureRollsEverythingBack() {
    QuoteBomMonthlyFreezeKey key =
        key("2026-08", "CUSTOMER-A", "BOX", "210");
    seedCard(11L, 101L, 1001L, key);
    persistence.failAfterInsertOnce();

    assertThatThrownBy(
            () ->
                service.freeze(
                    command(key, 1001L, candidate("2026-08", "BOX", "210"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("模拟节点保存失败");

    assertDraftAndUnbound(11L, 1001L);
    assertThat(scalarInt("SELECT COUNT(*) FROM qeb09_effective_build"))
        .isZero();
  }

  @Test
  @DisplayName("月度卡片更新失败时已插入节点一并回滚")
  void snapshotUpdateFailureRollsBackPersistedBuild() {
    QuoteBomMonthlyFreezeKey key =
        key("2026-08", "CUSTOMER-A", "BOX", "210");
    seedCard(11L, 101L, 1001L, key);
    jdbcTemplate.execute(
        """
        CREATE TRIGGER qeb09_fail_snapshot_update
        BEFORE UPDATE ON lp_quote_bom_monthly_snapshot
        FOR EACH ROW
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='模拟卡片更新失败'
        """);

    assertThatThrownBy(
            () ->
                service.freeze(
                    command(key, 1001L, candidate("2026-08", "BOX", "210"))))
        .isInstanceOf(RuntimeException.class);

    assertDraftAndUnbound(11L, 1001L);
    assertThat(scalarInt("SELECT COUNT(*) FROM qeb09_effective_build"))
        .isZero();
  }

  @Test
  @DisplayName("OA状态更新失败时节点和已冻结卡片全部回滚")
  void statusUpdateFailureRollsBackBuildAndFrozenCard() {
    QuoteBomMonthlyFreezeKey key =
        key("2026-08", "CUSTOMER-A", "BOX", "210");
    seedCard(11L, 101L, 1001L, key);
    jdbcTemplate.execute(
        """
        CREATE TRIGGER qeb09_fail_status_update
        BEFORE UPDATE ON lp_quote_bom_status
        FOR EACH ROW
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='模拟OA状态更新失败'
        """);

    assertThatThrownBy(
            () ->
                service.freeze(
                    command(key, 1001L, candidate("2026-08", "BOX", "210"))))
        .isInstanceOf(RuntimeException.class);

    assertDraftAndUnbound(11L, 1001L);
    assertThat(scalarInt("SELECT COUNT(*) FROM qeb09_effective_build"))
        .isZero();
  }

  private static QuoteBomMonthlyFreezeService transactionalProxy(
      QuoteBomMonthlyFreezeServiceImpl target,
      PlatformTransactionManager transactionManager) {
    TransactionInterceptor interceptor =
        new TransactionInterceptor(
            transactionManager, new AnnotationTransactionAttributeSource());
    ProxyFactory factory = new ProxyFactory(target);
    factory.addAdvice(interceptor);
    return (QuoteBomMonthlyFreezeService) factory.getProxy();
  }

  private static void createSchema() throws Exception {
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          CREATE TABLE lp_quote_bom_monthly_snapshot (
            id BIGINT NOT NULL PRIMARY KEY,
            product_code VARCHAR(64) NOT NULL,
            price_org_code VARCHAR(32) NOT NULL,
            customer_code VARCHAR(128) NOT NULL DEFAULT '',
            package_method VARCHAR(128) NOT NULL DEFAULT '',
            cost_period_month CHAR(7) NOT NULL,
            sync_status VARCHAR(16) NOT NULL,
            sync_at DATETIME NOT NULL,
            source_oa_form_item_id BIGINT NULL,
            bom_batch_id VARCHAR(128) NULL,
            active_flag TINYINT NOT NULL,
            freeze_status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
            effective_build_batch_id VARCHAR(64) NULL,
            effective_variant_hash CHAR(64) NULL,
            frozen_at DATETIME NULL,
            frozen_by BIGINT NULL,
            created_at DATETIME NOT NULL,
            updated_at DATETIME NOT NULL,
            KEY idx_qeb09_monthly_key (
              cost_period_month, product_code, customer_code,
              package_method, price_org_code, active_flag)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
      statement.execute(
          """
          CREATE TABLE lp_quote_bom_status (
            id BIGINT NOT NULL PRIMARY KEY,
            oa_form_item_id BIGINT NOT NULL,
            product_code VARCHAR(64) NOT NULL,
            customer_code VARCHAR(128) NOT NULL DEFAULT '',
            package_method VARCHAR(128) NOT NULL DEFAULT '',
            cost_period_month CHAR(7) NOT NULL,
            sync_record_id BIGINT NULL,
            costing_build_batch_id VARCHAR(64) NULL,
            created_at DATETIME NOT NULL,
            updated_at DATETIME NOT NULL,
            UNIQUE KEY uk_qeb09_oa_item (oa_form_item_id)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
      statement.execute(
          """
          CREATE TABLE qeb09_effective_build (
            build_batch_id VARCHAR(64) NOT NULL PRIMARY KEY,
            variant_hash CHAR(64) NOT NULL,
            origin_snapshot_id BIGINT NOT NULL,
            node_count INT NOT NULL,
            UNIQUE KEY uk_qeb09_variant_hash (variant_hash)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
    }
  }

  private void seedCard(
      long snapshotId,
      long statusId,
      long oaFormItemId,
      QuoteBomMonthlyFreezeKey key) {
    jdbcTemplate.update(
        """
        INSERT INTO lp_quote_bom_monthly_snapshot
          (id,product_code,price_org_code,customer_code,package_method,
           cost_period_month,sync_status,sync_at,source_oa_form_item_id,
           bom_batch_id,active_flag,freeze_status,created_at,updated_at)
        VALUES (?,?,?,?,?,?,'SUCCESS','2026-08-04 10:00:00',?,
                'RAW-BATCH-1',1,'DRAFT','2026-08-04 10:00:00',
                '2026-08-04 10:00:00')
        """,
        snapshotId,
        key.productCode(),
        key.priceOrgCode(),
        key.resolvedCustomerKey(),
        key.packageMethod(),
        key.costPeriodMonth(),
        oaFormItemId);
    seedStatus(statusId, oaFormItemId, key);
  }

  private void seedStatus(
      long statusId, long oaFormItemId, QuoteBomMonthlyFreezeKey key) {
    jdbcTemplate.update(
        """
        INSERT INTO lp_quote_bom_status
          (id,oa_form_item_id,product_code,customer_code,package_method,
           cost_period_month,created_at,updated_at)
        VALUES (?,?,?,?,?,?,'2026-08-04 10:00:00','2026-08-04 10:00:00')
        """,
        statusId,
        oaFormItemId,
        key.productCode(),
        key.resolvedCustomerKey(),
        key.packageMethod(),
        key.costPeriodMonth());
  }

  private QuoteBomMonthlyFreezeCommand command(
      QuoteBomMonthlyFreezeKey key,
      long oaFormItemId,
      EffectiveBomVariantInput candidate) {
    return new QuoteBomMonthlyFreezeCommand(
        key, oaFormItemId, 9527L, Map.of("ALT-GROUP-1", 81L), candidate);
  }

  private QuoteBomMonthlyFreezeKey key(
      String month, String customer, String packageMethod, String org) {
    return key(month, "P", customer, packageMethod, org);
  }

  private QuoteBomMonthlyFreezeKey key(
      String month,
      String productCode,
      String customer,
      String packageMethod,
      String org) {
    return new QuoteBomMonthlyFreezeKey(
        month, productCode, customer, packageMethod, org);
  }

  private EffectiveBomVariantInput candidate(
      String month, String packageMethod, String org) {
    return candidate(month, "P", packageMethod, org);
  }

  private EffectiveBomVariantInput candidate(
      String month, String productCode, String packageMethod, String org) {
    EffectiveBomVariantInput source = EffectiveBomPersistenceTestSupport.variant();
    return new EffectiveBomVariantInput(
        month,
        "RAW-BATCH-1",
        org,
        productCode,
        packageMethod,
        source.selectedMaterialCodeByGroupKey(),
        source.buildResult());
  }

  private EffectiveBomVariantInput alternativeCandidate(
      String month, String packageMethod, String org) {
    EffectiveBomVariantInput source =
        EffectiveBomPersistenceTestSupport.variant(
            "ALT-MATERIAL",
            packageMethod,
            new BigDecimal("2.500"),
            QuoteMaterialShape.OUTSOURCE,
            "POLICY-FP-1",
            "SUP-EXT",
            new BigDecimal("0.6000"),
            false);
    return new EffectiveBomVariantInput(
        month,
        "RAW-BATCH-1",
        org,
        "P",
        packageMethod,
        source.selectedMaterialCodeByGroupKey(),
        source.buildResult());
  }

  private void assertDraftAndUnbound(long snapshotId, long oaFormItemId) {
    assertThat(
            scalarString(
                "SELECT freeze_status FROM lp_quote_bom_monthly_snapshot WHERE id="
                    + snapshotId))
        .isEqualTo("DRAFT");
    assertThat(
            scalarString(
                "SELECT effective_build_batch_id FROM lp_quote_bom_monthly_snapshot WHERE id="
                    + snapshotId))
        .isNull();
    assertThat(
            scalarString(
                "SELECT costing_build_batch_id FROM lp_quote_bom_status WHERE oa_form_item_id="
                    + oaFormItemId))
        .isNull();
  }

  private int scalarInt(String sql) {
    Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
    return value == null ? 0 : value;
  }

  private String scalarString(String sql) {
    return jdbcTemplate.query(
        sql, result -> result.next() ? result.getString(1) : null);
  }

  private static final class JdbcEffectivePersistence
      implements QuoteEffectiveBomPersistenceService {

    private final JdbcTemplate jdbcTemplate;
    private final EffectiveBomVariantHasher hasher =
        EffectiveBomPersistenceTestSupport.hasher();
    private final AtomicInteger buildSequence = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicBoolean failAfterInsert = new AtomicBoolean();

    private JdbcEffectivePersistence(JdbcTemplate jdbcTemplate) {
      this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public QuoteEffectiveBomPersistenceResult persistConfirmed(
        QuoteEffectiveBomPersistenceRequest request) {
      calls.incrementAndGet();
      String hash = hasher.hash(request.variantInput());
      List<String> existing =
          jdbcTemplate.query(
              "SELECT build_batch_id FROM qeb09_effective_build WHERE variant_hash=?",
              (result, rowNum) -> result.getString(1),
              hash);
      if (!existing.isEmpty()) {
        return new QuoteEffectiveBomPersistenceResult(
            existing.getFirst(),
            hash,
            true,
            request.variantInput().buildResult().nodes().size());
      }
      String buildBatchId = "QEB09-BUILD-" + buildSequence.incrementAndGet();
      int nodeCount = request.variantInput().buildResult().nodes().size();
      jdbcTemplate.update(
          "INSERT INTO qeb09_effective_build "
              + "(build_batch_id,variant_hash,origin_snapshot_id,node_count) "
              + "VALUES (?,?,?,?)",
          buildBatchId,
          hash,
          request.originMonthlySnapshotId(),
          nodeCount);
      if (failAfterInsert.compareAndSet(true, false)) {
        throw new IllegalStateException("模拟节点保存失败");
      }
      return new QuoteEffectiveBomPersistenceResult(
          buildBatchId, hash, false, nodeCount);
    }

    private void failAfterInsertOnce() {
      failAfterInsert.set(true);
    }

    private int calls() {
      return calls.get();
    }

    private void reset() {
      calls.set(0);
      failAfterInsert.set(false);
    }
  }
}
