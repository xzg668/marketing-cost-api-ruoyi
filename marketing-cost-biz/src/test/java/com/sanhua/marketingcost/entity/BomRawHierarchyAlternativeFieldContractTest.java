package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-01 正式BOM层级替代字段契约")
class BomRawHierarchyAlternativeFieldContractTest {

  @Test
  @DisplayName("正式层级实体和Mapper保持原表契约")
  void entityAndMapperKeepRawHierarchyTable() {
    assertThat(BomRawHierarchy.class.getAnnotation(TableName.class).value())
        .isEqualTo("lp_bom_raw_hierarchy");
    assertThat(BaseMapper.class).isAssignableFrom(BomRawHierarchyMapper.class);
  }

  @Test
  @DisplayName("childType和alternativeGroupKey显式映射新增可空列")
  void mapsAlternativeColumns() throws Exception {
    assertMapping("childType", "child_type");
    assertMapping("alternativeGroupKey", "alternative_group_key");

    BomRawHierarchy legacy = new BomRawHierarchy();
    legacy.setMaterialCode("LEGACY");
    assertThat(legacy.getChildType()).isNull();
    assertThat(legacy.getAlternativeGroupKey()).isNull();
  }

  private static void assertMapping(String fieldName, String columnName) throws Exception {
    Field field = BomRawHierarchy.class.getDeclaredField(fieldName);
    assertThat(field.getType()).isEqualTo(String.class);
    assertThat(field.getAnnotation(TableField.class)).isNotNull();
    assertThat(field.getAnnotation(TableField.class).value()).isEqualTo(columnName);
  }
}
