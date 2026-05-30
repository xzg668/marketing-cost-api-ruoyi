package com.sanhua.marketingcost.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;

@DisplayName("BomSettlementRuleMapper · 新规则 Mapper 契约")
class BomSettlementRuleMapperContractTest {

  @Test
  @DisplayName("直接继承 BaseMapper<BomSettlementRule>，具备基础 CRUD 能力")
  void extendsBaseMapperWithSettlementRuleEntity() {
    Class<?> entityType =
        ResolvableType.forClass(BomSettlementRuleMapper.class)
            .as(BaseMapper.class)
            .getGeneric(0)
            .resolve();

    assertThat(BomSettlementRuleMapper.class).hasAnnotation(Mapper.class);
    assertThat(entityType).isEqualTo(BomSettlementRule.class);
  }
}
