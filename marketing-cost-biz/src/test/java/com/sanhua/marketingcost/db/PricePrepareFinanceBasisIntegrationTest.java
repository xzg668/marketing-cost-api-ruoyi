package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
@TestPropertySource(
    properties = {
      "srm.fixed-price.enabled=false",
      "oa.person-directory.enabled=false",
      "oa.person-directory.run-at-startup=false",
      "monthly-reprice.enabled=false",
      "cms.sync.publish.enabled=false"
    })
class PricePrepareFinanceBasisIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired PricePrepareBatchMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;

  @Test
  void basisIsWrittenOnceAndParticipatesInPreparationTransaction() {
    new TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              var finance = insert("BASIS-FINANCE", "FINANCE_QUOTE_BASE");
              insert("BASIS-OA", "OA_LOCKED");
              String saved =
                  "{\"price\":\"91.1234567890123456\",\"monthlyPriceId\":901,\"cellRef\":\"E3\"}";
              assertThat(mapper.selectFinanceFactorSnapshot(finance.getPrepareNo())).isNull();
              assertThat(mapper.saveFinanceFactorSnapshot(finance.getPrepareNo(), saved))
                  .isEqualTo(1);
              assertThat(
                      mapper.saveFinanceFactorSnapshot(finance.getPrepareNo(), "{\"price\":120}"))
                  .isZero();
              assertThat(mapper.saveFinanceFactorSnapshot("BASIS-OA", saved)).isZero();
              assertThat(mapper.selectFinanceFactorSnapshot(finance.getPrepareNo()))
                  .contains("91.1234567890123456", "E3");
              assertThat(mapper.selectById(finance.getId()).getPrepareNo())
                  .isEqualTo(finance.getPrepareNo());
              status.setRollbackOnly();
            });
    assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM lp_price_prepare_batch WHERE prepare_no IN"
                    + " ('BASIS-FINANCE','BASIS-OA')",
                Integer.class))
        .isZero();
  }

  @Test
  void forwardMigrationIsRepeatableAndDoesNotBackfillHistoricalPrices() throws Exception {
    insert("BASIS-HISTORY", "FINANCE_QUOTE_BASE");
    try {
      var before =
          jdbc.queryForList(
              "SELECT * FROM lp_price_prepare_batch WHERE prepare_no='BASIS-HISTORY'");
      String script = "V316__price_prepare_finance_basis.sql";
      MYSQL.copyFileToContainer(
          MountableFile.forClasspathResource("db/" + script), "/tmp/" + script);
      for (int attempt = 0; attempt < 2; attempt++) {
        var result =
            MYSQL.execInContainer(
                "sh",
                "-c",
                "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4"
                    + " marketing_cost < /tmp/"
                    + script);
        assertThat(result.getExitCode()).describedAs(result.getStderr()).isZero();
      }
      assertThat(
              jdbc.queryForList(
                  "SELECT * FROM lp_price_prepare_batch WHERE prepare_no='BASIS-HISTORY'"))
          .isEqualTo(before);
      assertThat(mapper.selectFinanceFactorSnapshot("BASIS-HISTORY")).isNull();
    } finally {
      jdbc.update("DELETE FROM lp_price_prepare_batch WHERE prepare_no='BASIS-HISTORY'");
    }
  }

  private PricePrepareBatch insert(String number, String scenario) {
    var batch = new PricePrepareBatch();
    batch.setPrepareNo(number);
    batch.setOaNo("BASIS-OA-1");
    batch.setOaFormItemId(101L);
    batch.setTopProductCode("TOP-1");
    batch.setPeriodMonth("2037-10");
    batch.setBusinessUnitType("COMMERCIAL");
    batch.setScenarioType(scenario);
    batch.setStatus("SUCCESS");
    batch.setBomPurpose("主制造");
    batch.setSourceType("U9");
    batch.setPriceAsOfTime(java.time.LocalDateTime.of(2037, 10, 15, 12, 0));
    batch.setPriceAsOfSource("REQUEST");
    assertThat(mapper.insert(batch)).isEqualTo(1);
    return batch;
  }
}
