package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V183 部品取价结果组织传递")
class V183CostRunPartItemOrgScopeSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("lp_cost_run_part_item 补 U9 组织和主档组织字段")
  void addsPartItemOrganizationColumns() {
    assertThat(SQL).contains(
        "'lp_cost_run_part_item'",
        "'price_org_code'",
        "'material_organization_code'",
        "U9报价组织：210=商用，220=板换",
        "料品主档组织：COMMERCIAL=商用，PLATE=板换");
  }

  @Test
  @DisplayName("组织字段只做传递，不对历史取价结果猜测回填")
  void doesNotGuessHistoricalOrganization() {
    assertThat(SQL)
        .contains("历史行不做业务猜测回填")
        .doesNotContain("UPDATE lp_cost_run_part_item")
        .doesNotContain("SET price_org_code = '210'")
        .doesNotContain("SET material_organization_code = 'COMMERCIAL'");
  }

  @Test
  @DisplayName("部品结果组织索引覆盖 OA 产品聚合范围")
  void addsOrgScopeIndex() {
    assertThat(SQL).contains(
        "idx_cost_run_part_org_scope",
        "price_org_code, material_organization_code, oa_no, product_code");
  }

  private static String readSql() {
    try (var in = V183CostRunPartItemOrgScopeSqlTest.class.getResourceAsStream(
        "/db/V183__cost_run_part_item_org_scope.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V183 SQL 失败", e);
    }
  }
}
