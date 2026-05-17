package com.sanhua.marketingcost.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class QuoteIngestSeedSqlTest {

  @Test
  void v60SeedsExpectedMenusPermissionsAndDictionaries() throws IOException {
    String sql =
        Files.readString(
            Path.of("src/main/resources/db/V60__quote_ingest_enums_menu_seed.sql"),
            StandardCharsets.UTF_8);

    assertThat(sql).contains("quote_source_type");
    assertThat(sql).contains("quote_scenario");
    assertThat(sql).contains("quote_ingest_status");
    assertThat(sql).contains("quote_classification_status");
    assertThat(sql).contains("quote_bom_status");
    assertThat(sql).contains("quote_fee_category");
    assertThat(sql).contains("quote_writeback_status");

    assertThat(sql).contains("'报价单导入'");
    assertThat(sql).contains("'报价单接入'");
    assertThat(sql).contains("'报价单产品 BOM 处理'");
    assertThat(sql).contains("'接入流水'");

    assertThat(count(sql, "'ingest:quote:list'")).isEqualTo(1);
    assertThat(count(sql, "'ingest:quote:import'")).isEqualTo(1);
    assertThat(count(sql, "'ingest:quote:confirm'")).isEqualTo(1);
    assertThat(count(sql, "'ingest:quote:bom-check'")).isEqualTo(1);
    assertThat(count(sql, "'ingest:quote:raw'")).isEqualTo(1);
    assertThat(count(sql, "'ingest:quote:mock-create'")).isEqualTo(1);
    assertThat(count(sql, "'ingest:quote-product-bom:list'")).isEqualTo(1);
    assertThat(count(sql, "'ingest:quote-log:list'")).isEqualTo(1);

    assertThat(sql).contains("(1, 2064)");
    assertThat(sql).contains("(1, 208)");
    assertThat(sql).contains("(10, 208)");
    assertThat(sql).contains("(11, 208)");
    assertThat(sql).doesNotContain("(10, 2064)");
    assertThat(sql).doesNotContain("(11, 2064)");
    assertThat(sql).doesNotContain("DELETE FROM sys_menu");
    assertThat(sql).doesNotContain("TRUNCATE TABLE sys_menu");
  }

  private static int count(String text, String needle) {
    int count = 0;
    int index = 0;
    while ((index = text.indexOf(needle, index)) >= 0) {
      count++;
      index += needle.length();
    }
    return count;
  }
}
