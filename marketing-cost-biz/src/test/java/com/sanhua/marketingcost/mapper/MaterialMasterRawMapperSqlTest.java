package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MaterialMasterRawMapper 最新有效批次 SQL")
class MaterialMasterRawMapperSqlTest {

  @Test
  @DisplayName("最新有效批次查询限定 active_flag，并预留 source_type")
  void latestActiveBatchFiltersActiveFlagAndSourceType() throws Exception {
    String sql = selectSql("selectLatestActiveBatchId", String.class);

    assertThat(sql).contains(
        "MAX(import_batch_id)",
        "active_flag = 1",
        "source_type = #{sourceType}");
  }

  @Test
  @DisplayName("按料号读取 raw 时只取最新有效批次，避免多批次混查")
  void selectByLatestBatchAndCodesUsesLatestActiveSubquery() throws Exception {
    String sql = selectSql("selectByLatestBatchAndCodes", java.util.Collection.class, String.class);

    assertThat(sql).contains(
        "FROM lp_material_master_raw",
        "active_flag = 1",
        "SELECT MAX(import_batch_id)",
        "material_code IN",
        "<foreach collection='codes'");
  }

  @Test
  @DisplayName("包装组件父件查询限定最新有效批次")
  void packageComponentParentQueryUsesLatestActiveBatch() throws Exception {
    String sql = selectSql("selectPackageComponentParentsByLatestBatch", String.class, String.class);

    assertThat(sql).contains(
        "active_flag = 1",
        "SELECT MAX(import_batch_id)",
        "main_category_name = #{mainCategoryName}");
  }

  @Test
  @DisplayName("批次摘要由 raw 表聚合出 MATERIAL_MASTER 批次记录")
  void batchSummaryAggregatesRawRows() throws Exception {
    String sql = selectSql("listBatchSummaries");

    assertThat(sql).contains(
        "import_batch_id AS batchNo",
        "'MATERIAL_MASTER' AS datasetCode",
        "COALESCE(source_type, 'EXCEL') AS sourceType",
        "COUNT(*) AS totalCount",
        "COUNT(*) AS successCount",
        "0 AS failCount",
        "GROUP BY import_batch_id, source_type, mapping_version");
  }

  private static String selectSql(String methodName, Class<?>... parameterTypes) throws Exception {
    Method method = MaterialMasterRawMapper.class.getMethod(methodName, parameterTypes);
    Select select = method.getAnnotation(Select.class);
    assertThat(select).isNotNull();
    return String.join("\n", select.value());
  }
}
