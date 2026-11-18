package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/** Production matching expressions against the deployed MySQL version, including legacy charset. */
@Tag("integration")
class PriceModelSearchMysqlTest {
  static MySQLContainer<?> mysql;
  static JdbcTemplate jdbc;
  @BeforeAll static void start() {
    mysql=new MySQLContainer<>("mysql:8.0.36");mysql.start();
    jdbc=new JdbcTemplate(new DriverManagerDataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword()));
    jdbc.execute("CREATE TABLE price_rows(id INT PRIMARY KEY,material_code VARCHAR(64),org_code VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,spec_model VARCHAR(255),amount DECIMAL(18,8)) DEFAULT CHARSET=utf8mb3");
    jdbc.execute("CREATE TABLE lp_material_master_raw(material_code VARCHAR(64),organization_code VARCHAR(10),material_model VARCHAR(255),material_spec VARCHAR(255),drawing_no VARCHAR(255),active_flag INT) DEFAULT CHARSET=utf8mb4");
    jdbc.update("INSERT INTO price_rows VALUES(1,'001','210','RFK-01',0),(2,'002','210','rfk-02',12.34567891),(3,'003','220','100%_A',1),(4,'004','210',NULL,2)");
    jdbc.update("INSERT INTO lp_material_master_raw VALUES('004','210','MODEL-A',NULL,NULL,1),('004','220','OTHER',NULL,NULL,1),('001','210','INACTIVE',NULL,NULL,0)");
  }
  @AfterAll static void stop() { if(mysql!=null)mysql.stop(); }
  List<Integer> ids(String key) {
    return jdbc.queryForList("SELECT id FROM price_rows WHERE "+PriceModelSearch.condition("?","spec_model")+" ORDER BY id",Integer.class,PriceModelSearch.normalize(key));
  }
  @Test void partialCaseAndWhitespaceDoNotChangeAmountsOrNullRows() {
    var before=jdbc.queryForList("SELECT * FROM price_rows ORDER BY id");
    assertThat(ids(" RFk ")).containsExactly(1,2);
    assertThat(ids("unknown")).isEmpty();
    assertThat(ids("100%")).containsExactly(3);
    assertThat(ids("_")).containsExactly(3);
    assertThat(ids("' OR 1=1 --")).isEmpty();
    assertThat(jdbc.queryForList("SELECT * FROM price_rows ORDER BY id")).isEqualTo(before);
  }
  @Test void countsAndPaginationUseTheSameCondition() {
    String where=PriceModelSearch.condition("?","spec_model");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_rows WHERE "+where,Integer.class,"rfk")).isEqualTo(2);
    assertThat(jdbc.queryForList("SELECT id FROM price_rows WHERE "+where+" ORDER BY id LIMIT 1 OFFSET 1",Integer.class,"rfk")).containsExactly(2);
  }
  @Test void masterMatchingRequiresSameOrganizationAndActiveRowAcrossCharsets() {
    String where=PriceModelSearch.masterCondition("price_rows.material_code","price_rows.org_code","?");
    assertThat(jdbc.queryForList("SELECT id FROM price_rows WHERE "+where,Integer.class,"model-a","model-a","model-a")).containsExactly(4);
    assertThat(jdbc.queryForList("SELECT id FROM price_rows WHERE "+where,Integer.class,"other","other","other")).isEmpty();
    assertThat(jdbc.queryForList("SELECT id FROM price_rows WHERE "+where,Integer.class,"inactive","inactive","inactive")).isEmpty();
  }
  @Test void emptyAndOverlongInputsHaveExplicitSemantics() {
    assertThat(PriceModelSearch.normalize("  ")).isEmpty();
    assertThat(PriceModelSearch.matches(null,null)).isTrue();
    assertThat(PriceModelSearch.matches("rfk",null)).isFalse();
    assertThatThrownBy(()->PriceModelSearch.normalize("x".repeat(256))).isInstanceOf(IllegalArgumentException.class);
  }
}
