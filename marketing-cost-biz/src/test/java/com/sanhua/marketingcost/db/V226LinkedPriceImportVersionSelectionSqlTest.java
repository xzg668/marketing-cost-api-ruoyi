package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V226 联动公式按导入版本选择")
class V226LinkedPriceImportVersionSelectionSqlTest {

  @Test
  @DisplayName("移除生效日期唯一键并增加导入版本查询索引")
  void replacesEffectiveDateVersionIndexes() {
    String sql = migrationSql();

    assertThat(sql)
        .contains("v226_drop_index_if_exists('lp_price_linked_item', 'uk_linked_formula_version')")
        .contains("v226_drop_index_if_exists('lp_price_linked_item', 'idx_linked_current_version_lookup')")
        .contains("idx_linked_import_version_lookup")
        .contains("`business_unit_type`, `material_code`, `pricing_month`")
        .contains("`supplier_code`, `created_at`, `deleted`");
  }

  private String migrationSql() {
    try (var input = getClass().getResourceAsStream(
        "/db/V226__linked_price_import_version_selection.sql")) {
      if (input == null) {
        throw new IllegalStateException("未找到 V226 迁移脚本");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("读取 V226 迁移脚本失败", exception);
    }
  }
}
