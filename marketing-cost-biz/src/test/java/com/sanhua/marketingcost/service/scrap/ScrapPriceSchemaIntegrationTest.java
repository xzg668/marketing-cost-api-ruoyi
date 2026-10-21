package com.sanhua.marketingcost.service.scrap;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.service.pricing.ScrapPriceIdentity;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
class ScrapPriceSchemaIntegrationTest {
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.4")
          .withDatabaseName("scrap_schema_test")
          .withUsername("root")
          .withPassword("test-only-password");
  static JdbcTemplate jdbc;
  static TransactionTemplate transaction;

  @BeforeAll
  static void start() throws Exception {
    MYSQL.start();
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource("db/V330__scrap_price_monthly.sql"), "/tmp/scrap.sql");
    var migration =
        MYSQL.execInContainer(
            "sh",
            "-c",
            "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot scrap_schema_test < /tmp/scrap.sql");
    assertThat(migration.getExitCode()).as(migration.getStderr()).isZero();
    var data =
        new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(data);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(data));
  }

  @AfterAll
  static void stop() {
    MYSQL.stop();
  }

  @BeforeEach
  void clear() {
    jdbc.update("DELETE FROM lp_scrap_price WHERE source_rule_id IS NOT NULL");
    jdbc.update("DELETE FROM lp_scrap_price");
  }

  @Test
  void addsOnlyOneTableAndHasNoConsumerOrganizationColumn() {
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                    + " table_name='lp_scrap_price' AND column_name IN"
                    + " ('org_code','price_org_code')",
                Integer.class))
        .isZero();
  }

  @Test
  void nullAndBlankSuppliersCannotCreateDuplicateRows() {
    insert("2026-10", "A", null, null, "2.6283185840708");
    assertThatThrownBy(() -> insert("2026-10", "A", "", "  ", "3"))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(jdbc.queryForObject("SELECT supplier_key FROM lp_scrap_price", String.class))
        .isEqualTo(new ScrapPriceIdentity("COMMERCIAL", "2026-10", "A", null, null).supplierKey());
    assertThat(count()).isEqualTo(1);
  }

  @Test
  void differentMonthsAndSupplierIdentitiesRemainIndependent() {
    insert("2026-10", "A", null, null, "1");
    insert("2026-11", "A", null, null, "2");
    insert("2026-10", "A", "S1", "名称", "3");
    insert("2026-10", "A", null, "S1", "4");
    insert("2026-10", "A", "s1", null, "5");
    assertThat(count()).isEqualTo(5);
    assertThatThrownBy(() -> insert("2026-10", "A", " S1 ", "新名称", "6"))
        .isInstanceOf(DataIntegrityViolationException.class);
    String expected =
        new ScrapPriceIdentity("COMMERCIAL", "2026-10", "A", null, "S1").supplierKey();
    assertThat(
            jdbc.queryForObject(
                "SELECT supplier_key FROM lp_scrap_price WHERE supplier_code IS NULL AND"
                    + " supplier_name='S1'",
                String.class))
        .isEqualTo(expected);
  }

  @Test
  void sameMonthCanChangeTypeWithoutChangingIdentityOrLosingPrecision() {
    insert("2026-10", "A", null, null, "2.6283185840708");
    Long id = jdbc.queryForObject("SELECT id FROM lp_scrap_price", Long.class);
    assertThat(jdbc.queryForObject("SELECT fixed_price FROM lp_scrap_price", BigDecimal.class))
        .isEqualByComparingTo("2.6283185840708");
    jdbc.update(
        "UPDATE lp_scrap_price SET price_type='LINKED', fixed_price=NULL,"
            + " expression='[Cu]*0.982/1.13', source_formula='[Cu]*0.982/1.13',"
            + " factor_bindings_json='{\"Cu\":\"AVG|1#CU\"}', unit_price=NULL,"
            + " calc_status='WAITING_BASE', rule_revision=rule_revision+1,"
            + " row_version=row_version+1 WHERE id=?",
        id);
    assertThat(count()).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT id FROM lp_scrap_price", Long.class)).isEqualTo(id);
    assertThat(jdbc.queryForObject("SELECT rule_revision FROM lp_scrap_price", Integer.class))
        .isEqualTo(2);
  }

  @Test
  void malformedRulesAndStaleResultsCannotPassDatabaseChecks() {
    insert("2026-10", "A", null, null, "1");
    for (String assignment :
        List.of(
            "price_month='2026-13',rule_source_month='2026-13'",
            "price_type='OTHER'",
            "price_type='LINKED',fixed_price=NULL,expression=NULL",
            "fixed_price=NULL",
            "calc_status='WAITING_BASE'",
            "unit_price=NULL",
            "tax_included=1",
            "finance_org_code='220'",
            "factor_bindings_json='not-json'",
            "row_version=0")) {
      assertCheckRejected("UPDATE lp_scrap_price SET " + assignment);
    }
  }

  @Test
  void carryForwardNeedsAnEarlierRealSourceAndKeepsOwnMonth() {
    insert("2026-10", "A", null, null, "1");
    Long source = jdbc.queryForObject("SELECT id FROM lp_scrap_price", Long.class);
    insert("2026-11", "A", null, null, "1");
    jdbc.update(
        "UPDATE lp_scrap_price SET origin_type='CARRY_FORWARD', rule_source_month='2026-10',"
            + " source_rule_id=?, source_rule_revision=1 WHERE price_month='2026-11'",
        source);
    assertThat(
            jdbc.queryForObject(
                "SELECT rule_source_month FROM lp_scrap_price WHERE price_month='2026-11'",
                String.class))
        .isEqualTo("2026-10");
    assertCheckRejected(
        "UPDATE lp_scrap_price SET source_rule_revision=NULL WHERE price_month='2026-11'");
    assertCheckRejected(
        "UPDATE lp_scrap_price SET rule_source_month='2026-12' WHERE price_month='2026-11'");
  }

  @Test
  void transactionRollbackLeavesNoPartialImport() {
    transaction.executeWithoutResult(
        status -> {
          insert("2026-10", "A", null, null, "1");
          insert("2026-10", "B", null, null, "2");
          status.setRollbackOnly();
        });
    assertThat(count()).isZero();
  }

  @Test
  void concurrentSameIdentityHasExactlyOneWinner() throws Exception {
    var start = new CountDownLatch(1);
    var one = CompletableFuture.supplyAsync(() -> concurrentInsert(start));
    var two = CompletableFuture.supplyAsync(() -> concurrentInsert(start));
    start.countDown();
    assertThat(one.get(10, TimeUnit.SECONDS) + two.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    assertThat(count()).isEqualTo(1);
  }

  @Test
  void expectedVersionUpdateCannotOverwriteANewerEdit() {
    insert("2026-10", "A", null, null, "1");
    String update =
        "UPDATE lp_scrap_price SET fixed_price=?,unit_price=?,row_version=row_version+1 WHERE"
            + " material_code='A' AND row_version=1";
    assertThat(jdbc.update(update, new BigDecimal("2"), new BigDecimal("2"))).isEqualTo(1);
    assertThat(jdbc.update(update, new BigDecimal("3"), new BigDecimal("3"))).isZero();
    assertThat(jdbc.queryForObject("SELECT fixed_price FROM lp_scrap_price", BigDecimal.class))
        .isEqualByComparingTo("2");
  }

  private int concurrentInsert(CountDownLatch start) {
    try {
      if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发测试未启动");
      insert("2026-10", "A", null, null, "1");
      return 1;
    } catch (DataIntegrityViolationException duplicate) {
      return 0;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  static void insert(String month, String code, String supplier, String name, String price) {
    jdbc.update(
        """
INSERT INTO lp_scrap_price(business_unit_type,price_month,material_code,material_name,
  supplier_code,supplier_name,price_type,fixed_price,factor_bindings_json,origin_type,
  rule_source_month,content_hash,unit_price,calc_status,created_by,created_at,updated_by,updated_at)
VALUES ('COMMERCIAL',?,?,?, ?,?,'FIXED',?,'{}','IMPORT',?, ?,?,'READY','test',NOW(6),'test',NOW(6))
""",
        month,
        code,
        code,
        supplier,
        name,
        new BigDecimal(price),
        month,
        "a".repeat(64),
        new BigDecimal(price));
  }

  private long count() {
    return jdbc.queryForObject("SELECT COUNT(*) FROM lp_scrap_price", Long.class);
  }

  private void assertCheckRejected(String statement) {
    assertThatThrownBy(() -> jdbc.update(statement))
        .as(statement)
        .isInstanceOf(DataAccessException.class)
        .satisfies(
            error -> {
              Throwable cause = ((DataAccessException) error).getMostSpecificCause();
              assertThat(cause).isInstanceOf(SQLException.class);
              assertThat(((SQLException) cause).getErrorCode()).isEqualTo(3819);
            });
  }
}
