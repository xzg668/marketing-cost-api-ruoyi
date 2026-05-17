package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@DisplayName("V86 报价单基价映射基础规则 seed 集成验证")
class V86QuoteBasePriceMappingSeedDdlTest {

  private static final DockerImageName MYSQL_IMAGE =
      DockerImageName.parse("mysql:8.4").asCompatibleSubstituteFor("mysql");

  @SuppressWarnings("resource")
  private static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>(MYSQL_IMAGE)
          .withDatabaseName("marketing_cost")
          .withUsername("root")
          .withPassword("root123")
          .withCommand("--sql-mode=NO_ENGINE_SUBSTITUTION", "--default-storage-engine=InnoDB",
              "--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci");

  @BeforeAll
  static void setUp() {
    MYSQL.start();
  }

  @AfterAll
  static void tearDown() {
    MYSQL.stop();
  }

  @Test
  @DisplayName("V86 重复执行后仍只有 Cu/Zn/Al 三条全局规则")
  void v86CanRunTwiceWithoutDuplicateSeedRows() throws Exception {
    runSql("/db/V85__quote_base_price_mapping_schema.sql", "v85_schema.sql");
    runSql("/db/V86__quote_base_price_mapping_seed.sql", "v86_first.sql");
    runSql("/db/V86__quote_base_price_mapping_seed.sql", "v86_second.sql");

    assertThat(count("SELECT COUNT(*) FROM lp_quote_base_price_mapping_rule")).isEqualTo(3);
    assertThat(count("SELECT COUNT(*) FROM lp_quote_base_price_mapping_rule "
        + "WHERE business_unit_type = '' AND enabled = 1 AND deleted = 0")).isEqualTo(3);
    assertThat(count("SELECT COUNT(*) FROM lp_quote_base_price_mapping_rule "
        + "WHERE quote_field_code IN ('copper_price', 'zinc_price', 'aluminum_price')"))
        .isEqualTo(3);
    assertThat(count("SELECT COUNT(*) FROM lp_quote_base_price_mapping_rule "
        + "WHERE quote_field_code = 'tin_price' OR variable_code = 'Sn'")).isZero();
  }

  private static void runSql(String classpathResource, String containerFileName) throws Exception {
    MYSQL.copyFileToContainer(
        MountableFile.forClasspathResource(classpathResource),
        "/tmp/" + containerFileName);
    var result = MYSQL.execInContainer(
        "sh", "-c",
        "mysql --default-character-set=utf8mb4 -uroot -p" + MYSQL.getPassword()
            + " " + MYSQL.getDatabaseName() + " < /tmp/" + containerFileName);
    assertThat(result.getExitCode())
        .as(containerFileName + " 执行失败 stderr=" + result.getStderr())
        .isZero();
  }

  private static int count(String sql) throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      assertThat(rs.next()).isTrue();
      return rs.getInt(1);
    }
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
  }
}
