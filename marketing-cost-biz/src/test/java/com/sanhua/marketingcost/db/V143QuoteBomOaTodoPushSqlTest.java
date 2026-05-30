package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V143 报价产品 BOM 准备真实 OA 待办字段")
class V143QuoteBomOaTodoPushSqlTest {

  private static final String SQL = readSql("/db/V143__quote_bom_oa_todo_push.sql");

  @Test
  @DisplayName("待办表保存真实 OA 待办号、地址、推送状态和错误")
  void addsRealOaTodoColumns() {
    assertThat(SQL).contains(
        "oa_todo_id",
        "oa_todo_url",
        "push_status",
        "push_error_message",
        "last_push_at",
        "closed_at");
  }

  @Test
  @DisplayName("兼容历史 MOCK 待办记录并补齐索引")
  void backfillsMockRowsAndIndexes() {
    assertThat(SQL).contains(
        "UPDATE lp_bom_supplement_todo",
        "todo_status IN ('PUSHED', 'MOCK_PUSHED')",
        "idx_bom_supplement_oa_todo_id",
        "idx_bom_supplement_todo_push");
    assertThat(SQL).doesNotContain("DROP TABLE").doesNotContain("TRUNCATE TABLE");
  }

  private static String readSql(String resource) {
    try (var in = V143QuoteBomOaTodoPushSqlTest.class.getResourceAsStream(resource)) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 SQL 失败: " + resource, e);
    }
  }
}
