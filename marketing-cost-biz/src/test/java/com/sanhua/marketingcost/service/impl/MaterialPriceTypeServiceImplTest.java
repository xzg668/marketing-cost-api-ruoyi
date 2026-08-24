package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.MaterialPriceTypeImportRequest;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class MaterialPriceTypeServiceImplTest {

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        MaterialPriceType.class);
  }

  @Test
  void firstImportAppendsNormalizedType() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    when(mapper.selectList(any())).thenReturn(List.of());

    new MaterialPriceTypeServiceImpl(mapper).importItems(request("MAT-1", "固定采购价"));

    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getMaterialCode()).isEqualTo("MAT-1");
    assertThat(captor.getValue().getPriceType()).isEqualTo("固定价");
  }

  @Test
  void sameCurrentTypeDoesNotUpdateOrAppend() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    MaterialPriceType current = route(10L, "MAT-1", "联动价");
    when(mapper.selectList(any())).thenReturn(List.of(current));

    List<MaterialPriceType> result =
        new MaterialPriceTypeServiceImpl(mapper).importItems(request("MAT-1", "LINKED"));

    assertThat(result).containsExactly(current);
    verify(mapper, never()).insert(any(MaterialPriceType.class));
    verify(mapper, never()).updateById(any(MaterialPriceType.class));
  }

  @Test
  void changedTypeAppendsAndKeepsOldRouteUntouched() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    MaterialPriceType current = route(10L, "MAT-1", "固定价");
    when(mapper.selectList(any())).thenReturn(List.of(current));

    new MaterialPriceTypeServiceImpl(mapper).importItems(request("MAT-1", "联动价"));

    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).insert(captor.capture());
    verify(mapper, never()).updateById(any(MaterialPriceType.class));
    assertThat(captor.getValue().getId()).isNull();
    assertThat(captor.getValue().getPriceType()).isEqualTo("联动价");
    assertThat(current.getEffectiveTo()).isNull();
  }

  @Test
  void settleAliasNormalizesToSettleFixed() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    when(mapper.selectList(any())).thenReturn(List.of());

    new MaterialPriceTypeServiceImpl(mapper).importItems(request("MAT-2", "结算价"));

    ArgumentCaptor<MaterialPriceType> captor = ArgumentCaptor.forClass(MaterialPriceType.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getPriceType()).isEqualTo("结算固定价");
  }

  @Test
  void pageReturnsHistory() {
    MaterialPriceTypeMapper mapper = Mockito.mock(MaterialPriceTypeMapper.class);
    Page<MaterialPriceType> page = new Page<>(1, 20);
    page.setRecords(List.of(route(1L, "MAT-1", "固定价")));
    page.setTotal(1);
    when(mapper.selectPage(any(), any())).thenReturn(page);

    Page<MaterialPriceType> result =
        new MaterialPriceTypeServiceImpl(mapper).page(null, null, null, null, 1, 20);

    assertThat(result.getTotal()).isOne();
    assertThat(result.getRecords()).hasSize(1);
  }

  private MaterialPriceTypeImportRequest request(String materialCode, String priceType) {
    MaterialPriceTypeImportRequest.MaterialPriceTypeRow row =
        new MaterialPriceTypeImportRequest.MaterialPriceTypeRow();
    row.setMaterialCode(materialCode);
    row.setPriceType(priceType);
    MaterialPriceTypeImportRequest request = new MaterialPriceTypeImportRequest();
    request.setRows(List.of(row));
    return request;
  }

  private MaterialPriceType route(Long id, String materialCode, String priceType) {
    MaterialPriceType route = new MaterialPriceType();
    route.setId(id);
    route.setMaterialCode(materialCode);
    route.setPriceType(priceType);
    return route;
  }
}
