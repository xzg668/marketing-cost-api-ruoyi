package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.mapper.PriceVariableBindingMapper;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorIdentityReadRepository;
import java.lang.reflect.Field;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-05 解析不修改旧身份、价格和绑定")
class PriceLinkedType2FactorResolverNoLegacyMutationTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FactorIdentity.class);
    TableInfoHelper.initTableInfo(assistant, FactorMonthlyPrice.class);
    TableInfoHelper.initTableInfo(assistant, PriceVariableBinding.class);
  }

  @Test
  @DisplayName("只读仓储执行身份、月度价格和旧绑定查询后没有任何写操作")
  void repositoryUsesSelectOnly() {
    FactorIdentityMapper identityMapper = mock(FactorIdentityMapper.class);
    FactorMonthlyPriceMapper monthlyPriceMapper = mock(FactorMonthlyPriceMapper.class);
    PriceVariableBindingMapper bindingMapper = mock(PriceVariableBindingMapper.class);
    when(identityMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(monthlyPriceMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    when(bindingMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
    PriceLinkedType2FactorIdentityReadRepository repository =
        new PriceLinkedType2FactorIdentityReadRepositoryImpl(
            identityMapper, monthlyPriceMapper, bindingMapper);

    repository.findActiveIdentities("BU-A");
    repository.findActiveMonthlyPrices(List.of(9001L, 9002L), "2026-07");
    repository.countActiveLegacyBindings(List.of(9001L, 9002L));

    verify(identityMapper).selectList(any(Wrapper.class));
    verify(monthlyPriceMapper).selectList(any(Wrapper.class));
    verify(bindingMapper).selectList(any(Wrapper.class));
    verifyNoMoreInteractions(identityMapper, monthlyPriceMapper, bindingMapper);
  }

  @Test
  @DisplayName("身份解析器没有数据库 Mapper 或硬编码数字身份字段")
  void resolverHasNoMapperOrNumericIdentityConstants() {
    Field[] fields = PriceLinkedType2FactorIdentityResolverImpl.class.getDeclaredFields();

    assertThat(fields)
        .extracting(field -> field.getType().getName())
        .noneMatch(typeName -> typeName.contains(".mapper."));
    assertThat(fields)
        .noneMatch(field ->
            (field.getType() == Long.class
                    || field.getType() == long.class
                    || field.getType() == Integer.class
                    || field.getType() == int.class)
                && java.lang.reflect.Modifier.isStatic(field.getModifiers()));
  }
}
