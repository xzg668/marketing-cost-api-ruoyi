package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.sanhua.marketingcost.mapper.AuxCostItemMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import com.sanhua.marketingcost.mapper.CostRunManufacturingSourceMapper;
import com.sanhua.marketingcost.service.costing.CostRunManufacturingSources;
import com.sanhua.marketingcost.service.costing.CostRunManufacturingSources.Demand;
import com.sanhua.marketingcost.service.costing.CostRunManufacturingSources.Sources;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("integration")
class CostRunManufacturingSourcesIntegrationTest {
  static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
      System.getProperty("mpp.mysql.image", "mysql:8.4")).asCompatibleSubstituteFor("mysql"))
      .withDatabaseName("reprice_manufacturing").withUsername("test").withPassword("test")
      .withConfigurationOverride("monthly-pricing/mysql")
      .withLabel("sales-cost.task", System.getProperty("mpp.mysql.task", "manufacturing-batch-test"))
      .withLabel("sales-cost.run", UUID.randomUUID().toString())
      .withReuse(Boolean.getBoolean("mpp.mysql.retain"));
  static final AtomicInteger statements = new AtomicInteger();
  static JdbcTemplate jdbc;
  static CmsCostSourceEffectiveMapper salary;
  static AuxCostItemMapper auxiliary;
  static CostRunManufacturingSources reader;

  @BeforeAll static void start() throws Exception {
    MYSQL.start();
    var original = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    var source = new DelegatingDataSource(original) {
      @Override public java.sql.Connection getConnection() throws java.sql.SQLException {
        var connection = super.getConnection();
        return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
              if (method.getName().equals("prepareStatement")) statements.incrementAndGet();
              try { return method.invoke(connection, args); }
              catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            });
      }
    };
    jdbc = new JdbcTemplate(source);
    assertThat(jdbc.queryForObject("SELECT @@version", String.class))
        .startsWith(System.getProperty("mpp.mysql.expected-version", "8.4"));
    // Extract this one table from its authoritative DDL; never execute the full historical migration.
    String migration = new ClassPathResource("db/V64__cms_cost_source_schema.sql")
        .getContentAsString(StandardCharsets.UTF_8);
    int start = migration.indexOf("CREATE TABLE IF NOT EXISTS cms_cost_source_effective (");
    jdbc.execute(migration.substring(start, migration.indexOf(";", start) + 1));
    var config = new MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    config.addMapper(CmsCostSourceEffectiveMapper.class);
    config.addMapper(AuxCostItemMapper.class);
    config.addMapper(CostRunManufacturingSourceMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(source); factory.setConfiguration(config);
    var session = new SqlSessionTemplate(factory.getObject());
    salary = session.getMapper(CmsCostSourceEffectiveMapper.class);
    auxiliary = session.getMapper(AuxCostItemMapper.class);
    reader = new CostRunManufacturingSources(session.getMapper(CostRunManufacturingSourceMapper.class));
  }

  @AfterAll static void stop() { if (!Boolean.getBoolean("mpp.mysql.retain")) MYSQL.stop(); }

  @Test void repeatedConsumersReadOnceWithoutSharingMutableBeansOrChangingDatabase() {
    String code = code();
    row(code, 2026, "COMMERCIAL", "2026-12", "SALARY_DIRECT", "0301", null, "3.123456");
    row(code, 2026, "COMMERCIAL", "2026-08", "SALARY_INDIRECT", "0302", null, "-0.001234");
    row(code, 2026, "COMMERCIAL", "2026-10", "AUX_SUBJECT", "0201", "辅料", "1.234567");
    var demand = demand(code, "2026-10");
    var demands = Collections.nCopies(64, demand);
    var before = jdbc.queryForList("SELECT * FROM cms_cost_source_effective WHERE parent_code=? ORDER BY id", code);
    int initial = statements.get();
    var serial = demands.stream().map(CostRunManufacturingSourcesIntegrationTest::serial).toList();
    int serialCount = statements.get() - initial;
    initial = statements.get();
    var batch = reader.readBatch(demands);
    int batchCount = statements.get() - initial;
    assertThat(batch).usingRecursiveComparison().isEqualTo(serial);
    assertThat(serialCount).isEqualTo(128); assertThat(batchCount).isEqualTo(2);
    batch.getFirst().salary().getFirst().setAmountYuan(BigDecimal.TEN);
    batch.getFirst().auxiliary().getFirst().setUnitPrice(BigDecimal.TEN);
    assertThat(batch.get(1)).usingRecursiveComparison().isEqualTo(serial.get(1));
    assertThat(reader.readBatch(List.of(demand)).getFirst()).usingRecursiveComparison().isEqualTo(serial.getFirst());
    assertThat(jdbc.queryForList("SELECT * FROM cms_cost_source_effective WHERE parent_code=? ORDER BY id", code)).isEqualTo(before);
    System.out.println("REPRICE_MANUFACTURING_SQL serial=" + serialCount + " batch=" + batchCount + " consumers=64");
  }

  @Test void mixedYearsUnitsMonthsAndMaterialSetsKeepOriginalSqlApplicabilityAndOrder() {
    String a = code(), b = code();
    row(a, 2026, "COMMERCIAL", "2026-12", "SALARY_DIRECT", "0301", null, "2.123456");
    row(a, 2026, "COMMERCIAL", "2026-02", "SALARY_INDIRECT", "0302", null, "1");
    row(a, 2026, "COMMERCIAL", "2026-11", "AUX_SUBJECT", "0202", "辅料二", "4");
    row(a, 2026, "COMMERCIAL", "2026-09", "AUX_SUBJECT", "0201", "辅料一", "3");
    row(a, 2026, "COMMERCIAL", "2026-09", "AUX_SUBJECT", "0203", " 包装辅料 ", "100");
    row(a, 2025, "COMMERCIAL", "2025-12", "SALARY_DIRECT", "0301", null, "7");
    row(a, 2026, "HOUSEHOLD", "2026-01", "SALARY_DIRECT", "0301", null, "8");
    row(b, 2026, "COMMERCIAL", "2026-12", "SALARY_DIRECT", "0301", null, "9");
    row(b, 2026, "COMMERCIAL", "2026-10", "AUX_SUBJECT", "0204", null, "-0.000001");
    var demands = List.of(demand(a, "2026-10"), demand(a, "2026-11"), demand(b, "2026-11"),
        new Demand(2025, "COMMERCIAL", "2025-12", Set.of(a), true, true),
        new Demand(2026, "HOUSEHOLD", "2026-11", Set.of(a), true, true),
        new Demand(2026, "COMMERCIAL", "2026-11", Set.of(a, b), true, true),
        demand(a, null));
    var expected = demands.stream().map(CostRunManufacturingSourcesIntegrationTest::serial).toList();
    assertThat(reader.readBatch(demands)).usingRecursiveComparison().isEqualTo(expected);
    assertThat(expected.get(0).salary()).hasSize(2);
    assertThat(expected.get(0).auxiliary()).hasSize(1);
    assertThat(expected.get(1).auxiliary()).hasSize(2);
    assertThat(expected.get(6).auxiliary()).isEmpty();
  }

  @Test void databaseCollationMatchesAreRetainedInsteadOfFilteringWithJavaEquality() {
    String code = code() + "-AbC";
    row(code, 2026, "COMMERCIAL", "2026-10", "SALARY_DIRECT", "0301", null, "1.123456");
    row(code, 2026, "COMMERCIAL", "2026-10", "AUX_SUBJECT", "0201", "辅料", "0.000001");
    var demands = List.of(demand(code, "2026-10"), demand(code.toLowerCase(java.util.Locale.ROOT), "2026-10"));
    var expected = demands.stream().map(CostRunManufacturingSourcesIntegrationTest::serial).toList();
    assertThat(expected.get(1).salary()).hasSize(1);
    assertThat(reader.readBatch(demands)).usingRecursiveComparison().isEqualTo(expected);
  }

  @Test void sixtyFourDistinctQueriesKeepEachProductsRowsAtTheBatchBoundary() {
    var demands = new ArrayList<Demand>();
    for (int i = 0; i < 64; i++) {
      String code = code();
      row(code, 2026, "COMMERCIAL", "2026-12", "SALARY_DIRECT", "0301", null, i + ".123456");
      row(code, 2026, "COMMERCIAL", "2026-10", "AUX_SUBJECT", "0201", "辅料", "-0.000001");
      demands.add(demand(code, "2026-10"));
    }
    var expected = demands.stream().map(CostRunManufacturingSourcesIntegrationTest::serial).toList();
    int initial = statements.get();
    assertThat(reader.readBatch(demands)).usingRecursiveComparison().isEqualTo(expected);
    assertThat(statements.get() - initial).isEqualTo(2);
    assertThat(expected).allSatisfy(s -> { assertThat(s.salary()).hasSize(1); assertThat(s.auxiliary()).hasSize(1); });
  }

  @Test void onlyRequestedPublicSourcesAreReadAndMissingInputsDoNotTriggerUnboundedQueries() {
    String code = code();
    row(code, 2026, "COMMERCIAL", "2026-12", "SALARY_DIRECT", "0301", null, "1");
    row(code, 2026, "COMMERCIAL", "2026-10", "AUX_SUBJECT", "0201", "辅料", "2");
    var demands = List.of(new Demand(2026, "COMMERCIAL", "2026-10", Set.of(code), false, true),
        new Demand(2026, "COMMERCIAL", "2026-10", Set.of(code), true, false),
        new Demand(2026, "COMMERCIAL", "2026-10", Set.of(code), false, false),
        new Demand(null, "COMMERCIAL", "2026-10", Set.of(code), true, true),
        new Demand(2026, "COMMERCIAL", "2026-10", Set.of(), true, true));
    var result = reader.readBatch(demands);
    assertThat(result.get(0).salary()).isEmpty(); assertThat(result.get(0).auxiliary()).hasSize(1);
    assertThat(result.get(1).salary()).hasSize(1); assertThat(result.get(1).auxiliary()).isEmpty();
    assertThat(result.subList(2, 5)).allSatisfy(s -> { assertThat(s.salary()).isEmpty(); assertThat(s.auxiliary()).isEmpty(); });
    int initial = statements.get();
    assertThat(reader.readBatch(List.of())).isEmpty();
    reader.readBatch(demands.subList(2, 5));
    assertThat(statements.get()).isEqualTo(initial);
  }

  @Test void aNewReadSeesChangedSourcesAndBoundsFailBeforeSql() {
    String code = code();
    row(code, 2026, "COMMERCIAL", "2026-10", "SALARY_DIRECT", "0301", null, "1");
    var demand = demand(code, "2026-10");
    var before = reader.readBatch(List.of(demand));
    jdbc.update("UPDATE cms_cost_source_effective SET amount_yuan=7 WHERE parent_code=?", code);
    var after = reader.readBatch(List.of(demand));
    assertThat(before.getFirst().salary().getFirst().getAmountYuan()).isEqualByComparingTo("1");
    assertThat(after.getFirst().salary().getFirst().getAmountYuan()).isEqualByComparingTo("7");
    int initial = statements.get();
    assertThatThrownBy(() -> reader.readBatch(Collections.nCopies(65, demand))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Demand(2026, "COMMERCIAL", "2026-10", Set.of(" "), true, true)).isInstanceOf(IllegalArgumentException.class);
    var codes = java.util.stream.IntStream.range(0, 257).mapToObj(i -> "P" + i).collect(java.util.stream.Collectors.toSet());
    assertThatThrownBy(() -> new Demand(2026, "COMMERCIAL", "2026-10", codes, true, true)).isInstanceOf(IllegalArgumentException.class);
    assertThat(statements.get()).isEqualTo(initial);
  }

  private static Sources serial(Demand d) {
    return new Sources(salary.selectSalarySources(d.costYear(), d.businessUnitType(), d.materialCodes()),
        auxiliary.selectEffectiveAuxCostItems(d.costYear(), d.materialCodes(), d.businessUnitType(), d.pricingMonth()));
  }
  private static Demand demand(String code, String month) { return new Demand(2026, "COMMERCIAL", month, Set.of(code), true, true); }
  private static String code() { return "R02-" + UUID.randomUUID(); }
  private static void row(String code, int year, String unit, String period, String type, String subject, String name, String amount) {
    jdbc.update("INSERT INTO cms_cost_source_effective(cost_year,source_type,parent_code,period,subject_code,subject_name,source_table,source_row_ids,amount_yuan,business_unit_type) VALUES(?,?,?,?,?,?,'fixture','[123]',?,?)",
        year, type, code, period, subject, name, new BigDecimal(amount), unit);
  }
}
