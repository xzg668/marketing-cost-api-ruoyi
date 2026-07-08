package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.U9BomByproductImportResponse;
import com.sanhua.marketingcost.entity.U9BomByproductMaster;
import com.sanhua.marketingcost.mapper.U9BomByproductMasterMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("U9BomByproductMasterServiceImpl")
class U9BomByproductMasterServiceImplTest {
  private U9BomByproductMasterMapper mapper;
  private U9BomByproductMasterServiceImpl service;

  @BeforeEach
  void setUp() {
    mapper = mock(U9BomByproductMasterMapper.class);
    service = new U9BomByproductMasterServiceImpl(mapper);
  }

  @Test
  @DisplayName("page：按 price_org_code 过滤副产品主档")
  void pageFiltersByPriceOrgCode() {
    when(mapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
      Page<U9BomByproductMaster> page = invocation.getArgument(0);
      page.setTotal(0);
      page.setRecords(List.of());
      return page;
    });

    service.page(
        "220",
        "PARENT",
        null,
        null,
        null,
        "主制造",
        null,
        null,
        1,
        20);

    ArgumentCaptor<Wrapper<U9BomByproductMaster>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectPage(any(Page.class), captor.capture());
    assertThat(captor.getValue().getCustomSqlSegment()).contains("price_org_code");
    assertThat(((AbstractWrapper<?, ?, ?>) captor.getValue()).getParamNameValuePairs().values())
        .contains("220");
  }

  @Test
  @DisplayName("page：非法 price_org_code 直接失败")
  void pageFailsOnInvalidPriceOrgCode() {
    assertThatThrownBy(() -> service.page(
            "999",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            1,
            20))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BOM报价组织");
  }

  @Test
  @DisplayName("importExcel：旧模板不带组织时默认导入到 210")
  void importExcelDefaultsOldTemplateToCommercialOrg() {
    when(mapper.upsert(any())).thenReturn(1);

    U9BomByproductImportResponse response = service.importExcel(
        new ByteArrayInputStream(excelBytes(false, row(null, "SCRAP-1"))),
        "old.xlsx",
        "admin");

    assertThat(response.getSuccessCount()).isEqualTo(1);
    ArgumentCaptor<U9BomByproductMaster> captor =
        ArgumentCaptor.forClass(U9BomByproductMaster.class);
    verify(mapper).upsert(captor.capture());
    assertThat(captor.getValue().getPriceOrgCode()).isEqualTo("210");
  }

  @Test
  @DisplayName("importExcel：文件内同自然键按组织去重，210/220 可同时导入")
  void importExcelDeduplicatesByOrganization() {
    when(mapper.upsert(any())).thenReturn(1);

    U9BomByproductImportResponse response = service.importExcel(
        new ByteArrayInputStream(excelBytes(
            true,
            row("210", "SCRAP-1"),
            row("220", "SCRAP-1"))),
        "org.xlsx",
        "admin");

    assertThat(response.getSuccessCount()).isEqualTo(2);
    ArgumentCaptor<U9BomByproductMaster> captor =
        ArgumentCaptor.forClass(U9BomByproductMaster.class);
    verify(mapper, org.mockito.Mockito.times(2)).upsert(captor.capture());
    assertThat(captor.getAllValues()).extracting(U9BomByproductMaster::getPriceOrgCode)
        .containsExactly("210", "220");
  }

  private static byte[] excelBytes(boolean includePriceOrgCode, List<Object>... rows) {
    List<List<String>> heads = new ArrayList<>();
    if (includePriceOrgCode) {
      heads.add(List.of("price_org_code"));
    }
    U9BomByproductFieldContract.fieldMappings()
        .forEach(mapping -> heads.add(List.of(mapping.header())));
    List<List<Object>> data = new ArrayList<>();
    for (List<Object> row : rows) {
      data.add(row);
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    EasyExcel.write(out)
        .head(heads)
        .sheet(U9BomByproductFieldContract.SHEET_NAME)
        .doWrite(data);
    return out.toByteArray();
  }

  private static List<Object> row(String priceOrgCode, String byproductCode) {
    List<Object> values = new ArrayList<>();
    if (priceOrgCode != null) {
      values.add(priceOrgCode);
    }
    values.add("PARENT-1");
    values.add("母件一");
    values.add("规格");
    values.add("主制造");
    values.add("V1");
    values.add("副产");
    values.add(byproductCode);
    values.add("副产品一");
    values.add("10");
    values.add("1.00000000");
    values.add("KG");
    values.add("已审核");
    values.add("D001");
    values.add("生产部");
    values.add("2026-01-01");
    values.add("2099-12-31");
    values.add("u9");
    values.add("2026-01-01 00:00:00");
    return values;
  }
}
