package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V182 BOM 成本行组织传递")
class V182BomCostingRowOrgScopeSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("lp_bom_costing_row 补 U9 组织和主档组织字段")
  void addsCostingRowOrganizationColumns() {
    assertThat(SQL).contains(
        "'lp_bom_costing_row'",
        "'price_org_code'",
        "'material_organization_code'",
        "U9报价组织：210=商用，220=板换",
        "料品主档组织：COMMERCIAL=商用，PLATE=板换");
  }

  @Test
  @DisplayName("组织字段只用于传递，不做历史业务猜测回填")
  void doesNotGuessHistoricalOrganization() {
    assertThat(SQL)
        .contains("历史行不做业务猜测回填")
        .doesNotContain("UPDATE lp_bom_costing_row")
        .doesNotContain("SET price_org_code = '210'")
        .doesNotContain("SET material_organization_code = 'COMMERCIAL'");
  }

  @Test
  @DisplayName("成本行组织索引覆盖报价产品行范围")
  void addsOrgScopeIndex() {
    assertThat(SQL).contains(
        "idx_bom_costing_org_scope",
        "price_org_code, material_organization_code, oa_no, oa_form_item_id, top_product_code, period_month");
  }

  private static String readSql() {
    try (var in = V182BomCostingRowOrgScopeSqlTest.class.getResourceAsStream(
        "/db/V182__bom_costing_row_org_scope.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V182 SQL 失败", e);
    }
  }
}
