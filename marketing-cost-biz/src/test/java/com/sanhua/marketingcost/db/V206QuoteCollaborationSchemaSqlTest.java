package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-01 报价BOM与价格协作结构 SQL")
class V206QuoteCollaborationSchemaSqlTest {

  private static final String SQL = readMigrationSql();
  private static final List<String> TABLES = List.of(
      "lp_quote_collaboration_task",
      "lp_quote_collaboration_product_task",
      "lp_quote_collaboration_quote_link",
      "lp_quote_collaboration_gap",
      "lp_quote_price_draft",
      "lp_quote_price_draft_field",
      "lp_quote_collaboration_review",
      "lp_quote_collaboration_review_item",
      "lp_quote_collaboration_approved_result",
      "lp_quote_collaboration_external_task",
      "lp_integration_outbox",
      "lp_integration_inbox");

  @Test
  @DisplayName("迁移只创建约定的十二张协作表")
  void createsExactlyTwelveCollaborationTables() {
    var matcher = Pattern.compile(
        "(?im)^CREATE TABLE IF NOT EXISTS `([^`]+)`").matcher(SQL);
    var actual = new java.util.ArrayList<String>();
    while (matcher.find()) {
      actual.add(matcher.group(1));
    }

    assertThat(actual).containsExactlyElementsOf(TABLES);
    assertThat(SQL).containsOnlyOnce("SET NAMES utf8mb4;");
    assertThat(SQL).contains(TABLES.stream()
        .map(table -> "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
        .toArray(String[]::new));
  }

  @Test
  @DisplayName("迁移不修改原表且不写入任何业务或模拟数据")
  void doesNotMutateExistingSchemaOrBusinessData() {
    String executableSql = SQL.lines()
        .filter(line -> !line.stripLeading().startsWith("--"))
        .reduce("", (left, right) -> left + "\n" + right)
        .toUpperCase();

    var forbiddenStatement = Pattern.compile(
        "(?m)^\\s*(ALTER\\s+TABLE|DROP\\s+TABLE|TRUNCATE\\s+TABLE|"
            + "INSERT\\s+INTO|UPDATE\\s+|DELETE\\s+FROM|REPLACE\\s+INTO)\\b");
    assertThat(forbiddenStatement.matcher(executableSql).find()).isFalse();
  }

  @Test
  @DisplayName("主任务、产品锁、报价关联和缺口具备并发唯一键")
  void definesTaskAndGapUniqueness() {
    assertThat(SQL).contains(
        "UNIQUE KEY `uk_collaboration_no` (`collaboration_no`)",
        "UNIQUE KEY `uk_collaboration_form_round` (`oa_form_id`, `round_no`)",
        "UNIQUE KEY `uk_product_task_no` (`product_task_no`)",
        "UNIQUE KEY `uk_product_task_active_lock` (`active_lock_key`)",
        "UNIQUE KEY `uk_collaboration_quote_link_active` (`active_link_key`)",
        "UNIQUE KEY `uk_collaboration_gap_no` (`gap_no`)",
        "UNIQUE KEY `uk_task_gap_fingerprint` (`product_task_id`, `gap_fingerprint`)");
  }

  @Test
  @DisplayName("价格草稿和财务审核字段精度、版本及唯一键符合契约")
  void definesPriceDraftAndReviewContract() {
    assertThat(SQL).contains(
        "`tax_rate` DECIMAL(10,6) NULL",
        "`price_type` VARCHAR(32) NOT NULL COMMENT 'FIXED_PURCHASE/LINKED/RANGE/SETTLE_FIXED'",
        "`draft_version` INT NOT NULL DEFAULT 1",
        "`published_source_table` VARCHAR(64) NULL",
        "`published_source_id` BIGINT NULL",
        "`publish_batch_no` VARCHAR(64) NULL",
        "UNIQUE KEY `uk_price_draft_field` (`price_draft_id`, `section_code`, `row_key`, `field_code`)",
        "UNIQUE KEY `uk_review_round` (`collaboration_id`, `review_round`)",
        "UNIQUE KEY `uk_review_item` (`review_id`, `item_type`, `item_ref_id`, `item_version`)",
        "UNIQUE KEY `uk_review_publish_batch` (`publish_batch_no`)");
  }

  @Test
  @DisplayName("OA关闭期所需外部任务及收发件箱具备幂等和调度索引")
  void definesIntegrationIdempotencyContract() {
    assertThat(SQL).contains(
        "`external_status` VARCHAR(32) NOT NULL COMMENT 'NOT_CREATED/HOLD/OPEN/DONE/CLOSED/FAILED'",
        "UNIQUE KEY `uk_external_task_id` (`external_task_id`)",
        "UNIQUE KEY `uk_outbox_event_id` (`event_id`)",
        "UNIQUE KEY `uk_outbox_idempotency_key` (`idempotency_key`)",
        "KEY `idx_outbox_dispatch` (`destination`, `send_status`, `next_retry_at`)",
        "UNIQUE KEY `uk_inbox_callback_id` (`callback_id`)",
        "UNIQUE KEY `uk_inbox_idempotency_key` (`idempotency_key`)",
        "KEY `idx_inbox_process` (`source_system`, `process_status`, `received_at`)");
  }

  @Test
  @DisplayName("BOM包装复用只保存来源对象和六个月策略，不保存价格或成本结果")
  void approvedResultOnlyCarriesReusableStructure() {
    assertThat(SQL).contains(
        "`result_type` VARCHAR(32) NOT NULL COMMENT 'FULL_BOM/BARE_PACKAGE'",
        "`source_object_type` VARCHAR(64) NOT NULL COMMENT 'SUPPLEMENT_VERSION/PACKAGE_REFERENCE'",
        "`structure_fingerprint` CHAR(64) NOT NULL",
        "`u9_context_fingerprint` CHAR(64) NULL",
        "`validity_months` INT NOT NULL DEFAULT 6",
        "KEY `idx_approved_result_match` (`product_code`, `applicable_org_code`, `result_type`, `result_status`, `valid_until`)");
  }

  private static String readMigrationSql() {
    try (var in = V206QuoteCollaborationSchemaSqlTest.class.getResourceAsStream(
        "/db/V206__quote_bom_price_collaboration_schema.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("读取 V206 SQL 失败", ex);
    }
  }
}
