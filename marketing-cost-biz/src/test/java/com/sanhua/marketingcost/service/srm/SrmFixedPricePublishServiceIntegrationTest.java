package com.sanhua.marketingcost.service.srm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.srm.SrmFixedPricePublishService.PublishResult;
import com.sanhua.marketingcost.service.srm.SrmFixedPricePublishService.Status;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@Tag("integration")
@Transactional
@TestPropertySource(properties = {
    "srm.fixed-price.enabled=false",
    "srm.fixed-price.minimum-valid-rows=4",
    "srm.fixed-price.maximum-change-ratio=0.10",
    "srm.fixed-price.quiet-period-minutes=0"
})
class SrmFixedPricePublishServiceIntegrationTest extends BomMapperTestBase {

  private static final LocalDate PREVIOUS_BATCH = LocalDate.of(2099, 1, 1);
  private static final LocalDate CURRENT_BATCH = LocalDate.of(2099, 1, 2);

  @Autowired
  private SrmFixedPricePublishService publishService;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @BeforeAll
  static void createRawTable() throws Exception {
    try (Connection connection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE IF NOT EXISTS lp_price_fixed_item_srm_raw (
              id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
              company VARCHAR(255),
              material_code VARCHAR(64),
              material_name VARCHAR(255),
              spec VARCHAR(255),
              sup_code VARCHAR(64),
              sup_name VARCHAR(255),
              unit VARCHAR(32),
              price DECIMAL(32,8),
              eff_date VARCHAR(32),
              exp_date VARCHAR(32),
              `source` VARCHAR(255),
              dt VARCHAR(10) NOT NULL,
              synced_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY (id),
              KEY idx_srm_raw_dt (dt)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
    }
  }

  @BeforeEach
  void prepareRows() {
    jdbcTemplate.update(
        "DELETE FROM lp_price_fixed_item_srm_raw WHERE dt IN (?, ?)",
        PREVIOUS_BATCH.toString(),
        CURRENT_BATCH.toString());

    insertRaw(PREVIOUS_BATCH, "浙江三花商用制冷有限公司", "MAT-A", "SUP-1", "10", "规格A");
    insertRaw(PREVIOUS_BATCH, "浙江三花商用制冷有限公司", "MAT-A", "SUP-1", "12", "规格A");
    insertRaw(PREVIOUS_BATCH, "浙江三花商用制冷有限公司", "MAT-B", "SUP-2", "20", "规格B");
    insertRaw(PREVIOUS_BATCH, "浙江三花板换科技有限公司", "MAT-C", "SUP-3", "30", "规格C");

    insertRaw(CURRENT_BATCH, "浙江三花商用制冷有限公司", "MAT-A", "SUP-1", "10", "规格A");
    insertRaw(
        CURRENT_BATCH,
        "浙江三花商用制冷有限公司",
        "MAT-A",
        "SUP-1",
        "12",
        "超过六十四个字符的规格用于验证正式表长度映射ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789");
    insertRaw(CURRENT_BATCH, "浙江三花商用制冷有限公司", "MAT-B", "SUP-2", "20", "规格B");
    insertRaw(CURRENT_BATCH, "浙江三花板换科技有限公司", "MAT-C", "SUP-3", "30", "规格C");
    insertRaw(CURRENT_BATCH, "浙江三花商用制冷有限公司", "", "SUP-X", "99", "无物料编码");

    jdbcTemplate.update("""
        INSERT INTO lp_price_fixed_item (
            material_code, fixed_price, source_kind, source_type, source_system,
            business_unit_type, created_at, updated_at)
        VALUES ('OLD-PURCHASE', 1, 'PUBLIC', 'PURCHASE_FIXED', 'EXCEL',
                'COMMERCIAL', NOW(), NOW())
        """);
    jdbcTemplate.update("""
        INSERT INTO lp_price_fixed_item (
            material_code, fixed_price, source_kind, source_type, source_system,
            business_unit_type, created_at, updated_at)
        VALUES ('KEEP-SETTLE', 2, 'PUBLIC', 'SETTLE_FIXED', 'EXCEL',
                'COMMERCIAL', NOW(), NOW())
        """);
    jdbcTemplate.update("""
        INSERT INTO lp_price_fixed_item (
            material_code, fixed_price, source_kind, source_type, source_system,
            business_unit_type, created_at, updated_at)
        VALUES ('KEEP-TECH', 3, 'TECH_SUPPLEMENTAL', 'PURCHASE_FIXED', 'MANUAL',
                'COMMERCIAL', NOW(), NOW())
        """);
  }

  @Test
  void publishesFullPurchaseSnapshotAndKeepsSettlementRows() {
    PublishResult result = publishService.publishIfReady(CURRENT_BATCH);

    assertThat(result.status()).isEqualTo(Status.PUBLISHED);
    assertThat(result.rawRows()).isEqualTo(5);
    assertThat(result.validRows()).isEqualTo(4);
    assertThat(result.skippedRows()).isEqualTo(1);
    assertThat(result.insertedRows()).isEqualTo(3);

    assertThat(count("source_kind='PUBLIC' AND source_type IN ('PURCHASE','PURCHASE_FIXED') "
        + "AND COALESCE(NULLIF(TRIM(business_unit_type),''),'COMMERCIAL')='COMMERCIAL'"))
        .isEqualTo(3);
    assertThat(count("material_code='OLD-PURCHASE'")).isZero();
    assertThat(count("material_code='KEEP-SETTLE' AND source_type='SETTLE_FIXED'")).isEqualTo(1);
    assertThat(count("material_code='KEEP-TECH' AND source_kind='TECH_SUPPLEMENTAL'")).isEqualTo(1);
    assertThat(count("source_system='SRM' AND source_batch_no='2099-01-02'"))
        .isEqualTo(3);
    assertThat(jdbcTemplate.queryForObject(
        "SELECT fixed_price FROM lp_price_fixed_item WHERE material_code='MAT-A'",
        java.math.BigDecimal.class)).isEqualByComparingTo("12");
    assertThat(jdbcTemplate.queryForObject(
        "SELECT CHAR_LENGTH(spec_model) FROM lp_price_fixed_item WHERE material_code='MAT-A'",
        Integer.class)).isLessThanOrEqualTo(64);

    PublishResult repeated = publishService.publishIfReady(CURRENT_BATCH);
    assertThat(repeated.status()).isEqualTo(Status.SKIPPED);
    assertThat(count("source_system='SRM' AND source_batch_no='2099-01-02'"))
        .isEqualTo(3);
  }

  @Test
  void rejectsBatchWhenDayOverDayCountChangesMoreThanConfiguredRatio() {
    insertRaw(CURRENT_BATCH, "浙江三花商用制冷有限公司", "MAT-D", "SUP-4", "40", "规格D");
    long before = count("material_code='OLD-PURCHASE'");

    PublishResult result = publishService.publishIfReady(CURRENT_BATCH);

    assertThat(result.status()).isEqualTo(Status.REJECTED);
    assertThat(result.message()).contains("总数", "超过允许的10%");
    assertThat(count("material_code='OLD-PURCHASE'")).isEqualTo(before);
    assertThat(count("source_system='SRM' AND source_batch_no='2099-01-02'"))
        .isZero();
  }

  private void insertRaw(
      LocalDate batchDate,
      String company,
      String materialCode,
      String supplierCode,
      String price,
      String spec) {
    jdbcTemplate.update("""
        INSERT INTO lp_price_fixed_item_srm_raw (
            company, material_code, material_name, spec, sup_code, sup_name,
            unit, price, eff_date, exp_date, `source`, dt, synced_at)
        VALUES (?, ?, ?, ?, ?, ?, '只', ?, '2099-01-01', '2099-12-31',
                '固定价TEST', ?, NOW() - INTERVAL 1 HOUR)
        """,
        company,
        materialCode,
        "物料" + materialCode,
        spec,
        supplierCode,
        "供应商" + supplierCode,
        price,
        batchDate.toString());
  }

  private long count(String whereClause) {
    Long value = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM lp_price_fixed_item WHERE " + whereClause,
        Long.class);
    return value == null ? 0 : value;
  }
}
