package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.sanhua.marketingcost.mapper.MaterialPriceMonthMapper;
import com.sanhua.marketingcost.mapper.TechnicalPriceSourceMapper;
import com.sanhua.marketingcost.service.pricing.MaterialPriceMonthSelector;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/** 生产选月和候选 Mapper 的真实 MySQL 边界；不连接业务库。 */
@Tag("integration")
class MaterialPriceSourceScopeIntegrationTest {
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
      .withDatabaseName("priority_sources").withUsername("root").withPassword("test-only-password");
  private static JdbcTemplate jdbc;
  private static TechnicalPriceSourceMapper sources;
  private static MaterialPriceMonthSelector months;
  private final LocalDate date = LocalDate.of(2026, 10, 14);

  @BeforeAll
  static void start() {
    MYSQL.start();
    var data = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(data);
    jdbc.execute("""
        CREATE TABLE lp_price_linked_item (
          id BIGINT AUTO_INCREMENT PRIMARY KEY,material_code VARCHAR(64),business_unit_type VARCHAR(32),
          org_code VARCHAR(32),pricing_month CHAR(7),source_kind VARCHAR(32) DEFAULT 'PUBLIC',
          deleted TINYINT DEFAULT 0,rule_status VARCHAR(32) DEFAULT 'PUBLISHED',
          INDEX idx_linked_scope_month(business_unit_type,org_code,pricing_month,source_kind,deleted,rule_status))
        """);
    jdbc.execute("""
        CREATE TABLE lp_price_range_factor_rule (
          id BIGINT AUTO_INCREMENT PRIMARY KEY,material_code VARCHAR(64),business_unit_type VARCHAR(32),
          org_code VARCHAR(32),pricing_month CHAR(7),rule_status VARCHAR(32) DEFAULT 'PUBLISHED',
          INDEX idx_range_scope_month(business_unit_type,org_code,pricing_month,rule_status))
        """);
    jdbc.execute("""
        CREATE TABLE lp_price_range_item (
          id BIGINT AUTO_INCREMENT PRIMARY KEY,material_code VARCHAR(64),business_unit_type VARCHAR(32),
          org_code VARCHAR(32),range_basis VARCHAR(32),effective_from DATE)
        """);
    jdbc.execute("""
        CREATE TABLE lp_price_fixed_item (
          id BIGINT AUTO_INCREMENT PRIMARY KEY,material_code VARCHAR(64),business_unit_type VARCHAR(32),
          org_code VARCHAR(32),source_kind VARCHAR(32) DEFAULT 'PUBLIC',source_type VARCHAR(32),
          pricing_month CHAR(7),effective_from DATE,effective_to DATE)
        """);
    var config = new MybatisConfiguration();
    config.setEnvironment(new Environment("sources", new SpringManagedTransactionFactory(), data));
    config.addMapper(TechnicalPriceSourceMapper.class);
    config.addMapper(MaterialPriceMonthMapper.class);
    var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
    sources = session.getMapper(TechnicalPriceSourceMapper.class);
    months = new MaterialPriceMonthSelector(session.getMapper(MaterialPriceMonthMapper.class));
  }

  @AfterAll static void stop() { MYSQL.stop(); }

  @BeforeEach
  void clear() {
    for (String table : List.of("lp_price_linked_item", "lp_price_range_factor_rule",
        "lp_price_range_item", "lp_price_fixed_item")) jdbc.update("DELETE FROM " + table);
  }

  @Test
  void fourAvailableTypesAreDistinctAndSettlementLinkedHasNoSource() {
    linked("M1", "2026-10", "210", "COMMERCIAL");
    range("M1", "2026-10", "210", "COMMERCIAL");
    fixed("M1", "SETTLE_FIXED", "2026-10", null, null, "210", "COMMERCIAL");
    fixed("M1", "PURCHASE_FIXED", null, "2026-10-01", "2026-11-01", "210", "COMMERCIAL");
    assertThat(kinds("M1", "2026-10", "210", "COMMERCIAL"))
        .containsExactlyInAnyOrder("LINKED", "RANGE", "SETTLE_FIXED", "FIXED");
  }

  @Test
  void partiallyImportedMonthCannotBorrowOlderMaterialRules() {
    linked("M1", "2026-09", "210", "COMMERCIAL");
    range("M1", "2026-09", "210", "COMMERCIAL");
    range("OTHER", "2026-10", "210", "COMMERCIAL");
    assertThat(kinds("M1", "2026-10", "210", "COMMERCIAL")).isEmpty();
    fixed("M1", "PURCHASE", null, "2026-10-01", "2026-11-01", "210", "COMMERCIAL");
    assertThat(kinds("M1", "2026-10", "210", "COMMERCIAL")).containsExactly("FIXED");
  }

