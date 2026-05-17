package com.sanhua.marketingcost.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableName;
import com.sanhua.marketingcost.entity.OaFormExtraFee;
import com.sanhua.marketingcost.entity.OaFormExtraField;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteIngestLog;
import com.sanhua.marketingcost.entity.QuoteIngestTypeRule;
import com.sanhua.marketingcost.entity.QuoteWritebackTask;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class QuoteIngestSchemaModelTest {

  @Test
  void quoteIngestEntitiesUseExpectedTables() {
    assertThat(tableName(QuoteIngestLog.class)).isEqualTo("lp_quote_ingest_log");
    assertThat(tableName(QuoteIngestTypeRule.class)).isEqualTo("lp_quote_ingest_type_rule");
    assertThat(tableName(OaFormExtraField.class)).isEqualTo("lp_oa_form_extra_field");
    assertThat(tableName(OaFormExtraFee.class)).isEqualTo("lp_oa_form_extra_fee");
    assertThat(tableName(QuoteBomStatus.class)).isEqualTo("lp_quote_bom_status");
    assertThat(tableName(QuoteWritebackTask.class)).isEqualTo("lp_quote_writeback_task");
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
