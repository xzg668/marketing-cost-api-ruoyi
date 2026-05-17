package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CmsMaterialScrapRefQueryServiceImplTest {
  private MaterialScrapRefMapper materialScrapRefMapper;
  private CmsMaterialScrapRefQueryServiceImpl service;

  @BeforeEach
  void setUp() {
    materialScrapRefMapper = mock(MaterialScrapRefMapper.class);
    service = new CmsMaterialScrapRefQueryServiceImpl(materialScrapRefMapper);
  }

  @Test
  @DisplayName("T4 current 分页：按标准化后的原材料/回收料号和关键字查询")
  void pageCurrentFiltersByNormalizedCodesAndKeyword() {
    Page<MaterialScrapRef> returned = new Page<>(1, 20);
    returned.setTotal(1);
    returned.setRecords(List.of(mapping("301050066", "301990317", "拉制铜管", "废紫铜沫")));
    when(materialScrapRefMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(returned);

    var response = service.pageCurrent(" 301 050066", "301 990317", "紫铜", 1, 20, "COMMERCIAL");

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getList()).extracting(MaterialScrapRef::getScrapCode).containsExactly("301990317");
    String sql = capturedSelectPageSql();
    assertThat(sql)
        .contains("material_code")
        .contains("scrap_code")
        .contains("material_name")
        .contains("scrap_name")
        .contains("business_unit_type");
    assertParamContains(capturedSelectPageParams(), "301050066");
    assertParamContains(capturedSelectPageParams(), "301990317");
    assertParamContains(capturedSelectPageParams(), "紫铜");
    assertParamContains(capturedSelectPageParams(), "COMMERCIAL");
  }

  private MaterialScrapRef mapping(String materialCode, String scrapCode, String materialName, String scrapName) {
    MaterialScrapRef row = new MaterialScrapRef();
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialName);
    row.setScrapCode(scrapCode);
    row.setScrapName(scrapName);
    row.setScrapUnit("千克");
    row.setBusinessUnitType("COMMERCIAL");
    return row;
  }

  private void assertParamContains(List<Object> params, String expected) {
    assertThat(params.stream().map(String::valueOf).toList())
        .anySatisfy(value -> assertThat(value).contains(expected));
  }

  private String capturedSelectPageSql() {
    return capturedSelectPageWrapper().getCustomSqlSegment();
  }

  private List<Object> capturedSelectPageParams() {
    Wrapper<?> wrapper = capturedSelectPageWrapper();
    if (wrapper instanceof AbstractWrapper<?, ?, ?> abstractWrapper) {
      return new java.util.ArrayList<>(abstractWrapper.getParamNameValuePairs().values());
    }
    return List.of();
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Wrapper<?> capturedSelectPageWrapper() {
    ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(materialScrapRefMapper, org.mockito.Mockito.atLeastOnce())
        .selectPage(any(Page.class), captor.capture());
    return captor.getValue();
  }
}
