package com.sanhua.marketingcost.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.dto.FactorIdentityDto;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-01 影响因素统一身份实体契约")
class FactorIdentityCanonicalContractTest {

  @Test
  @DisplayName("实体和 Mapper 保持 lp_factor_identity 映射")
  void entityAndMapperKeepFactorIdentityTableContract() {
    assertThat(FactorIdentity.class.getAnnotation(TableName.class).value())
        .isEqualTo("lp_factor_identity");
    assertThat(BaseMapper.class).isAssignableFrom(FactorIdentityMapper.class);
  }

  @Test
  @DisplayName("三个新增字段显式映射数据库列且历史对象默认为空")
  void canonicalFieldsAreExplicitAndNullable() throws Exception {
    assertFieldMapping("canonicalFactorKey", String.class, "canonical_factor_key");
    assertFieldMapping(
        "canonicalFactorIdentityId", Long.class, "canonical_factor_identity_id");
    assertFieldMapping("identityOrigin", String.class, "identity_origin");

    FactorIdentity legacy = new FactorIdentity();
    assertThat(legacy.getCanonicalFactorKey()).isNull();
    assertThat(legacy.getCanonicalFactorIdentityId()).isNull();
    assertThat(legacy.getIdentityOrigin()).isNull();
  }

  @Test
  @DisplayName("实体和 DTO 能完整承载统一身份")
  void entityAndDtoExposeCanonicalIdentity() {
    FactorIdentity entity = new FactorIdentity();
    entity.setCanonicalFactorKey("AVG|1#CU");
    entity.setCanonicalFactorIdentityId(191L);
    entity.setIdentityOrigin("STANDARD_IMPORT");

    FactorIdentityDto dto = new FactorIdentityDto();
    dto.setCanonicalFactorKey(entity.getCanonicalFactorKey());
    dto.setCanonicalFactorIdentityId(entity.getCanonicalFactorIdentityId());
    dto.setIdentityOrigin(entity.getIdentityOrigin());

    assertThat(dto.getCanonicalFactorKey()).isEqualTo("AVG|1#CU");
    assertThat(dto.getCanonicalFactorIdentityId()).isEqualTo(191L);
    assertThat(dto.getIdentityOrigin()).isEqualTo("STANDARD_IMPORT");
  }

  private static void assertFieldMapping(
      String fieldName, Class<?> expectedType, String expectedColumn) throws Exception {
    Field field = FactorIdentity.class.getDeclaredField(fieldName);
    assertThat(field.getType()).isEqualTo(expectedType);
    assertThat(field.getAnnotation(TableField.class)).isNotNull();
    assertThat(field.getAnnotation(TableField.class).value()).isEqualTo(expectedColumn);
  }
}
