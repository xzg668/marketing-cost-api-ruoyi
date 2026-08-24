package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.service.MaterialPriceTypeRouteSyncService.RouteCommand;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class MaterialPriceTypeRouteSyncServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        MaterialPriceType.class);
  }

  @Test
  void firstFormalPriceCreatesCurrentTypeInSameBusinessUnit() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    when(mapper.selectList(any())).thenReturn(List.of());

    var result = new MaterialPriceTypeRouteSyncServiceImpl(mapper).sync(
        command("MAT-1", "COMMERCIAL", "PURCHASE_FIXED"));

    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).insert(captor.capture());
    assertThat(result.created()).isTrue();
    assertThat(captor.getValue().getPriceType()).isEqualTo("固定价");
    assertThat(captor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(captor.getValue().getPeriod()).isNull();
    assertThat(captor.getValue().getEffectiveFrom()).isNull();
  }

  @Test
  void repeatedSameTypeDoesNotWriteAgain() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    MaterialPriceType current = route(1L, "MAT-1", "固定价", "COMMERCIAL");
    when(mapper.selectList(any())).thenReturn(List.of(current));

    var result = new MaterialPriceTypeRouteSyncServiceImpl(mapper).sync(
        command("MAT-1", "COMMERCIAL", "FIXED"));

    assertThat(result.created()).isFalse();
    assertThat(result.route()).isSameAs(current);
    verify(mapper, never()).insert(any(MaterialPriceType.class));
  }

  @Test
  void typeChangeAppendsWithoutUpdatingOldHistory() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    MaterialPriceType current = route(1L, "MAT-1", "固定价", "HOUSEHOLD");
    when(mapper.selectList(any())).thenReturn(List.of(current));

    new MaterialPriceTypeRouteSyncServiceImpl(mapper).sync(
        command("MAT-1", "HOUSEHOLD", "LINKED"));

    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).insert(captor.capture());
    verify(mapper, never()).updateById(any(MaterialPriceType.class));
    assertThat(captor.getValue().getPriceType()).isEqualTo("联动价");
    assertThat(captor.getValue().getBusinessUnitType()).isEqualTo("HOUSEHOLD");
    assertThat(current.getEffectiveTo()).isNull();
  }

  private RouteCommand command(String materialCode, String businessUnit, String priceType) {
    return new RouteCommand(
        materialCode, "物料", "规格", "件", businessUnit, priceType, "test", "TEST");
  }

  private MaterialPriceType route(
      Long id, String materialCode, String priceType, String businessUnit) {
    MaterialPriceType route = new MaterialPriceType();
    route.setId(id);
    route.setMaterialCode(materialCode);
    route.setPriceType(priceType);
    route.setBusinessUnitType(businessUnit);
    return route;
  }
}
