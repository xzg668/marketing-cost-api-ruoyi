package com.sanhua.marketingcost.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sanhua.marketingcost.entity.OaFormExtraFee;
import com.sanhua.marketingcost.entity.OaFormExtraField;
import com.sanhua.marketingcost.entity.OaFormHeaderExtraField;
import com.sanhua.marketingcost.entity.OaFormItemExtraField;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteIngestLog;
import com.sanhua.marketingcost.entity.QuoteIngestTypeRule;
import com.sanhua.marketingcost.entity.QuoteWritebackTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class QuoteIngestSchemaModelTest {

  @Test
  void quoteIngestEntitiesUseExpectedTables() {
    assertThat(tableName(QuoteIngestLog.class)).isEqualTo("lp_quote_ingest_log");
    assertThat(tableName(QuoteIngestTypeRule.class)).isEqualTo("lp_quote_ingest_type_rule");
    assertThat(tableName(OaFormExtraField.class)).isEqualTo("lp_oa_form_extra_field");
    assertThat(tableName(OaFormHeaderExtraField.class)).isEqualTo("lp_oa_form_header_extra_field");
    assertThat(tableName(OaFormItemExtraField.class)).isEqualTo("lp_oa_form_item_extra_field");
    assertThat(tableName(OaFormExtraFee.class)).isEqualTo("lp_oa_form_extra_fee");
    assertThat(tableName(QuoteBomStatus.class)).isEqualTo("lp_quote_bom_status");
    assertThat(tableName(QuoteWritebackTask.class)).isEqualTo("lp_quote_writeback_task");
  }

  @Test
  void quoteBomStatusModelContainsMonthlyReuseTraceFields() {
    QuoteBomStatus status = new QuoteBomStatus();
    LocalDateTime syncAt = LocalDateTime.of(2026, 5, 27, 20, 0);

    status.setCostPeriodMonth("2026-05");
    status.setSyncRecordId(10L);
    status.setReusedFromRecordId(9L);
    status.setSyncAt(syncAt);

    assertThat(status.getCostPeriodMonth()).isEqualTo("2026-05");
    assertThat(status.getSyncRecordId()).isEqualTo(10L);
    assertThat(status.getReusedFromRecordId()).isEqualTo(9L);
    assertThat(status.getSyncAt()).isEqualTo(syncAt);
  }

  @Test
  void v121AddsOaOriginalFormExcelModelWithoutMixingHeaderAndItemExtras() throws IOException {
    String sql =
        Files.readString(
            Path.of("src/main/resources/db/V121__quote_oa_form_excel_model.sql"),
            StandardCharsets.UTF_8);

    assertThat(sql).contains("CREATE TABLE IF NOT EXISTS lp_oa_form_header_extra_field");
    assertThat(sql).contains("CREATE TABLE IF NOT EXISTS lp_oa_form_item_extra_field");
    assertThat(sql).contains("accounting_period_month");
    assertThat(sql).contains("expense_product_category");
    assertThat(sql).contains("applicant_unit");
    assertThat(sql).contains("source_company");
    assertThat(sql).contains("source_business_division");
    assertThat(sql).contains("fee_scope");
    assertThat(sql).contains("旧 lp_oa_form_extra_field 暂保留兼容");
    assertThat(sql).doesNotContain("DROP TABLE IF EXISTS lp_oa_form_extra_field");
  }

  @Test
  void v123AddsFiSc020CostDetailItemFields() throws IOException {
    String sql =
        Files.readString(
            Path.of("src/main/resources/db/V123__quote_oa_form_item_cost_detail_fields.sql"),
            StandardCharsets.UTF_8);

    assertThat(sql).contains("valid_month");
    assertThat(sql).contains("sus304_weight_g");
    assertThat(sql).contains("sus316_weight_g");
    assertThat(sql).contains("copper_weight_g");
    assertThat(sql).contains("v123_add_column_if_not_exists");
  }

  @Test
  void migrationKeepsHistoricTablesAndGuardsCalcAt() throws IOException {
    String sql =
        Files.readString(
            Path.of("src/main/resources/db/V59__quote_ingest_schema.sql"),
            StandardCharsets.UTF_8);

    assertThat(sql).contains("_quote_add_column_if_not_exists");
    assertThat(sql).contains("CALL _quote_add_column_if_not_exists('oa_form', 'calc_at'");
    assertThat(sql).contains("CREATE TABLE IF NOT EXISTS lp_quote_ingest_log");
    assertThat(sql).contains("CREATE TABLE IF NOT EXISTS lp_quote_bom_status");
    assertThat(sql).contains("UPDATE oa_form\nSET source_type = 'LEGACY'");
    assertThat(sql).doesNotContain("DROP TABLE IF EXISTS `oa_form`");
    assertThat(sql).doesNotContain("DROP TABLE IF EXISTS oa_form");
    assertThat(sql).doesNotContain("TRUNCATE TABLE oa_form");
  }

  private static String tableName(Class<?> entityClass) {
    return entityClass.getAnnotation(TableName.class).value();
  }
}
