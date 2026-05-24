package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MaterialMasterMapper 非空覆盖 SQL")
class MaterialMasterMapperSqlTest {

  @Test
  @DisplayName("upsertBatch 使用 COALESCE，避免 U9 空字段覆盖主表非空值")
  void upsertBatchUsesNonNullOverwrite() throws Exception {
    Method method = MaterialMasterMapper.class.getMethod("upsertBatch", List.class);
    Insert insert = method.getAnnotation(Insert.class);
    assertThat(insert).isNotNull();
    String sql = String.join("\n", insert.value());

    assertThat(sql).contains(
        "material_name=COALESCE(VALUES(material_name), material_name)",
        "net_weight_kg=COALESCE(VALUES(net_weight_kg), net_weight_kg)",
        "default_supplier=COALESCE(VALUES(default_supplier), default_supplier)",
        "import_batch_id=VALUES(import_batch_id)");
  }
}
