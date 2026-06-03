package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V152 供应商供货比例去重键调整")
class V152SupplierSupplyRatioDedupeKeySqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("重建唯一键为业务单元 + 物料代码 + 供应商 + deleted")
  void rebuildsUniqueKeyToMaterialAndSupplier() {
    assertThat(SQL).contains(
        "DROP INDEX `', p_index_name, '`",
        "ADD UNIQUE KEY `uk_supplier_ratio_biz`",
        "`business_unit_type`, `material_code`, `supplier_name`, `deleted`");
  }

  @Test
  @DisplayName("DDL 使用 information_schema 做幂等判断")
  void isIdempotent() {
    assertThat(SQL).contains(
        "information_schema.statistics",
        "v152_drop_index_if_exists",
        "v152_add_index_if_not_exists");
  }

  @Test
  @DisplayName("型号不参与新去重键，允许空字符串默认值")
  void specModelHasBlankDefault() {
    assertThat(SQL).contains("MODIFY COLUMN `spec_model` VARCHAR(255) NOT NULL DEFAULT ''");
  }

  private static String readSql() {
    try (var in = V152SupplierSupplyRatioDedupeKeySqlTest.class.getResourceAsStream(
        "/db/V152__supplier_supply_ratio_dedupe_key.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V152 SQL 失败", e);
    }
  }
}
