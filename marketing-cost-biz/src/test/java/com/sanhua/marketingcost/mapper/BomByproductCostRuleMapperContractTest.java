package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.BomByproductCostRule;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;

@DisplayName("BomByproductCostRuleMapper · 新规则 Mapper 契约")
class BomByproductCostRuleMapperContractTest {

  @Test
  @DisplayName("直接继承 BaseMapper<BomByproductCostRule>，具备基础 CRUD 能力")
  void extendsBaseMapperWithByproductRuleEntity() {
    Class<?> entityType =
        ResolvableType.forClass(BomByproductCostRuleMapper.class)
            .as(BaseMapper.class)
            .getGeneric(0)
            .resolve();

    assertThat(BomByproductCostRuleMapper.class).hasAnnotation(Mapper.class);
    assertThat(entityType).isEqualTo(BomByproductCostRule.class);
  }
}
