package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V196AuxiliaryMaterialExclusionRulesSqlTest {

  @Test
  @DisplayName("V196复用结算规则表保存财务排除清单和两个保留例外")
  void migrationUsesExistingRuleTableAndFormalMasterFields() throws Exception {
    String sql;
    try (InputStream input = getClass().getResourceAsStream(
        "/db/V196__auxiliary_material_exclusion_rules.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sql).contains(
        "AUXILIARY_EXCLUDE_FINANCE_MAIN_CATEGORIES",
        "AUXILIARY_EXCLUDE_PLASTIC_EXCEPT_KEEP",
        "AUXILIARY_EXCLUDE_ADHESIVE_AUX_EXCEPT_PACKAGE",
        "main_category_code",
        "purchase_category",
        "NOT_IN",
        "其它包装材料",
        "181841442",
        "181851445",
        "181811435",
        "181851454",
        "181841443",
        "181861452",
        "171721414",
        "171721412",
        "181841444",
        "181861453",
        "181831498",
        "171741425");
    assertThat(sql.toUpperCase()).doesNotContain("CREATE TABLE", "DROP TABLE");
    assertThat(sql).doesNotContain("311034930");
  }
}
