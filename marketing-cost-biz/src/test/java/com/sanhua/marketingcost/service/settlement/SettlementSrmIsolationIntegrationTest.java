package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.pricing.settlement.*;
import com.sanhua.marketingcost.service.srm.SrmFixedPricePublishService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class SettlementSrmIsolationIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactions;
  @Autowired SettlementPriceImportService imports;
  @Autowired SettlementPriceReader reader;
  @Autowired MaterialPriceRouterService router;
  private static final LocalDate BATCH = LocalDate.of(2099, 1, 2);
  private static final Path ARCHIVE;
  static {
    try { ARCHIVE = Files.createTempDirectory("settlement-srm-tests-"); }
    catch (java.io.IOException e) { throw new ExceptionInInitializerError(e); }
  }
  @DynamicPropertySource static void archive(DynamicPropertyRegistry p) {
    p.add("price.settlement.workbooks-root", ARCHIVE::toString);
  }
  @BeforeEach void setup() throws Exception {
    jdbc.execute("DROP TRIGGER IF EXISTS stp_fail_srm_insert");
    jdbc.execute("CREATE TABLE IF NOT EXISTS lp_price_fixed_item_srm_raw (id BIGINT PRIMARY KEY AUTO_INCREMENT,company VARCHAR(255),material_code VARCHAR(64),material_name VARCHAR(255),spec VARCHAR(255),sup_code VARCHAR(64),sup_name VARCHAR(255),unit VARCHAR(32),price DECIMAL(32,8),eff_date VARCHAR(32),exp_date VARCHAR(32),`source` VARCHAR(255),dt VARCHAR(10) NOT NULL,synced_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    for (String table : List.of("lp_price_fixed_item_srm_raw", "lp_price_fixed_item", "lp_settlement_price", "lp_settlement_base_price")) jdbc.update("DELETE FROM " + table);
    for (String day : List.of("2099-01-01", "2099-01-02")) {
      for (String company : List.of("浙江三花商用制冷有限公司", "浙江三花板换科技有限公司"))
        jdbc.update("INSERT INTO lp_price_fixed_item_srm_raw(company,material_code,material_name,spec,sup_code,sup_name,unit,price,eff_date,`source`,dt,synced_at) VALUES (?,?,'原料','测试规格','S-1','供应商','只',12,'2026-01-01','固定价',?,NOW()-INTERVAL 1 HOUR)", company, company.endsWith("板换科技有限公司") ? "NEW-220" : "NEW-210", day);
    }
    jdbc.update("INSERT INTO lp_price_fixed_item(material_code,source_kind,source_type,fixed_price,business_unit_type,org_code,created_at,updated_at) VALUES ('OLD-PURCHASE','PUBLIC','PURCHASE_FIXED',1,'COMMERCIAL','210',NOW(),NOW()),('809633','PUBLIC','SETTLE_FIXED',999,'COMMERCIAL','210',NOW(),NOW()),('OLD-ONLY','PUBLIC','SETTLE',888,'COMMERCIAL','220',NOW(),NOW())");
    try (var input = getClass().getResourceAsStream("/settlement/original-20261017.xls")) {
      byte[] bytes = input.readAllBytes(); var scope = new SettlementScope("COMMERCIAL", "2026-10");
      imports.confirm(bytes, "original.xls", scope, imports.preview(bytes, "original.xls", scope).fingerprint(), "finance");
    }
  }
  @AfterEach void removeFault() { jdbc.execute("DROP TRIGGER IF EXISTS stp_fail_srm_insert"); }
  private SrmFixedPricePublishService publisher() {
    return new SrmFixedPricePublishService(jdbc, new TransactionTemplate(transactions), 2, new BigDecimal("0.1"), 0);
  }
  private List<?> prices() { return jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id"); }
  private List<?> bases() { return jdbc.queryForList("SELECT * FROM lp_settlement_base_price ORDER BY id"); }

  @Test void actualSrmReplacementPreservesEverySettlementFieldAndRevisionAndLegacyHistory() {
    var prices = prices(); var bases = bases();
    var legacy = jdbc.queryForList("SELECT * FROM lp_price_fixed_item WHERE source_type IN ('SETTLE','SETTLE_FIXED') ORDER BY id");
    var result = publisher().publishIfReady(BATCH);
    assertThat(result.status()).isEqualTo(SrmFixedPricePublishService.Status.PUBLISHED);
    assertThat(result.insertedRows()).isEqualTo(2);
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_fixed_item WHERE material_code='OLD-PURCHASE'", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_price_fixed_item WHERE source_system='SRM'", Integer.class)).isEqualTo(2);
    assertThat(prices()).isEqualTo(prices); assertThat(bases()).isEqualTo(bases);
    assertThat(jdbc.queryForList("SELECT * FROM lp_price_fixed_item WHERE source_type IN ('SETTLE','SETTLE_FIXED') ORDER BY id")).isEqualTo(legacy);
    assertThat(publisher().publishIfReady(BATCH).status()).isEqualTo(SrmFixedPricePublishService.Status.SKIPPED);
  }

  @Test void failureAfterDeletingPurchaseSnapshotRollsBackWithoutTouchingSettlement() {
    var fixed = jdbc.queryForList("SELECT * FROM lp_price_fixed_item ORDER BY id"); var prices = prices(); var bases = bases();
    jdbc.execute("CREATE TRIGGER stp_fail_srm_insert BEFORE INSERT ON lp_price_fixed_item FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='STP injected insert failure'");
    assertThatThrownBy(() -> publisher().publishIfReady(BATCH)).hasMessageContaining("STP injected insert failure");
    assertThat(jdbc.queryForList("SELECT * FROM lp_price_fixed_item ORDER BY id")).isEqualTo(fixed);
    assertThat(prices()).isEqualTo(prices); assertThat(bases()).isEqualTo(bases);
  }

  @Test void legacyOnlyMaterialNeverBecomesCurrentAndSameMaterialUsesOnlyNewRuleEvenWhenLegacyIsEmpty() {
    for (String org : List.of("210", "220")) {
      assertThat(router.listCandidates("OLD-ONLY", "2026-11", LocalDate.of(2026,11,1), org, "COMMERCIAL")).isEmpty();
      var result = reader.resolve(new SettlementPriceRequest("COMMERCIAL", "809633", "2026-11", org, SettlementPriceType.SETTLE_FIXED));
      assertThat(result.unitPrice()).isEqualByComparingTo("4.1658");
      assertThat(result.evidence().inputSnapshotJson()).contains("lp_settlement_price");
    }
    jdbc.update("DELETE FROM lp_price_fixed_item WHERE source_type IN ('SETTLE','SETTLE_FIXED')");
    assertThat(reader.resolve(new SettlementPriceRequest("COMMERCIAL", "809633", "2026-11", "210", SettlementPriceType.SETTLE_FIXED)).unitPrice()).isEqualByComparingTo("4.1658");
  }
}
