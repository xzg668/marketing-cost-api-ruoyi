package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioPageResponse;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioUpdateRequest;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SupplierSupplyRatioServiceImplTest {

  private SupplierSupplyRatioMapper mapper;
  private SupplierSupplyRatioServiceImpl service;

  @BeforeEach
  void setUp() {
    mapper = mock(SupplierSupplyRatioMapper.class);
    service = new SupplierSupplyRatioServiceImpl(mapper);
  }

  @Test
  @DisplayName("分页查询默认只查未删除数据，并透传过滤条件")
  void pageFiltersActiveRowsAndConditions() {
    SupplierSupplyRatio row = row(10L);
    when(mapper.selectPage(any(Page.class), any(Wrapper.class)))
        .thenAnswer(invocation -> {
          Page<SupplierSupplyRatio> page = invocation.getArgument(0);
          page.setTotal(1);
          page.setRecords(List.of(row));
          return page;
        });

    SupplierSupplyRatioPageResponse response = service.page(
        "203240251", "小阀座", "SHF", "大新", "EXCEL", 2, 30, "COMMERCIAL");

    assertThat(response.getTotal()).isEqualTo(1);
    assertThat(response.getRecords()).containsExactly(row);

    ArgumentCaptor<Page<SupplierSupplyRatio>> pageCaptor = ArgumentCaptor.forClass(Page.class);
    ArgumentCaptor<QueryWrapper<SupplierSupplyRatio>> queryCaptor =
        ArgumentCaptor.forClass(QueryWrapper.class);
    verify(mapper).selectPage(pageCaptor.capture(), queryCaptor.capture());

    assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(30);
    assertThat(queryCaptor.getValue().getSqlSegment()).contains(
        "deleted",
        "business_unit_type",
        "material_code",
        "material_name",
        "spec_model",
        "supplier_name",
        "source_type",
        "supply_ratio");
  }

  @Test
  @DisplayName("分页参数兜底：页码最小为 1，每页最多 200")
  void pageNormalizesPageArguments() {
    when(mapper.selectPage(any(Page.class), any(Wrapper.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.page(null, null, null, null, null, -3, 999, null);

    ArgumentCaptor<Page<SupplierSupplyRatio>> pageCaptor = ArgumentCaptor.forClass(Page.class);
    verify(mapper).selectPage(pageCaptor.capture(), any(Wrapper.class));
    assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
    assertThat(pageCaptor.getValue().getSize()).isEqualTo(200);
  }

  @Test
  @DisplayName("更新供货比例时禁止负数")
  void updateRejectsNegativeRatio() {
    SupplierSupplyRatioUpdateRequest request = new SupplierSupplyRatioUpdateRequest();
    request.setSupplyRatio(new BigDecimal("-0.01"));

    assertThatThrownBy(() -> service.update(1L, request, "alice"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能小于 0");
    verify(mapper, never()).updateById(any(SupplierSupplyRatio.class));
  }

  @Test
  @DisplayName("更新只改允许维护的字段，不改导入幂等键字段")
  void updatePatchesAllowedFieldsOnly() {
    SupplierSupplyRatio existing = row(10L);
    existing.setMaterialCode("203240251");
    existing.setMaterialName("小阀座");
    existing.setSupplierName("新昌县大新机械厂");
    existing.setSpecModel("SHF-000-036003");
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);

    SupplierSupplyRatioUpdateRequest request = new SupplierSupplyRatioUpdateRequest();
    request.setUnit(" 个 ");
    request.setMaterialShape(" B ");
    request.setSupplierCode(" S001 ");
    request.setSupplyRatio(new BigDecimal("0.600000"));

    SupplierSupplyRatio updated = service.update(10L, request, "alice");

    assertThat(updated.getUnit()).isEqualTo("个");
    assertThat(updated.getMaterialShape()).isEqualTo("B");
    assertThat(updated.getSupplierCode()).isEqualTo("S001");
    assertThat(updated.getSupplyRatio()).isEqualByComparingTo("0.600000");
    assertThat(updated.getMaterialCode()).isEqualTo("203240251");
    assertThat(updated.getMaterialName()).isEqualTo("小阀座");
    assertThat(updated.getSupplierName()).isEqualTo("新昌县大新机械厂");
    assertThat(updated.getSpecModel()).isEqualTo("SHF-000-036003");
    assertThat(updated.getUpdatedBy()).isEqualTo("alice");
    verify(mapper).updateById(existing);
  }

  @Test
  @DisplayName("删除为逻辑删除")
  void deleteMarksRowDeleted() {
    SupplierSupplyRatio existing = row(10L);
    existing.setDeleted(0);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);

    service.delete(10L, "bob");

    assertThat(existing.getDeleted()).isEqualTo(1);
    assertThat(existing.getUpdatedBy()).isEqualTo("bob");
    verify(mapper).updateById(existing);
  }

  private SupplierSupplyRatio row(Long id) {
    SupplierSupplyRatio row = new SupplierSupplyRatio();
    row.setId(id);
    row.setBusinessUnitType("COMMERCIAL");
    row.setMaterialCode("203240251");
    row.setMaterialName("小阀座");
    row.setSupplierName("新昌县大新机械厂");
    row.setSpecModel("SHF-000-036003");
    row.setSupplyRatio(BigDecimal.ONE);
    row.setDeleted(0);
    return row;
  }
}
