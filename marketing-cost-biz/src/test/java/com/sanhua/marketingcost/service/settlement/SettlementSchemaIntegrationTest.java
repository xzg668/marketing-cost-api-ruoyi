package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;

@Tag("integration")
class SettlementSchemaIntegrationTest {
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
      .withDatabaseName("settlement_schema").withUsername("root").withPassword("isolated-test-only");
  private static JdbcTemplate jdbc;

  @BeforeAll static void start() {
    MYSQL.start();
    jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
  }
  @AfterAll static void stop() { MYSQL.stop(); }

  @BeforeEach void clean() {
    new ResourceDatabasePopulator(new ClassPathResource("db/V332__settlement_price_versions.sql"))
        .execute(jdbc.getDataSource());
    jdbc.update("DELETE FROM lp_settlement_price");
    jdbc.execute("DROP TABLE IF EXISTS lp_settlement_base_price");
  }

  @Test void emptyDatabaseAndUpgradeAreRepeatableWithoutChangingExistingData() {
    var migration = new ClassPathResource("db/V332__settlement_price_versions.sql");
    assertThat(migration.exists()).as("settlement forward migration").isTrue();
    jdbc.execute("DROP TABLE lp_settlement_price");
    var runner = new ResourceDatabasePopulator(migration);
    runner.execute(jdbc.getDataSource());
    assertThat(jdbc.queryForList("SHOW TABLES")).hasSize(1);
    jdbc.execute("CREATE TABLE protected_prices(id BIGINT PRIMARY KEY, price DECIMAL(24,12), notes TEXT)");
    jdbc.update("INSERT INTO protected_prices VALUES(1, 4.841, 'existing source')");
    var before = jdbc.queryForList("SELECT * FROM protected_prices");
    var ddl = jdbc.queryForMap("SHOW CREATE TABLE protected_prices");
    runner.execute(jdbc.getDataSource());
    runner.execute(jdbc.getDataSource());
    assertThat(jdbc.queryForList("SHOW TABLES")).hasSize(2);
    assertThat(jdbc.queryForList("SELECT * FROM protected_prices")).isEqualTo(before);
    assertThat(jdbc.queryForMap("SHOW CREATE TABLE protected_prices")).isEqualTo(ddl);
    jdbc.execute("DROP TABLE protected_prices");
  }

  @Test void exactIdentityAllowsRevisionsAndBusinessUnitsButRejectsDuplicateRevision() {
    insert("COMMERCIAL", "2026-10", 1);
    insert("COMMERCIAL", "2026-10", 2);
    insert("HOUSEHOLD", "2026-10", 1);
    assertThatThrownBy(() -> insert("COMMERCIAL", "2026-10", 1))
        .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_settlement_price", Integer.class)).isEqualTo(3);
  }

  @Test void nullWeightsRemainMissingWhileExplicitConfirmedZerosAreAccepted() {
    insert("COMMERCIAL", "2026-10", 1);
    assertThat(jdbc.queryForMap("SELECT copper_weight_g,zinc_weight_g FROM lp_settlement_price"))
        .containsEntry("copper_weight_g", null).containsEntry("zinc_weight_g", null);
    assertThatThrownBy(() -> jdbc.update("UPDATE lp_settlement_price SET weights_confirmed=1"))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    jdbc.update("UPDATE lp_settlement_price SET copper_weight_g=0,zinc_weight_g=0,weights_confirmed=1,"
        + "weight_confirmation_note='finance confirmed zero',weights_confirmed_by='test',weights_confirmed_at=NOW()");
    assertThat(jdbc.queryForObject("SELECT weights_confirmed FROM lp_settlement_price", Integer.class)).isOne();
  }

  @Test void malformedMonthIdentityAndFormulaCannotBecomeEffectiveRows() {
    for (String month : List.of("2026-00", "2026-13", "2026-1"))
      assertThatThrownBy(() -> insert("COMMERCIAL", month, 1)).isInstanceOf(org.springframework.dao.DataAccessException.class);
    insert("COMMERCIAL", "2026-10", 1);
    for (String update : List.of("given_price=NULL", "given_price=-1", "revision_no=0", "active=2",
        "price_type='UNKNOWN'", "price_type='SETTLE_LINKED'", "source_rows_json='{}'", "material_code=''",
        "material_code='M1 '", "copper_weight_g=-1", "weights_confirmed=2"))
      assertThatThrownBy(() -> jdbc.update("UPDATE lp_settlement_price SET " + update), update)
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test void upgradePreservesLegacyBaseDataWithoutRecreatingOrUsingIt() {
    new ResourceDatabasePopulator(new ClassPathResource("settlement/legacy-base-schema.sql"))
        .execute(jdbc.getDataSource());
    jdbc.update("INSERT INTO lp_settlement_base_price(business_unit_type,price_month,revision_no,copper_price,zinc_price,"
        + "source_kind,change_reason,created_by) VALUES('COMMERCIAL','2026-10',1,104.526,24.51,'FINANCE','legacy','test')");
    var before = jdbc.queryForList("SELECT * FROM lp_settlement_base_price");
    var ddl = jdbc.queryForMap("SHOW CREATE TABLE lp_settlement_base_price");
    var migration = new ResourceDatabasePopulator(new ClassPathResource("db/V332__settlement_price_versions.sql"));
    migration.execute(jdbc.getDataSource());
    migration.execute(jdbc.getDataSource());
    assertThat(jdbc.queryForList("SELECT * FROM lp_settlement_base_price")).isEqualTo(before);
    assertThat(jdbc.queryForMap("SHOW CREATE TABLE lp_settlement_base_price")).isEqualTo(ddl);
  }

  @Test void effectiveMonthOrderingExcludesFutureAndOtherBusinessUnits() {
    insert("COMMERCIAL", "2026-09", 5);
    insert("COMMERCIAL", "2026-10", 1);
    insert("COMMERCIAL", "2026-10", 2);
    insert("COMMERCIAL", "2026-12", 1);
    insert("HOUSEHOLD", "2026-11", 1);
    var found = jdbc.queryForMap("SELECT price_month,revision_no FROM lp_settlement_price WHERE business_unit_type=? "
        + "AND material_code=? AND price_type=? AND active=1 AND price_month<=? ORDER BY price_month DESC,revision_no DESC LIMIT 1",
        "COMMERCIAL", "M1", "SETTLE_FIXED", "2026-11");
    assertThat(found).containsEntry("price_month", "2026-10").containsEntry("revision_no", 2);
  }

  @Test void simultaneousSameRevisionHasExactlyOneWinner() throws Exception {
    var start = new CountDownLatch(1);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var results = java.util.stream.IntStream.range(0, 2).mapToObj(i -> pool.submit(() -> {
        start.await();
        try { insert("COMMERCIAL", "2026-10", 1); return true; }
        catch (org.springframework.dao.DuplicateKeyException conflict) { return false; }
      })).toList();
      start.countDown();
      assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
    }
  }

  private void insert(String unit, String month, int revision) {
    jdbc.update("INSERT INTO lp_settlement_price(business_unit_type,material_code,material_name,price_type,price_month,"
        + "revision_no,given_price,source_system,source_rows_json,content_hash,created_by) VALUES(?, 'M1','part',"
        + "'SETTLE_FIXED',?,?,4.841,'EXCEL','[]',REPEAT('a',64),'test')", unit, month, revision);
  }
}
