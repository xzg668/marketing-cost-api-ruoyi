package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.mapper.monthly.MonthlyOdsPriceSourceMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class MonthlyOdsPriceSourceMapperIntegrationTest {
  static final String ORDER = "ods_sy_v_mm_material_order_price_cg05_df";
  static final String TIER = "ods_sy_v_mm_material_jt_price_cg05_df";
  static final String COMMERCIAL = "浙江三花商用制冷有限公司";
  static final String BOARD = "浙江三花板换科技有限公司";
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
      System.getProperty("mpp.mysql.image", "mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("ods_price_source_test").withUsername("root")
      .withPassword("ods-price-source-test-only").withConfigurationOverride("monthly-pricing/mysql");
  static JdbcTemplate jdbc;
  static MonthlyOdsPriceSourceMapper mapper;

  @BeforeAll static void start() throws Exception {
    MYSQL.start();
    var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    jdbc = new JdbcTemplate(ds);
    assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("ods_price_source_test");
    assertThat(jdbc.queryForObject("SELECT @@version", String.class))
        .startsWith(System.getProperty("mpp.mysql.expected-version", "8.4"));
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/ods-price-source-schema.sql")).execute(ds);
    var config = new org.apache.ibatis.session.Configuration();
    config.setMapUnderscoreToCamelCase(true);
    config.addMapper(MonthlyOdsPriceSourceMapper.class);
    var factory = new SqlSessionFactoryBean();
    factory.setDataSource(ds); factory.setConfiguration(config);
    mapper = new SqlSessionTemplate(factory.getObject()).getMapper(MonthlyOdsPriceSourceMapper.class);
  }

  @AfterAll static void stop() { MYSQL.stop(); }
  @BeforeEach void emptyOwnedFixture() {
    jdbc.update("DELETE FROM " + ORDER);
    jdbc.update("DELETE FROM " + TIER);
  }

  private void insert(String table, String company, String code, String partition,
      String from, String to, String price) {
    assertThat(table).isIn(ORDER, TIER);
    jdbc.update("INSERT INTO " + table + " (company_name,material_code,material_name,spec,supply_code,"
        + "supply_name,unit,price,eff_date,exp_date,source,dt) VALUES(?,?,'fixture name','fixture spec',"
        + "'S1','fixture supplier','只',?,?,?,'fixture approval',?)",
        company, code, new BigDecimal(price), from, to, partition);
  }

  @Test void latestPartitionWinsAndMissingMaterialsNeverFallBackToOlderPartitions() {
    for (var table : List.of(ORDER, TIER)) {
      insert(table, COMMERCIAL, "M1", "2026-10-23", "2026-01-01", null, "99");
      insert(table, COMMERCIAL, "M2", "2026-10-23", "2026-01-01", null, "88");
      insert(table, COMMERCIAL, "M1", "2026-10-24", "2026-01-01", null, "12");
    }
    for (var result : List.of(mapper.orders(COMMERCIAL, List.of("M1", "M2")),
        mapper.tiers(COMMERCIAL, List.of("M1", "M2")))) {
      assertThat(result).hasSize(1);
      assertThat(result.getFirst().getMaterialCode()).isEqualTo("M1");
      assertThat(result.getFirst().getDt()).isEqualTo("2026-10-24");
      assertThat(result.getFirst().getPrice()).isEqualByComparingTo("12");
    }
  }

  @Test void partitionIsLatestForTheWholeTableRatherThanForEachCompany() {
    for (var table : List.of(ORDER, TIER)) {
      insert(table, COMMERCIAL, "M1", "2026-10-23", null, null, "10");
      insert(table, BOARD, "M1", "2026-10-24", null, null, "20");
    }
    assertThat(mapper.orders(COMMERCIAL, List.of("M1"))).isEmpty();
    assertThat(mapper.tiers(COMMERCIAL, List.of("M1"))).isEmpty();
    assertThat(mapper.orders(BOARD, List.of("M1"))).hasSize(1);
    assertThat(mapper.tiers(BOARD, List.of("M1"))).hasSize(1);
  }

  @Test void orderReadDoesNotExcludeFutureEffectiveDatesAndRetainsNullDatesForExplicitPolicy() {
    insert(ORDER, COMMERCIAL, "M1", "2026-10-24", "2027-01-01", null, "12");
    insert(ORDER, COMMERCIAL, "M2", "2026-10-24", null, null, "13");
    assertThat(mapper.orders(COMMERCIAL, List.of("M1", "M2"))).hasSize(2);
  }

  @Test void rawTierReadPreservesDatesForTheAdaptersVersionSelection() {
    insert(TIER, COMMERCIAL, "EXPIRED", "2026-10-24", "2024-01-01", "2024-12-31", "1");
    insert(TIER, COMMERCIAL, "FUTURE", "2026-10-24", "2030-01-01", "2030-12-31", "2");
    insert(TIER, COMMERCIAL, "UNDATED", "2026-10-24", null, null, "3");
    assertThat(mapper.tiers(COMMERCIAL, List.of("EXPIRED", "FUTURE", "UNDATED")))
        .extracting(row -> row.getMaterialCode()).containsExactlyInAnyOrder("EXPIRED", "FUTURE", "UNDATED");
  }

  @Test void preservesCompanyExactMaterialCodesAndIndependentTablePartitions() {
    insert(ORDER, COMMERCIAL, "001M", "2026-10-25", null, null, "10");
    insert(ORDER, BOARD, "001M", "2026-10-25", null, null, "20");
    insert(TIER, COMMERCIAL, "001M", "2026-10-24", null, null, "30");
    assertThat(mapper.orders(COMMERCIAL, List.of("001M"))).singleElement()
        .satisfies(row -> assertThat(row.getPrice()).isEqualByComparingTo("10"));
    assertThat(mapper.tiers(COMMERCIAL, List.of("001M"))).singleElement()
        .satisfies(row -> assertThat(row.getDt()).isEqualTo("2026-10-24"));
    assertThat(mapper.orders(COMMERCIAL, List.of("1M", "001m"))).isEmpty();
  }

  @Test void emptyRequestedMaterialsNeverReadTheWholeSource() {
    insert(ORDER, COMMERCIAL, "M1", "2026-10-24", null, null, "1");
    insert(TIER, COMMERCIAL, "M1", "2026-10-24", null, null, "1");
    assertThat(mapper.orders(COMMERCIAL, List.of())).isEmpty();
    assertThat(mapper.tiers(COMMERCIAL, List.of())).isEmpty();
  }

  @Test void mapsOriginalSourceSupplierOrderTierAndDecimalFieldsWithoutWriting() {
    insert(ORDER, COMMERCIAL, "M1", "2026-10-24", "2025-09-04", null, "23.29210000");
    jdbc.update("UPDATE " + ORDER + " SET order_no='CGDDCG05202509040092',order_line_no='7' WHERE material_code='M1'");
    insert(TIER, COMMERCIAL, "M1", "2026-10-24", null, null, "0.37500000");
    jdbc.update("UPDATE " + TIER + " SET jieti_down=20000,jieti_up=NULL WHERE material_code='M1'");
    var order = mapper.orders(COMMERCIAL, List.of("M1")).getFirst();
    assertThat(order.getSupplierCode()).isEqualTo("S1");
    assertThat(order.getSupplierName()).isEqualTo("fixture supplier");
    assertThat(order.getOrderNo()).isEqualTo("CGDDCG05202509040092");
    assertThat(order.getOrderLineNo()).isEqualTo("7");
    assertThat(order.getPrice()).isEqualByComparingTo("23.2921");
    assertThat(order.getSource()).isEqualTo("fixture approval");
    assertThat(order.getSyncedAt()).isNotNull();
    var tier = mapper.tiers(COMMERCIAL, List.of("M1")).getFirst();
    assertThat(tier.getJietiDown()).isEqualByComparingTo("20000");
    assertThat(tier.getJietiUp()).isNull();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + ORDER, Long.class)).isEqualTo(1);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + TIER, Long.class)).isEqualTo(1);
  }
}
