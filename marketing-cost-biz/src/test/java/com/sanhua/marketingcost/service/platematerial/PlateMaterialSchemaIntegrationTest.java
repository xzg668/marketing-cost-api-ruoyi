package com.sanhua.marketingcost.service.platematerial;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.sql.SQLException;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
class PlateMaterialSchemaIntegrationTest {
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
      .withDatabaseName("plate_schema_test").withUsername("root").withPassword("test-only-password");
  static JdbcTemplate jdbc;
  static List<Map<String, Object>> oldRows;
  static List<Map<String, Object>> oldColumns;
  static String oldTrigger;

  @BeforeAll static void start() throws Exception {
    MYSQL.start();
    jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    // Migration compatibility fixture: old schemas and independent protected data exist before V335.
    jdbc.execute("""
        CREATE TABLE lp_quote_tech_data_version (
          id BIGINT PRIMARY KEY, version_status VARCHAR(20) NOT NULL,
          content_schema_version INT NOT NULL, original_value VARCHAR(80) NOT NULL,
          original_json JSON NULL)
        """);
    jdbc.execute("CREATE TABLE preserved_fixture(id INT PRIMARY KEY, value VARCHAR(20))");
    jdbc.update("INSERT INTO preserved_fixture VALUES (1,'protected')");
    jdbc.execute("""
        CREATE TRIGGER preserved_fixture_guard BEFORE UPDATE ON preserved_fixture
        FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PROTECTED_FIXTURE'
        """);
    jdbc.update("""
        INSERT INTO lp_quote_tech_data_version VALUES
          (1,'SUBMITTED',1,'旧版资料','{"existing":"one"}'),
          (2,'FROZEN',2,'原冻结资料','{"existing":"two"}')
        """);
    oldRows = jdbc.queryForList("SELECT * FROM lp_quote_tech_data_version ORDER BY id");
    oldColumns = jdbc.queryForList("""
        SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='lp_quote_tech_data_version' ORDER BY ORDINAL_POSITION
        """);
    oldTrigger = jdbc.queryForObject("SELECT ACTION_STATEMENT FROM information_schema.triggers "
        + "WHERE trigger_schema=DATABASE() AND TRIGGER_NAME='preserved_fixture_guard'", String.class);
    applyMigration();
  }

  @AfterAll static void stop() { MYSQL.stop(); }
  @BeforeEach void resetMappings() { jdbc.update("DELETE FROM lp_plate_material_mapping"); }

  @Test void migrationCanBeRepeatedWithoutChangingOldFieldsRowsOrTriggers() throws Exception {
    applyMigration();
    assertThat(jdbc.queryForList("SELECT id,version_status,content_schema_version,original_value,original_json "
        + "FROM lp_quote_tech_data_version ORDER BY id")).isEqualTo(oldRows);
    assertThat(jdbc.queryForList("""
        SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT FROM information_schema.columns
        WHERE table_schema=DATABASE() AND table_name='lp_quote_tech_data_version'
          AND COLUMN_NAME<>'plate_material_json' ORDER BY ORDINAL_POSITION
        """)).isEqualTo(oldColumns);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_quote_tech_data_version WHERE plate_material_json IS NULL", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class)).isEqualTo(3);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE()", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT ACTION_STATEMENT FROM information_schema.triggers "
        + "WHERE trigger_schema=DATABASE() AND TRIGGER_NAME='preserved_fixture_guard'", String.class)).isEqualTo(oldTrigger);
    assertThat(jdbc.queryForObject("SELECT value FROM preserved_fixture WHERE id=1", String.class)).isEqualTo("protected");
  }

  @Test void exactIdentityPreservesCaseInternalSpacesSuffixAndBusinessUnit() {
    for (String model : List.of("MODEL", "model", "MO DEL", "MODEL-L", "ＭＯＤＥＬ")) {
      insert("COMMERCIAL", model);
    }
    insert("HOUSEHOLD", "MODEL");
    assertThat(count()).isEqualTo(6);
    assertThatThrownBy(() -> insert("COMMERCIAL", "MODEL")).isInstanceOf(DataIntegrityViolationException.class);
    for (String model : List.of("", " ", " MODEL", "MODEL ")) {
      assertCheckRejected(() -> insert("COMMERCIAL", model));
    }
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_plate_material_mapping "
        + "WHERE business_unit_type='COMMERCIAL' AND normalized_model='MODEL'", Integer.class)).isOne();
  }

  @Test void sixMaterialsAndProvenanceRejectMalformedRows() {
    insert("COMMERCIAL", "M");
    for (String material : List.of("水氟SUS304","水氟316L","氟氟SUS304","氟氟316L","水水SUS304","水水316L")) {
      jdbc.update("UPDATE lp_plate_material_mapping SET plate_material=?", material);
    }
    for (String invalid : List.of("水水316", "水水sus304", "", "316L")) {
      assertCheckRejected(() -> jdbc.update("UPDATE lp_plate_material_mapping SET plate_material=?", invalid));
    }
    assertCheckRejected(() -> jdbc.update("UPDATE lp_plate_material_mapping SET source_type='EXCEL'"));
    assertCheckRejected(() -> jdbc.update("UPDATE lp_plate_material_mapping SET row_version=-1"));
  }

  @Test void frozenAndLegacySnapshotsCannotBePopulatedByOrdinarySql() {
    assertThatThrownBy(() -> jdbc.update("UPDATE lp_quote_tech_data_version SET plate_material_json='{}' WHERE id=2"))
        .hasMessageContaining("IMMUTABLE");
    assertCheckRejected(() -> jdbc.update("""
        INSERT INTO lp_quote_tech_data_version(id,version_status,content_schema_version,original_value,plate_material_json)
        VALUES(3,'DRAFT',1,'invalid legacy','{}')
        """));
  }

  @Test void concurrentDuplicateIdentityHasOneWinner() throws Exception {
    var start = new CountDownLatch(1);
    var first = CompletableFuture.supplyAsync(() -> concurrentInsert(start));
    var second = CompletableFuture.supplyAsync(() -> concurrentInsert(start));
    start.countDown();
    assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isOne();
    assertThat(count()).isOne();
  }

  private int concurrentInsert(CountDownLatch start) {
    try {
      if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrency test did not start");
      insert("COMMERCIAL", "CONCURRENT");
      return 1;
    } catch (DataIntegrityViolationException duplicate) {
      return 0;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private static void applyMigration() throws Exception {
    MYSQL.copyFileToContainer(MountableFile.forClasspathResource("db/V335__plate_material_mapping.sql"), "/tmp/plate.sql");
    var result = MYSQL.execInContainer("sh", "-c",
        "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 plate_schema_test < /tmp/plate.sql");
    assertThat(result.getExitCode()).as(result.getStderr()).isZero();
  }

  private void insert(String unit, String model) {
    jdbc.update("""
        INSERT INTO lp_plate_material_mapping(business_unit_type,normalized_model,plate_material,source_type,created_by,updated_by)
        VALUES (?,?,'水水316L','MANUAL',101,101)
        """, unit, model);
  }

  private long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM lp_plate_material_mapping", Long.class); }

  private void assertCheckRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
    assertThatThrownBy(action).isInstanceOfSatisfying(DataAccessException.class, error -> {
      assertThat(error.getMostSpecificCause()).isInstanceOfSatisfying(SQLException.class,
          sql -> assertThat(sql.getErrorCode()).isEqualTo(3819));
    });
  }
}
