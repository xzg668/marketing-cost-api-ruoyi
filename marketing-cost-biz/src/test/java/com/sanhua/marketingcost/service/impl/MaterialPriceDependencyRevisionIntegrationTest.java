package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.MaterialPriceSourceType;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.service.MaterialPricePriorityService;
import com.sanhua.marketingcost.service.MaterialPricePriorityService.Scope;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.ibatis.mapping.Environment;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

/** 父项修订读取真实 JSON 底稿与财务配置，校验编辑失效而冻结输入不变。 */
@Tag("integration")
class MaterialPriceDependencyRevisionIntegrationTest {
  private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
      .withDatabaseName("priority_dependencies").withUsername("root").withPassword("test-only-password");
  private static JdbcTemplate jdbc;
  private static CostInputRevisionServiceImpl revisions;
  private static MaterialPricePriorityService priorities;
  private static TransactionTemplate transaction;
  private final Scope child = new Scope("COMMERCIAL", "2026-10", "210", "CHILD");

  @BeforeAll
  static void start() {
    MYSQL.start();
    var data = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(data);
    new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
        new org.springframework.core.io.ClassPathResource("db/V332__settlement_price_versions.sql")).execute(data);
    jdbc.execute("""
        CREATE TABLE lp_material_price_type (
          id BIGINT AUTO_INCREMENT PRIMARY KEY,business_unit_type VARCHAR(32),period CHAR(7),
          price_org_code VARCHAR(32),material_code VARCHAR(64),material_name VARCHAR(128),price_type VARCHAR(32),
          priority TINYINT,manual_priority TINYINT,priority_updated_by BIGINT,priority_updated_at DATETIME(6),
          source VARCHAR(32),source_system VARCHAR(32),created_at DATETIME,updated_at DATETIME,
          UNIQUE KEY uk_scope(business_unit_type,period,price_org_code,material_code,price_type))
          DEFAULT CHARACTER SET utf8mb3 COLLATE utf8mb3_bin
        """);
    jdbc.execute("""
        CREATE TABLE lp_price_prepare_item (
          id BIGINT AUTO_INCREMENT PRIMARY KEY,oa_form_item_id BIGINT,period_month CHAR(7),current_flag TINYINT,
          material_code VARCHAR(64),price_org_code VARCHAR(32),input_snapshot_json JSON)
          DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci
        """);
    jdbc.execute("CREATE TABLE lp_quote_costing_workspace (oa_form_item_id BIGINT,period_month CHAR(7),current_bom_build_batch_id VARCHAR(64)) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    jdbc.execute("CREATE TABLE lp_quote_effective_bom_node (build_batch_id VARCHAR(64),material_code VARCHAR(64),price_org_code VARCHAR(32)) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    jdbc.execute("CREATE TABLE lp_quote_final_submission (id BIGINT,oa_form_id BIGINT,accounting_month CHAR(7),returned_at DATETIME)");
    for (String table : List.of("cms_cost_source_effective", "lp_department_fund_rate", "lp_quality_loss_rate",
        "lp_manufacture_rate", "lp_three_expense_rate", "lp_other_expense_rate", "lp_product_property",
        "lp_product_property_rule", "lp_price_settle", "lp_supplier_supply_ratio", "lp_bom_byproduct_cost_rule",
        "lp_cost_business_rule")) jdbc.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY)");
    for (String table : List.of("lp_price_fixed_item", "lp_price_linked_item", "lp_price_range_item",
        "lp_price_range_factor_rule", "lp_price_settle_item")) {
      jdbc.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY,material_code VARCHAR(64),fixed_price DECIMAL(24,12))");
    }
    var config = new MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    config.setEnvironment(new Environment("dependencies", new SpringManagedTransactionFactory(), data));
    config.addMapper(MaterialPriceTypeMapper.class);
    var session = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
    var transactions = new DataSourceTransactionManager(data);
    transaction = new TransactionTemplate(transactions);
    priorities = new MaterialPricePriorityService(session.getMapper(MaterialPriceTypeMapper.class), transactions);
    revisions = new CostInputRevisionServiceImpl(jdbc);
  }

  @AfterAll static void stop() { MYSQL.stop(); }
  @BeforeEach void clear() {
    for (String table : List.of("lp_material_price_type", "lp_price_prepare_item", "lp_price_fixed_item",
        "lp_quote_costing_workspace", "lp_quote_effective_bom_node")) jdbc.update("DELETE FROM " + table);
  }

  @Test
  void mixedCollationsPreserveEveryMaterialAndOrganizationDependency() {
    prepare(1L, "2026-10", "210", """
        {"components":[{"materialCode":"CHILD","inputs":{"pricePriority":{"scope":
          {"materialCode":"CHILD","priceOrgCode":"220"}}}},
          {"materialCode":"child","inputs":{"pricePriority":{"scope":
          {"materialCode":"child","priceOrgCode":"210"}}}}]}
        """);
    jdbc.update("INSERT INTO lp_quote_costing_workspace VALUES (1,'2026-10','BOM')");
    jdbc.update("INSERT INTO lp_quote_effective_bom_node VALUES ('BOM','PARENT','210'),('BOM','CHILD','210')");
    assertThat(jdbc.queryForList(CostInputRevisionServiceImpl.MATERIAL_CODES_SQL,
        String.class, 1L, "2026-10", 1L, "2026-10"))
        .containsExactly("CHILD", "PARENT", "child");
    var scopes = jdbc.query(CostInputRevisionServiceImpl.PRIORITY_SCOPES_SQL,
        (rs, row) -> rs.getString("material_code") + ":" + rs.getString("price_org_code"),
        1L, "2026-10", 1L, "2026-10");
    assertThat(scopes).containsExactlyInAnyOrder("PARENT:210", "CHILD:210", "CHILD:220", "child:210");
  }

  @ParameterizedTest
  @ValueSource(strings = {"PACKAGE", "MAKE_PART"})
  void childFinanceEditInvalidatesParentWithoutRewritingFrozenInputs(String kind) {
    String snapshot = "{\"kind\":\"" + kind + "\",\"components\":[{\"materialCode\":\"CHILD\",\"inputs\":{\"pricePriority\":{\"scope\":{\"priceOrgCode\":\"210\",\"materialCode\":\"CHILD\"},\"revision\":\"frozen-original\"},\"amount\":123.123456789012}}]}";
    prepare(1L, "2026-10", "210", snapshot);
    prepare(2L, "2026-10", "220", snapshot.replace("210", "220"));
    prepare(1L, "2026-11", "210", snapshot);
    var form = form();
    String original = revisions.currentRevision(form, item(1L), "2026-10");
    String otherOrg = revisions.currentRevision(form, item(2L), "2026-10");
    String otherMonth = revisions.currentRevision(form, item(1L), "2026-11");
    priorities.initialize(child, "子料");
    assertThat(revisions.currentRevision(form, item(1L), "2026-10")).isEqualTo(original);
    var reverse = new ArrayList<>(MaterialPriceSourceType.DEFAULT_ORDER);
    Collections.reverse(reverse);
    transaction.executeWithoutResult(status -> priorities.change(child, reverse, priorities.read(child).revision(), 9L));
    String edited = revisions.currentRevision(form, item(1L), "2026-10");
    assertThat(edited).isNotEqualTo(original);
    assertThat(revisions.currentRevision(form, item(2L), "2026-10")).isEqualTo(otherOrg);
    assertThat(revisions.currentRevision(form, item(1L), "2026-11")).isEqualTo(otherMonth);
    var stored = jdbc.queryForObject("SELECT CAST(input_snapshot_json AS CHAR) FROM lp_price_prepare_item WHERE oa_form_item_id=1 AND period_month='2026-10'", String.class);
    assertThat(stored).contains("frozen-original", "123.123456789012");
    transaction.executeWithoutResult(status -> priorities.change(child, null, priorities.read(child).revision(), 9L));
    assertThat(revisions.currentRevision(form, item(1L), "2026-10")).isNotEqualTo(edited).isNotEqualTo(original);
    assertThat(jdbc.queryForObject("SELECT CAST(input_snapshot_json AS CHAR) FROM lp_price_prepare_item WHERE oa_form_item_id=1 AND period_month='2026-10'", String.class)).isEqualTo(stored);
  }

  @Test
  void componentSourcePriceChangeAlsoInvalidatesParent() {
    prepare(1L, "2026-10", "210", "{\"components\":[{\"materialCode\":\"CHILD\"}]}");
    jdbc.update("INSERT INTO lp_price_fixed_item VALUES (1,'CHILD',10)");
    String before = revisions.currentRevision(form(), item(1L), "2026-10");
    jdbc.update("UPDATE lp_price_fixed_item SET fixed_price=20 WHERE id=1");
    assertThat(revisions.currentRevision(form(), item(1L), "2026-10")).isNotEqualTo(before);
  }

  private void prepare(Long item, String month, String org, String snapshot) {
    jdbc.update("INSERT INTO lp_price_prepare_item(oa_form_item_id,period_month,current_flag,material_code,price_org_code,input_snapshot_json) VALUES (?,?,1,'PARENT',?,?)", item, month, org, snapshot);
  }
  private OaForm form() {
    var form = new OaForm(); form.setId(1L); form.setOaNo("OA"); form.setBusinessUnitType("COMMERCIAL"); return form;
  }
  private OaFormItem item(Long id) {
    var item = new OaFormItem(); item.setId(id); item.setOaFormId(1L); item.setBusinessUnitType("COMMERCIAL"); return item;
  }
}
