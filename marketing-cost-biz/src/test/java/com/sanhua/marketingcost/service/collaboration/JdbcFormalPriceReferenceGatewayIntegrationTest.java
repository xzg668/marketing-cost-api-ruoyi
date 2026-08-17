package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@DisplayName("QCBP-14 正式有效价格只读搜索")
class JdbcFormalPriceReferenceGatewayIntegrationTest extends BomMapperTestBase {
  private static final String MARKER = "Q14-REF-";

  @Autowired private FormalPriceReferenceGateway gateway;
  @Autowired private JdbcTemplate jdbc;

  @BeforeAll
  static void ensureRangePriceSchema() throws Exception {
    try (var connection = MYSQL.createConnection(""); var statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE IF NOT EXISTS lp_price_range_item (
            id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
            org_code VARCHAR(64), source_name VARCHAR(128), supplier_name VARCHAR(255),
            supplier_code VARCHAR(64), purchase_class VARCHAR(64), material_name VARCHAR(128),
            material_code VARCHAR(64) NOT NULL, spec_model VARCHAR(128), unit VARCHAR(32),
            formula_expr TEXT, blank_weight DECIMAL(20,8), net_weight DECIMAL(20,8),
            process_fee DECIMAL(20,8), agent_fee DECIMAL(20,8),
            range_low DECIMAL(20,8) NOT NULL, range_high DECIMAL(20,8) NOT NULL,
            range_basis VARCHAR(16) NOT NULL DEFAULT 'QTY', factor_rule_id BIGINT,
            factor_code VARCHAR(32), import_batch_no VARCHAR(64), current_flag TINYINT NOT NULL DEFAULT 1,
            price_excl_tax DECIMAL(20,8), price_incl_tax DECIMAL(20,8), tax_included TINYINT NOT NULL DEFAULT 1,
            effective_from DATE NOT NULL, effective_to DATE, order_type VARCHAR(64), quota DECIMAL(20,8),
            business_unit_type VARCHAR(20), created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
            KEY idx_range_factor_rule (factor_rule_id),
            KEY idx_range_current (business_unit_type,material_code,factor_code,current_flag)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
    }
  }

  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM lp_price_variable_binding WHERE linked_item_id IN "
        + "(SELECT id FROM lp_price_linked_item WHERE material_code LIKE ?)", MARKER + "%");
    jdbc.update("DELETE FROM lp_price_fixed_item WHERE material_code LIKE ?", MARKER + "%");
    jdbc.update("DELETE FROM lp_price_linked_item WHERE material_code LIKE ?", MARKER + "%");
    jdbc.update("DELETE FROM lp_price_range_item WHERE material_code LIKE ?", MARKER + "%");
  }

  @Test
  void searchOnlyReturnsCurrentFormalRecordsInBusinessAndOrganizationScope() {
    LocalDateTime now = LocalDateTime.now();
    jdbc.update("""
        INSERT INTO lp_price_fixed_item
          (org_code, material_code, material_name, business_unit_type, spec_model, unit,
           fixed_price, tax_included, effective_from, effective_to, created_at, updated_at,
           source_type, pricing_month)
        VALUES
          ('210', ?, '当前固定价', 'COMMERCIAL', 'TP2-9.52', 'kg', 88.5, 1,
           '2026-01-01', NULL, ?, ?, 'PURCHASE', '2026-08'),
          ('220', ?, '其他组织', 'COMMERCIAL', 'TP2-9.52', 'kg', 99.5, 1,
           '2026-01-01', NULL, ?, ?, 'PURCHASE', '2026-08'),
          ('210', ?, '已经失效', 'COMMERCIAL', 'TP2-9.52', 'kg', 77.5, 1,
           '2025-01-01', '2025-12-31', ?, ?, 'PURCHASE', '2025-12'),
          ('210', ?, '其他业务单元', 'HOUSEHOLD', 'TP2-9.52', 'kg', 66.5, 1,
           '2026-01-01', NULL, ?, ?, 'PURCHASE', '2026-08')
        """, MARKER + "OK", now, now,
        MARKER + "OTHER-ORG", now, now,
        MARKER + "EXPIRED", now, now,
        MARKER + "OTHER-BU", now, now);

    assertThat(gateway.search("COMMERCIAL", "210", "2026-08", MARKER, "FIXED_PURCHASE"))
        .extracting(FormalPriceReference::materialCode)
        .containsExactly(MARKER + "OK");
  }

  @Test
  void copiedSnapshotDoesNotChangeWhenFormalRecordChangesLater() {
    LocalDateTime now = LocalDateTime.now();
    jdbc.update("""
        INSERT INTO lp_price_linked_item
          (pricing_month, org_code, material_code, material_name, business_unit_type,
           spec_model, unit, formula_expr, blank_weight, net_weight, tax_included,
           effective_from, effective_to, deleted, created_at, updated_at)
        VALUES ('2026-08', '210', ?, '联动参考', 'COMMERCIAL', 'TP2', 'kg',
                '[Cu]*[net_weight]', 1.2, 1.0, 1, '2026-01-01', NULL, 0, ?, ?)
        """, MARKER + "LINK", now, now);
    Long id = jdbc.queryForObject(
        "SELECT id FROM lp_price_linked_item WHERE material_code=?", Long.class, MARKER + "LINK");

    FormalPriceReference snapshot = gateway.findEffective(
        "COMMERCIAL", "210", "2026-08", "lp_price_linked_item", id).orElseThrow();
    jdbc.update("UPDATE lp_price_linked_item SET formula_expr='CHANGED' WHERE id=?", id);

    assertThat(snapshot.fields()).filteredOn(field -> "FORMULA_EXPR".equals(field.fieldCode()))
        .singleElement().extracting(FormalPriceReference.Field::value)
        .isEqualTo("[Cu]*[net_weight]");
    assertThat(gateway.findEffective(
        "COMMERCIAL", "210", "2026-08", "lp_price_linked_item", id).orElseThrow().fields())
        .filteredOn(field -> "FORMULA_EXPR".equals(field.fieldCode()))
        .singleElement().extracting(FormalPriceReference.Field::value)
        .isEqualTo("CHANGED");
  }

  @Test
  void copiedLinkedSnapshotIncludesCurrentRowLocalFactorBinding() {
    LocalDateTime now = LocalDateTime.now();
    jdbc.update("""
        INSERT INTO lp_price_linked_item
          (pricing_month, org_code, material_code, material_name, business_unit_type,
           spec_model, unit, formula_expr, net_weight, tax_included,
           effective_from, effective_to, deleted, created_at, updated_at)
        VALUES ('2026-08', '210', ?, '带行情绑定联动参考', 'COMMERCIAL', 'TP2', '件',
                '[__material]*[net_weight]', 10, 1, '2026-01-01', NULL, 0, ?, ?)
        """, MARKER + "BIND", now, now);
    Long id = jdbc.queryForObject(
        "SELECT id FROM lp_price_linked_item WHERE material_code=?", Long.class, MARKER + "BIND");
    jdbc.update("""
        INSERT INTO lp_price_variable_binding
          (linked_item_id, token_name, factor_code, price_source, bu_scoped,
           effective_date, source, deleted)
        VALUES (?, '材料含税价格', 'factor_identity_191', '平均价', 1,
                '2026-01-01', 'MANUAL', 0)
        """, id);

    FormalPriceReference snapshot = gateway.findEffective(
        "COMMERCIAL", "210", "2026-08", "lp_price_linked_item", id).orElseThrow();
    jdbc.update("UPDATE lp_price_variable_binding SET factor_code='factor_identity_192' "
        + "WHERE linked_item_id=?", id);

    assertThat(snapshot.fields()).filteredOn(field -> "BINDING".equals(field.sectionCode()))
        .filteredOn(field -> "FACTOR_CODE".equals(field.fieldCode()))
        .singleElement().extracting(FormalPriceReference.Field::value)
        .isEqualTo("factor_identity_191");
    assertThat(gateway.findEffective(
        "COMMERCIAL", "210", "2026-08", "lp_price_linked_item", id).orElseThrow().fields())
        .filteredOn(field -> "BINDING".equals(field.sectionCode()))
        .filteredOn(field -> "FACTOR_CODE".equals(field.fieldCode()))
        .singleElement().extracting(FormalPriceReference.Field::value)
        .isEqualTo("factor_identity_192");
  }

  @Test
  void fixedSearchHonorsBothCurrentAndLegacySourceTypesWithoutCrossingPriceKinds() {
    LocalDateTime now = LocalDateTime.now();
    jdbc.update("""
        INSERT INTO lp_price_fixed_item
          (org_code, material_code, material_name, business_unit_type, spec_model, unit,
           supplier_code, supplier_name, fixed_price, base_settle_price, tax_included, tax_rate,
           effective_from, effective_to, created_at, updated_at, source_type, pricing_month)
        VALUES
          ('210', ?, '新固定采购价', 'COMMERCIAL', 'FIXED', 'kg', 'SUP-1', '供应商1',
           10.1, NULL, 1, 0.13, '2026-01-01', NULL, ?, ?, 'PURCHASE_FIXED', '2026-08'),
          ('210', ?, '旧固定采购价', 'COMMERCIAL', 'FIXED', 'kg', 'SUP-1', '供应商1',
           10.2, NULL, 1, 0.13, '2026-01-01', NULL, ?, ?, 'PURCHASE', '2026-08'),
          ('210', ?, '新结算固定价', 'COMMERCIAL', 'SETTLE', 'kg', 'SUP-2', '供应商2',
           20.1, 20.1, 1, 0.13, '2026-01-01', NULL, ?, ?, 'SETTLE_FIXED', '2026-08'),
          ('210', ?, '旧结算固定价', 'COMMERCIAL', 'SETTLE', 'kg', 'SUP-2', '供应商2',
           20.2, 20.2, 1, 0.13, '2026-01-01', NULL, ?, ?, 'SETTLE', '2026-08')
        """, MARKER + "PF-NEW", now, now, MARKER + "PF-OLD", now, now,
        MARKER + "SF-NEW", now, now, MARKER + "SF-OLD", now, now);

    List<FormalPriceReference> purchase = gateway.search(
        "COMMERCIAL", "210", "2026-08", MARKER, "FIXED_PURCHASE");
    List<FormalPriceReference> settle = gateway.search(
        "COMMERCIAL", "210", "2026-08", MARKER, "SETTLE_FIXED");

    assertThat(purchase).extracting(FormalPriceReference::materialCode)
        .containsExactlyInAnyOrder(MARKER + "PF-NEW", MARKER + "PF-OLD");
    assertThat(purchase).extracting(FormalPriceReference::priceType)
        .containsOnly("FIXED_PURCHASE");
    assertThat(settle).extracting(FormalPriceReference::materialCode)
        .containsExactlyInAnyOrder(MARKER + "SF-NEW", MARKER + "SF-OLD");
    assertThat(settle).extracting(FormalPriceReference::priceType)
        .containsOnly("SETTLE_FIXED");
    assertThat(settle).flatExtracting(FormalPriceReference::fields)
        .filteredOn(field -> field.required())
        .extracting(FormalPriceReference.Field::fieldCode)
        .containsOnly("BASE_SETTLE_PRICE");
  }

  @Test
  void findEffectiveRejectsAnotherOrganizationEvenWhenSourceIdExists() {
    LocalDateTime now = LocalDateTime.now();
    jdbc.update("""
        INSERT INTO lp_price_fixed_item
          (org_code, material_code, material_name, business_unit_type, spec_model, unit,
           fixed_price, tax_included, effective_from, effective_to, created_at, updated_at,
           source_type, pricing_month)
        VALUES ('220', ?, '其他组织参考', 'COMMERCIAL', 'TP2', 'kg', 9.9, 1,
                '2026-01-01', NULL, ?, ?, 'PURCHASE_FIXED', '2026-08')
        """, MARKER + "ORG-220", now, now);
    Long id = jdbc.queryForObject(
        "SELECT id FROM lp_price_fixed_item WHERE material_code=?", Long.class,
        MARKER + "ORG-220");

    assertThat(gateway.findEffective(
        "COMMERCIAL", "210", "2026-08", "lp_price_fixed_item", id)).isEmpty();
  }

  @Test
  void rangeReferenceReturnsTheWholeFormalGroupAsOneReadOnlySnapshot() {
    LocalDateTime now = LocalDateTime.now();
    jdbc.update("""
        INSERT INTO lp_price_range_item
          (business_unit_type, org_code, supplier_code, supplier_name, material_code,
           material_name, spec_model, unit, range_low, range_high, range_basis,
           current_flag, price_excl_tax, price_incl_tax, tax_included,
           effective_from, effective_to, created_at, updated_at)
        VALUES
          ('COMMERCIAL', '210', 'SUP-Q17', '区间供应商', ?, '区间参考', 'TP2', '件',
           0, 10, 'QTY', 1, 8, 9.04, 1, '2026-01-01', NULL, ?, ?),
          ('COMMERCIAL', '210', 'SUP-Q17', '区间供应商', ?, '区间参考', 'TP2', '件',
           10, 999999999999.999999, 'QTY', 1, 9, 10.17, 1, '2026-01-01', NULL, ?, ?)
        """, MARKER + "RANGE", now, now, MARKER + "RANGE", now, now);
    List<FormalPriceReference> found = gateway.search(
        "COMMERCIAL", "210", "2026-08", MARKER + "RANGE", "RANGE");

    assertThat(found).singleElement().satisfies(reference -> {
      assertThat(reference.priceSummary()).contains("2段区间");
      assertThat(reference.fields()).filteredOn(field -> "RANGE_ROW".equals(field.sectionCode()))
          .hasSize(8);
      assertThat(reference.fields()).filteredOn(field -> "RANGE_LOW".equals(field.fieldCode()))
          .extracting(FormalPriceReference.Field::value).containsExactly("0", "10");
    });

    Long sourceId = found.get(0).sourceId();
    FormalPriceReference snapshot = gateway.findEffective(
        "COMMERCIAL", "210", "2026-08", "lp_price_range_item", sourceId).orElseThrow();
    jdbc.update("UPDATE lp_price_range_item SET price_excl_tax=99 WHERE material_code=?",
        MARKER + "RANGE");
    assertThat(snapshot.fields()).filteredOn(field -> "PRICE_EXCL_TAX".equals(field.fieldCode()))
        .extracting(FormalPriceReference.Field::value).containsExactly("8", "9");
  }
}