  @Test
  void wholeMonthCarryForwardAndFutureMonthExclusionUseSameScope() {
    linked("M1", "2026-09", "210", "COMMERCIAL");
    linked("M1", "2026-11", "210", "COMMERCIAL");
    range("M1", "2026-11", "210", "COMMERCIAL");
    fixed("M1", "SETTLE", "2026-11", null, null, "210", "COMMERCIAL");
    assertThat(months.select("COMMERCIAL", "210", "2026-10").sourceMonth()).isEqualTo("2026-09");
    assertThat(kinds("M1", "2026-10", "210", "COMMERCIAL")).containsExactly("LINKED");
    assertThat(kinds("M1", "2026-08", "210", "COMMERCIAL")).isEmpty();
  }

  @Test
  void unknownOrganizationAndOtherBusinessUnitsNeverSupplyCandidates() {
    linked("M1", "2026-10", "220", "COMMERCIAL");
    range("M1", "2026-10", "210", "HOUSEHOLD");
    jdbc.update("INSERT INTO lp_price_range_item(material_code,business_unit_type,range_basis) VALUES ('M1','COMMERCIAL','QTY')");
    fixed("M1", "SETTLE_FIXED", "2026-10", null, null, null, "COMMERCIAL");
    fixed("M1", "PURCHASE", null, "2026-10-01", "2026-11-01", "210", null);
    assertThat(kinds("M1", "2026-10", "210", "COMMERCIAL")).isEmpty();
    assertThat(kinds("M1", "2026-10", "220", "COMMERCIAL")).containsExactly("LINKED");
    assertThat(kinds("M1", "2026-10", "210", "HOUSEHOLD")).containsExactly("RANGE");
  }

  @Test
  void purchaseValidityUsesInclusiveStartAndExclusiveEnd() {
    fixed("M1", "PURCHASE", null, "2026-10-01", "2026-10-14", "210", "COMMERCIAL");
    fixed("M1", "PURCHASE_FIXED", null, "2026-10-15", "2026-11-01", "210", "COMMERCIAL");
    fixed("M1", "SETTLE", "2026-10", "2026-10-15", null, "210", "COMMERCIAL");
    jdbc.update("INSERT INTO lp_price_range_item(material_code,business_unit_type,org_code,range_basis,effective_from) VALUES ('M1','COMMERCIAL','210','QTY','2026-10-15')");
    assertThat(kinds("M1", "2026-10", "210", "COMMERCIAL")).isEmpty();
    fixed("M1", "PURCHASE", null, "2026-10-14", "2026-11-01", "210", "COMMERCIAL");
    assertThat(kinds("M1", "2026-10", "210", "COMMERCIAL")).containsExactly("FIXED");
  }

  @Test
  void withdrawnDeletedAndTechnicalRulesCannotAdvancePublicMonth() {
    linked("M1", "2026-09", "210", "COMMERCIAL");
    for (String condition : List.of("deleted=1", "rule_status='WITHDRAWN'", "source_kind='TECH_SUPPLEMENTAL'")) {
      linked("OTHER", "2026-10", "210", "COMMERCIAL");
      Long id = jdbc.queryForObject("SELECT MAX(id) FROM lp_price_linked_item", Long.class);
      jdbc.update("UPDATE lp_price_linked_item SET " + condition + " WHERE id=?", id);
    }
    range("OTHER", "2026-10", "210", "COMMERCIAL");
    jdbc.update("UPDATE lp_price_range_factor_rule SET rule_status='WITHDRAWN'");
    assertThat(kinds("M1", "2026-10", "210", "COMMERCIAL")).containsExactly("LINKED");
  }

  @Test
  void multiplePublishedRevisionsDoNotDuplicateTypeCandidates() {
    linked("M1", "2026-10", "210", "COMMERCIAL");
    linked("M1", "2026-10", "210", "COMMERCIAL");
    assertThat(kinds("M1", "2026-10", "210", "COMMERCIAL")).containsExactly("LINKED");
  }

  private List<String> kinds(String code, String month, String org, String businessUnit) {
    var selected = months.select(businessUnit, org, month);
    return sources.publicKinds(code, month, selected == null ? null : selected.sourceMonth(), businessUnit, org, date);
  }

  private void linked(String code, String month, String org, String unit) {
    jdbc.update("INSERT INTO lp_price_linked_item(material_code,pricing_month,org_code,business_unit_type) VALUES (?,?,?,?)", code, month, org, unit);
  }

  private void range(String code, String month, String org, String unit) {
    jdbc.update("INSERT INTO lp_price_range_factor_rule(material_code,pricing_month,org_code,business_unit_type) VALUES (?,?,?,?)", code, month, org, unit);
  }

  private void fixed(String code, String type, String month, String from, String to, String org, String unit) {
    jdbc.update("INSERT INTO lp_price_fixed_item(material_code,source_type,pricing_month,effective_from,effective_to,org_code,business_unit_type) VALUES (?,?,?,?,?,?,?)", code, type, month, from, to, org, unit);
  }
}
