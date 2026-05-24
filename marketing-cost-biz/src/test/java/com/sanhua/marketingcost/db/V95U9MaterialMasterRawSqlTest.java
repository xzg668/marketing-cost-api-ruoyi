package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V95 U9料品主档 raw 表字段契约 DDL")
class V95U9MaterialMasterRawSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("补齐本次 20260519 模板新增字段和未来来源追溯字段")
  void addsNewRawAndSourceColumns() {
    assertThat(SQL).contains(
        "global_seg_3_theoretical_net_weight VARCHAR(255) NULL COMMENT ''Excel第63列 全局段3(理论净重)''",
        "source_type VARCHAR(32) NOT NULL DEFAULT ''EXCEL''",
        "source_batch_no VARCHAR(128) NULL",
        "mapping_version VARCHAR(64) NOT NULL DEFAULT ''U9_ITEM_MASTER_20260519''",
        "active_flag TINYINT NOT NULL DEFAULT 1",
        "未来U9接口或中台接入复用",
        "未来U9接口接入用于追溯");
  }

  @Test
  @DisplayName("核心字段注释按新 Excel 1-based 列号维护，避免旧列序错位")
  void alignsCriticalCommentsToNewExcelHeaders() {
    assertThat(SQL).contains(
        "COMMENT ''Excel第6列 物料代码*''",
        "COMMENT ''Excel第11列 主分类代码''",
        "COMMENT ''Excel第12列 主分类名称''",
        "COMMENT ''Excel第14列 U9物料形态属性''",
        "COMMENT ''Excel第19列 默认主供应商''",
        "COMMENT ''Excel第20列 采购处理提前期''",
        "COMMENT ''Excel第38列 全局段5(净重)''",
        "COMMENT ''Excel第42列 全局段9(单品毛重)''",
        "COMMENT ''Excel第59列 成本要素''");
  }

  @Test
  @DisplayName("旧表头别名写入注释，兼容原 ItemMaster20260427.xlsx")
  void documentsOldHeaderAliases() {
    assertThat(SQL).contains(
        "Excel第61列 收货原则；旧表头别名：料品采购相关信息.收货原则",
        "Excel第62列 采购预处理提前期(天)；旧表头别名：料品MRP相关信息.采购预处理提前期(天)");
  }

  @Test
  @DisplayName("DDL 幂等，ADD COLUMN/ADD INDEX 都通过 information_schema 保护")
  void isIdempotentAndTableAware() {
    assertThat(SQL).contains(
        "CREATE PROCEDURE _v95_material_raw_add_column_if_not_exists",
        "CREATE PROCEDURE _v95_material_raw_add_index_if_not_exists",
        "CREATE PROCEDURE _v95_material_raw_modify_column_if_exists",
        "information_schema.TABLES",
        "information_schema.COLUMNS",
        "information_schema.STATISTICS",
        "DROP PROCEDURE IF EXISTS _v95_material_raw_add_column_if_not_exists");
  }

  @Test
  @DisplayName("保留既有索引并补充来源批次和有效批次索引")
  void keepsExistingIndexesAndAddsTraceIndexes() {
    assertThat(SQL).contains(
        "idx_raw_batch",
        "ADD KEY idx_raw_batch (import_batch_id)",
        "idx_raw_cost_element",
        "ADD KEY idx_raw_cost_element (cost_element)",
        "idx_raw_main_cat",
        "ADD KEY idx_raw_main_cat (main_category_code)",
        "idx_raw_source_batch",
        "ADD KEY idx_raw_source_batch (source_type, source_batch_no)",
        "idx_raw_active_batch",
        "ADD KEY idx_raw_active_batch (active_flag, import_batch_id)");
  }

  @Test
  @DisplayName("迁移不做破坏性表数据操作")
  void doesNotUseDestructiveTableOperations() {
    assertThat(SQL)
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM lp_material_master_raw");
  }

  @Test
  @DisplayName("表注释说明本表按 20260519 Excel 表头维护")
  void updatesTableComment() {
    assertThat(SQL).contains(
        "U9料品主档原始导入表",
        "字段注释按料品档案20260519.xlsx第2行表头和Excel 1-based列号维护");
  }

  private static String readSql() {
    try (var in = V95U9MaterialMasterRawSqlTest.class.getResourceAsStream(
        "/db/V95__u9_material_master_raw_20260519.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V95 SQL 失败", e);
    }
  }
}
