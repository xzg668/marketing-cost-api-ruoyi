package com.sanhua.marketingcost.service.pricing.inquiry;

import static org.assertj.core.api.Assertions.*;

import com.baomidou.mybatisplus.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.financefactor.*;
import com.sanhua.marketingcost.service.impl.*;
import java.util.*;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.*;

/** New disposable database only; no connection to application or previous acceptance databases. */
public final class InquiryDatabaseTestSupport implements AutoCloseable {
  public final MySQLContainer<?> mysql =
      new MySQLContainer<>(
              DockerImageName.parse(System.getProperty("mpp.mysql.image", "mysql:8.4"))
                  .asCompatibleSubstituteFor("mysql"))
          .withConfigurationOverride("monthly-pricing/mysql")
          .withDatabaseName("inquiry_pricing_test")
          .withUsername("root")
          .withPassword("inquiry-test-only")
          .withCommand("--max-allowed-packet=64M");
  public JdbcTemplate jdbc;
  public SqlSessionTemplate sql;
  public DataSourceTransactionManager manager;
  public final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  public FinanceFactorPriceReader finance;
  public FinanceFactorPublicationService publications;
  public InquiryPriceRepository repository;
  public InquiryPriceRefreshService refresh;
  public InquiryPriceService service;

  public void start() throws Exception {
    mysql.start();
    for (String script :
        List.of(
            "prc01_pre_migration_schema.sql",
            "V308__original_price_version_contract.sql",
            "V309__finance_factor_publication_note.sql",
            "V311__original_linked_expression_capacity.sql",
            "V325__finance_effective_price.sql",
            "V326__original_workbook_source.sql",
            "V330__scrap_price_monthly.sql",
            "V332__settlement_price_versions.sql",
            "V341__settlement_effective_price.sql",
            "V356__inquiry_price_sources.sql")) {
      mysql.copyFileToContainer(
          MountableFile.forClasspathResource("db/" + script), "/tmp/inquiry-schema.sql");
      var result =
          mysql.execInContainer(
              "sh",
              "-c",
              "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4"
                  + " inquiry_pricing_test < /tmp/inquiry-schema.sql");
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
    var data =
        new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    jdbc = new JdbcTemplate(data);
    manager = new DataSourceTransactionManager(data);
    assertThat(jdbc.queryForObject("SELECT @@version", String.class))
        .startsWith(System.getProperty("mpp.mysql.expected-version", "8.4"));
    var config = new MybatisConfiguration();
    config.setMapUnderscoreToCamelCase(true);
    config.setEnvironment(
        new Environment("inquiry-test", new SpringManagedTransactionFactory(), data));
    for (var mapper :
        List.of(
            FactorIdentityMapper.class,
            FactorUploadBatchMapper.class,
            FactorMonthlyPriceMapper.class,
            FactorRowRefMapper.class,
            FinanceEffectivePriceMapper.class,
            PriceLinkedItemMapper.class,
            PriceRangeFactorRuleMapper.class,
            ScrapPriceMapper.class)) config.addMapper(mapper);
    sql = new SqlSessionTemplate(new MybatisSqlSessionFactoryBuilder().build(config));
    finance =
        new FinanceFactorPriceReader(
            sql.getMapper(FactorUploadBatchMapper.class),
            sql.getMapper(FactorMonthlyPriceMapper.class),
            sql.getMapper(FactorIdentityMapper.class),
            sql.getMapper(FactorRowRefMapper.class));
    repository = new InquiryPriceRepository(jdbc, json);
    refresh = new InquiryPriceRefreshService(repository, finance, manager);
    service = new InquiryPriceService(repository, new InquiryWorkbookParser(), refresh, manager);
    var canonical = new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl());
    publications =
        new FinanceFactorPublicationService(
            new FinanceFactorWorkbookParser(canonical),
            canonical,
            sql.getMapper(FactorIdentityMapper.class),
            sql.getMapper(FactorUploadBatchMapper.class),
            sql.getMapper(FactorMonthlyPriceMapper.class),
            sql.getMapper(FactorRowRefMapper.class),
            json,
            manager,
            com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceTestSupport.service(
                sql, json),
            com.sanhua.marketingcost.service.scrap.ScrapPriceTestSupport.service(
                sql, manager, json),
            com.sanhua.marketingcost.service.settlement.SettlementEffectivePriceTestSupport.service(
                sql, json),
            refresh, org.mockito.Mockito.mock(com.sanhua.marketingcost.service.financefactor.FinanceMonthlyPriceRefreshService.class));
  }

  public void clearInquiries() {
    for (String table :
        List.of("lp_inquiry_effective_price", "lp_inquiry_rule", "lp_inquiry_import_batch"))
      jdbc.update("DELETE FROM " + table);
  }

  public void publishFinance(String month, String scheme) throws Exception {
    byte[] bytes;
    try (var stream = getClass().getResourceAsStream("/finance-factor/commercial-original.xlsx")) {
      bytes = Objects.requireNonNull(stream).readAllBytes();
    }
    var context = new FinanceFactorImportContext("COMMERCIAL", "210", month, null, scheme);
    var preview = publications.preview(bytes, "商用基价.xlsx", context);
    publications.publish(
        bytes, "商用基价.xlsx", context, preview.previewFingerprint(), "finance-fixture");
  }

  public InquiryPriceService.Published importFile(String month, byte[] bytes) {
    return service.publish(
        "COMMERCIAL", month, "inquiry-fixture", UUID.randomUUID().toString(), "询价.xls", bytes);
  }

  public List<InquiryPriceService.Current> prices(String month) {
    return service.current("COMMERCIAL", month, null, 1, 100).records();
  }

  @Override
  public void close() {
    mysql.stop();
  }
}
