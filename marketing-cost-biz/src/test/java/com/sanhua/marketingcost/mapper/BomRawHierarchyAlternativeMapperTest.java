package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-03 正式层级替代字段批量写入契约")
class BomRawHierarchyAlternativeMapperTest {

  @Test
  @DisplayName("batchUpsert插入和重复键更新都包含三项来源追溯字段")
  void batchUpsertPersistsAndUpdatesAlternativeMetadata() throws Exception {
    Method method = BomRawHierarchyMapper.class.getMethod("batchUpsert", java.util.List.class);
    Insert insert = method.getAnnotation(Insert.class);
    String sql = String.join("", insert.value()).replaceAll("\\s+", " ");

    assertThat(sql).contains(
        "source_u9_row_id, source_line_key, process_seq,",
        "child_type, alternative_group_key,",
        "#{e.sourceU9RowId}",
        "#{e.childType}",
        "#{e.alternativeGroupKey}",
        "source_u9_row_id = VALUES(source_u9_row_id)",
        "child_type = VALUES(child_type)",
        "alternative_group_key = VALUES(alternative_group_key)");
  }

  @Test
  @DisplayName("新增字段只扩展既有upsert，不删除原层级和用量字段")
  void keepsExistingHierarchyAndQuantityColumns() throws Exception {
    Insert insert =
        BomRawHierarchyMapper.class
            .getMethod("batchUpsert", java.util.List.class)
            .getAnnotation(Insert.class);
    String sql = Arrays.toString(insert.value());

    assertThat(sql).contains(
        "top_product_code",
        "parent_code",
        "material_code",
        "level",
        "path",
        "sort_seq",
        "qty_per_parent",
        "qty_per_top",
        "source_import_batch_id",
        "build_batch_id");
  }
}
