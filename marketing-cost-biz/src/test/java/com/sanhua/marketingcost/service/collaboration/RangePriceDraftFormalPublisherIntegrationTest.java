package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
@DisplayName("QCBP-17 区间价整组原子发布")
class RangePriceDraftFormalPublisherIntegrationTest extends BomMapperTestBase {
  private static final String MATERIAL = "Q17-PUBLISH";

  @Autowired private RangePriceDraftFormalPublisher publisher;
  @Autowired private JdbcTemplate jdbc;
  @Autowired private ObjectMapper objectMapper;

  @BeforeAll
  static void schema() throws Exception {
    try (var connection = MYSQL.createConnection(""); var statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE IF NOT EXISTS lp_price_range_item (
            id BIGINT AUTO_INCREMENT PRIMARY KEY, org_code VARCHAR(64), source_name VARCHAR(128),
            supplier_name VARCHAR(255), supplier_code VARCHAR(64), material_name VARCHAR(128),
            material_code VARCHAR(64) NOT NULL, spec_model VARCHAR(128), unit VARCHAR(32),
            range_low DECIMAL(20,8) NOT NULL, range_high DECIMAL(20,8) NOT NULL,
            range_basis VARCHAR(16) NOT NULL DEFAULT 'QTY', factor_rule_id BIGINT, factor_code VARCHAR(32),
            import_batch_no VARCHAR(64), current_flag TINYINT NOT NULL DEFAULT 1,
            price_excl_tax DECIMAL(20,8), price_incl_tax DECIMAL(20,8), tax_included TINYINT NOT NULL,
            effective_from DATE NOT NULL, effective_to DATE, business_unit_type VARCHAR(20),
            created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL,
            CONSTRAINT ck_q17_atomic_price CHECK (price_excl_tax IS NULL OR price_excl_tax < 1000000)
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
      try (var check = statement.executeQuery("""
          SELECT COUNT(*) FROM information_schema.table_constraints
          WHERE constraint_schema=DATABASE() AND table_name='lp_price_range_item'
            AND constraint_name='ck_q17_atomic_price'
          """)) {
        check.next();
        if (check.getInt(1) == 0) {
          statement.execute("""
              ALTER TABLE lp_price_range_item ADD CONSTRAINT ck_q17_atomic_price
              CHECK (price_excl_tax IS NULL OR price_excl_tax < 1000000)
              """);
        }
      }
      statement.execute("""
          CREATE TABLE IF NOT EXISTS lp_price_range_factor_rule (
            id BIGINT AUTO_INCREMENT PRIMARY KEY, business_unit_type VARCHAR(20),
            material_code VARCHAR(64) NOT NULL, material_name VARCHAR(128), spec_model VARCHAR(128),
            factor_code VARCHAR(32) NOT NULL, version_no INT NOT NULL DEFAULT 1,
            import_batch_no VARCHAR(64) NOT NULL, effective_from DATE NOT NULL, effective_to DATE,
            current_flag TINYINT NOT NULL DEFAULT 1, created_at DATETIME NOT NULL, updated_at DATETIME NOT NULL
          ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
          """);
    }
  }

  @AfterEach
  void cleanup() {
    jdbc.update("DELETE FROM lp_price_range_item WHERE material_code=?", MATERIAL);
    jdbc.update("DELETE FROM lp_price_range_factor_rule WHERE material_code=?", MATERIAL);
  }

  @Test
  void publishesEveryRangeAndKeepsReferenceRowsUntouched() {
    jdbc.update("""
        INSERT INTO lp_price_range_item
          (business_unit_type,org_code,material_code,range_low,range_high,range_basis,current_flag,
           price_excl_tax,tax_included,effective_from,created_at,updated_at)
        VALUES ('COMMERCIAL','210','Q17-REFERENCE',0,10,'QTY',1,7,1,'2026-01-01',NOW(),NOW())
        """);
    var result = publisher.publish(draft(), fields("8", "9"), "Q17-BATCH-OK");
    assertThat(result.sourceIds()).hasSize(2);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_price_range_item WHERE material_code=? AND current_flag=1",
        Integer.class, MATERIAL)).isEqualTo(2);
    assertThat(jdbc.queryForObject(
        "SELECT price_excl_tax FROM lp_price_range_item WHERE material_code='Q17-REFERENCE'",
        BigDecimal.class)).isEqualByComparingTo("7");
  }

  @Test
  void failureOnSecondRowRollsBackTheWholeGroup() {
    assertThatThrownBy(() -> publisher.publish(
        draft(), fields("8", "1000000"), "Q17-BATCH-ROLLBACK"))
        .isInstanceOf(DataAccessException.class);
    assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM lp_price_range_item WHERE material_code=?",
        Integer.class, MATERIAL)).isZero();
  }

  private QuotePriceDraft draft() {
    QuotePriceDraft draft = new QuotePriceDraft();
    draft.setMaterialCode(MATERIAL);
    draft.setMaterialName("区间发布目标");
    draft.setMaterialSpec("TP2");
    draft.setBusinessUnitType("COMMERCIAL");
    draft.setOrgCode("210");
    draft.setPriceType("RANGE");
    draft.setDraftStatus("APPROVED");
    draft.setValidationStatus("PASSED");
    draft.setSupplierCode("SUP-Q17");
    draft.setUnit("件");
    draft.setTaxIncluded(1);
    draft.setEffectiveFrom(LocalDate.of(2026, 8, 1));
    return draft;
  }

  private List<QuotePriceDraftField> fields(String firstPrice, String secondPrice) {
    List<QuotePriceDraftField> fields = new ArrayList<>();
    fields.add(field("COMMON", "COMMON", "RANGE_BASIS", "QTY"));
    fields.add(field("RANGE_ROW", "1", "RANGE_LOW", "0"));
    fields.add(field("RANGE_ROW", "1", "RANGE_HIGH", "10"));
    fields.add(field("RANGE_ROW", "1", "PRICE_EXCL_TAX", firstPrice));
    fields.add(field("RANGE_ROW", "1", "PRICE_INCL_TAX", "9.04"));
    fields.add(field("RANGE_ROW", "2", "RANGE_LOW", "10"));
    fields.add(field("RANGE_ROW", "2", "RANGE_HIGH", null));
    fields.add(field("RANGE_ROW", "2", "PRICE_EXCL_TAX", secondPrice));
    fields.add(field("RANGE_ROW", "2", "PRICE_INCL_TAX", "10.17"));
    return fields;
  }

  private QuotePriceDraftField field(String section, String row, String code, String value) {
    QuotePriceDraftField field = new QuotePriceDraftField();
    field.setSectionCode(section);
    field.setRowKey(row);
    field.setFieldCode(code);
    try {
      field.setTargetValueJson(value == null ? null : objectMapper.writeValueAsString(value));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
    return field;
  }
}
