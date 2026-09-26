package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V257DropLegacyCollaborationTablesSqlTest {

  @Test
  void guardsCutoverBeforeDroppingExactlyElevenLegacyTables() throws IOException {
    String sql = new String(getClass().getResourceAsStream(
        "/db/V257__drop_legacy_collaboration_tables.sql").readAllBytes(),
        StandardCharsets.UTF_8);

    assertThat(sql)
        .contains(
            "new_table_count <> 8",
            "old_table_count NOT IN (0, 11)",
            "T15_BLOCKED: 8张新技术资料表不完整",
            "T15_BLOCKED: 旧菜单或权限尚未完成切换",
            "status='COLLABORATION'",
            "information_schema.views",
            "information_schema.triggers",
            "CALL `assert_t15_legacy_drop_ready`()",
            "DROP TABLE IF EXISTS");

    String drop = sql.substring(sql.lastIndexOf("DROP TABLE IF EXISTS"));
    assertThat(drop)
        .containsOnlyOnce("`lp_quote_collaboration_product_task`,")
        .containsOnlyOnce("`lp_quote_collaboration_quote_link`,")
        .containsOnlyOnce("`lp_quote_collaboration_gap`,")
        .containsOnlyOnce("`lp_quote_price_draft_field`,")
        .containsOnlyOnce("`lp_quote_collaboration_review_item`,")
        .containsOnlyOnce("`lp_quote_collaboration_approved_result`,")
        .containsOnlyOnce("`lp_quote_collaboration_admin_action`,")
        .containsOnlyOnce("`lp_quote_collaboration_review`,")
        .containsOnlyOnce("`lp_quote_price_draft`,")
        .containsOnlyOnce("`lp_quote_collaboration_task`,")
        .containsOnlyOnce("`lp_collaboration_token`;");
  }
}
