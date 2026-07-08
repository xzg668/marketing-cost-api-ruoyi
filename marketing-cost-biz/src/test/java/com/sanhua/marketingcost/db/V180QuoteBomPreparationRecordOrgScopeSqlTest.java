package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V180 报价 BOM 准备记录组织隔离")
class V180QuoteBomPreparationRecordOrgScopeSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("准备记录补 BOM 组织和主档组织字段且不默认商用")
  void addsOrganizationColumns() {
    assertThat(SQL).contains(
        "CALL v180_add_column_if_not_exists(",
        "'lp_quote_bom_preparation_record'",
        "'price_org_code'",
        "price_org_code VARCHAR(32) DEFAULT NULL",
        "'material_organization_code'",
        "material_organization_code VARCHAR(32) DEFAULT NULL");
    assertThat(SQL)
        .doesNotContain("DEFAULT ''210''")
        .doesNotContain("DEFAULT ''COMMERCIAL''")
        .doesNotContain("SET price_org_code = ''210''")
        .doesNotContain("SET material_organization_code = ''COMMERCIAL''");
  }

  @Test
  @DisplayName("同月锁定复用索引包含组织字段")
  void monthlyLockIndexIncludesOrganization() {
    assertThat(SQL).contains(
        "idx_qbp_record_org_month_lock",
        "KEY idx_qbp_record_org_month_lock (quote_product_code, cost_period_month, price_org_code, material_organization_code, active_flag, preparation_status)");
  }

  @Test
  @DisplayName("迁移幂等且不清空准备记录")
  void isIdempotentAndDoesNotClearData() {
    assertThat(SQL)
        .contains(
            "CREATE PROCEDURE v180_add_column_if_not_exists",
            "CREATE PROCEDURE v180_modify_column_if_exists",
            "CREATE PROCEDURE v180_add_index_if_not_exists",
            "information_schema.COLUMNS",
            "information_schema.STATISTICS")
        .doesNotContain("DROP TABLE lp_quote_bom_preparation_record")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM lp_quote_bom_preparation_record");
  }

  private static String readSql() {
    try (var in = V180QuoteBomPreparationRecordOrgScopeSqlTest.class.getResourceAsStream(
        "/db/V180__quote_bom_preparation_record_org_scope.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V180 SQL 失败", e);
    }
  }
}
