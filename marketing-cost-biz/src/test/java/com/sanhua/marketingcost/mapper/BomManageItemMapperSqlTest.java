package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BomManageItemMapper SQL")
class BomManageItemMapperSqlTest {

  @Test
  @DisplayName("BOM 明细不展示包装组件父料号下的包装子件")
  void detailRowsExcludePackageComponentChildren() throws NoSuchMethodException {
    String sql = selectSql(
        "selectDetailRows",
        String.class,
        Long.class,
        String.class,
        String.class,
        String.class);

    assertThat(sql).contains(
        "NOT EXISTS",
        "FROM lp_material_master_raw pkg_parent",
        "pkg_parent.material_code = t1.parent_code",
        "pkg_parent.active_flag = 1",
        "SELECT MAX(pkg_latest.import_batch_id)",
        "pkg_parent.main_category_code LIKE '15155%'",
        "pkg_parent.shape_attr = '虚拟'");
  }

  @Test
  @DisplayName("父级列表明细数同样排除包装子件")
  void parentRowDetailCountExcludesPackageComponentChildren() throws NoSuchMethodException {
    String sql = selectSql(
        "selectParentRows",
        String.class,
        String.class,
        String.class,
        String.class,
        long.class,
        long.class);

    assertThat(sql).contains(
        "NOT EXISTS",
        "pkg_parent.material_code = t1.parent_code",
        "pkg_parent.import_batch_id = (",
        "pkg_parent.main_category_code LIKE '15155%'",
        "COUNT(1) AS detailCount");
  }

  private String selectSql(String methodName, Class<?>... parameterTypes)
      throws NoSuchMethodException {
    Select select = BomManageItemMapper.class
        .getMethod(methodName, parameterTypes)
        .getAnnotation(Select.class);
    assertThat(select).isNotNull();
    return String.join("\n", select.value());
  }
}
