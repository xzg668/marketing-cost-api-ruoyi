package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import java.lang.reflect.Method;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("U9 BOM副产品档案 Mapper SQL")
class U9BomByproductMasterMapperSqlTest {

  @Test
  @DisplayName("upsert 按五字段自然键冲突更新，不依赖批次")
  void upsertUsesNaturalKeyAndNoBatch() throws Exception {
    Method method = U9BomByproductMasterMapper.class.getMethod("upsert", U9BomByproductMaster.class);
    Insert insert = method.getAnnotation(Insert.class);
    assertThat(insert).isNotNull();
    String sql = String.join("\n", insert.value());

    assertThat(sql).contains(
        "INSERT INTO lp_u9_bom_byproduct_master",
        "parent_material_no",
        "bom_purpose",
        "byproduct_material_no",
        "effective_from",
        "effective_to",
        "ON DUPLICATE KEY UPDATE",
        "updated_at = CURRENT_TIMESTAMP");
    assertThat(sql).doesNotContain("import_batch_id");
  }
}
