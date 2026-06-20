package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MaterialMasterRawMapper 组织当前料品 SQL")
class MaterialMasterRawMapperSqlTest {

  @Test
  @DisplayName("内部导入流水查询限定 active_flag、组织，并预留 source_type")
  void latestActiveBatchFiltersActiveFlagAndSourceType() throws Exception {
    String sql = selectSql("selectLatestActiveBatchId", String.class, String.class);

    assertThat(sql).contains(
        "MAX(import_batch_id)",
        "active_flag = 1",
        "organization_code = #{organizationCode}",
        "source_type = #{sourceType}");
  }

  @Test
  @DisplayName("按料号读取 raw 时限定组织当前有效行，避免跨组织混查")
  void selectByLatestBatchAndCodesUsesLatestActiveSubquery() throws Exception {
    String sql = selectSql(
        "selectByLatestBatchAndCodes", java.util.Collection.class, String.class, String.class);

    assertThat(sql).contains(
        "FROM lp_material_master_raw",
        "active_flag = 1",
        "organization_code = #{organizationCode}",
        "material_code IN",
        "<foreach collection='codes'");
    assertThat(sql).doesNotContain("SELECT MAX(import_batch_id)");
  }

  @Test
  @DisplayName("报价核算选择器限定组织当前有效 raw，并按料号、品名、型号模糊搜索")
  void optionsQueryUsesLatestActiveBatchAndKeywordFields() throws Exception {
    String sql = selectSql(
        "selectOptionsByLatestBatchKeyword", String.class, String.class, String.class, int.class);

    assertThat(sql).contains(
        "FROM lp_material_master_raw",
        "active_flag = 1",
        "organization_code = #{organizationCode}",
        "source_type = #{sourceType}",
        "material_code LIKE CONCAT('%', #{keyword}, '%')",
        "material_name LIKE CONCAT('%', #{keyword}, '%')",
        "material_model LIKE CONCAT('%', #{keyword}, '%')",
        "ORDER BY material_code ASC",
        "LIMIT #{limit}");
    assertThat(sql).doesNotContain("SELECT MAX(import_batch_id)");
  }

  @Test
  @DisplayName("包装组件父件查询限定组织当前有效 raw")
  void packageComponentParentQueryUsesLatestActiveBatch() throws Exception {
    String sql = selectSql(
        "selectPackageComponentParentsByLatestBatch", String.class, String.class, String.class);

    assertThat(sql).contains(
        "active_flag = 1",
        "organization_code = #{organizationCode}",
        "main_category_name = #{mainCategoryName}");
    assertThat(sql).doesNotContain("SELECT MAX(import_batch_id)");
  }

  private static String selectSql(String methodName, Class<?>... parameterTypes) throws Exception {
    Method method = MaterialMasterRawMapper.class.getMethod(methodName, parameterTypes);
    Select select = method.getAnnotation(Select.class);
    assertThat(select).isNotNull();
    return String.join("\n", select.value());
  }
}
