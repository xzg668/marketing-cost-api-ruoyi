package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.ManufactureRateImportRequest;
import com.sanhua.marketingcost.dto.ManufactureRateImportResponse;
import com.sanhua.marketingcost.entity.ManufactureRate;
import com.sanhua.marketingcost.mapper.ManufactureRateMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ManufactureRateServiceImplTest {

  @Test
  @DisplayName("制造费用率导入：有料号按料号级配置插入")
  void importItemsInsertsMaterialCodeLevel() {
    ManufactureRateMapper mapper = mock(ManufactureRateMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(mapper.insert(any(ManufactureRate.class))).thenReturn(1);

    ManufactureRateServiceImpl service = new ManufactureRateServiceImpl(mapper);
    ManufactureRateImportResponse result = service.importItems(request(row(2, "P-001", "MODEL-A", "产品A")));

    assertThat(result.getInserted()).isEqualTo(1);
    ArgumentCaptor<ManufactureRate> captor = ArgumentCaptor.forClass(ManufactureRate.class);
    verify(mapper).insert(captor.capture());
    ManufactureRate inserted = captor.getValue();
    assertThat(inserted.getRateYear()).isEqualTo(2026);
    assertThat(inserted.getMatchLevel()).isEqualTo("MATERIAL_CODE");
    assertThat(inserted.getMatchKey()).isEqualTo("P-001");
    assertThat(inserted.getPeriod()).isEqualTo("2026-01");
    assertThat(inserted.getCompany()).isEmpty();
    assertThat(inserted.getProductSubcategory()).isEmpty();
  }

  @Test
  @DisplayName("制造费用率导入：无料号有型号按型号级配置插入")
  void importItemsInsertsMaterialModelLevel() {
    ManufactureRateMapper mapper = mock(ManufactureRateMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(mapper.insert(any(ManufactureRate.class))).thenReturn(1);

    ManufactureRateServiceImpl service = new ManufactureRateServiceImpl(mapper);
    ManufactureRateImportResponse result = service.importItems(request(row(3, null, "MODEL-B", "产品B")));

    assertThat(result.getInserted()).isEqualTo(1);
    ArgumentCaptor<ManufactureRate> captor = ArgumentCaptor.forClass(ManufactureRate.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getMatchLevel()).isEqualTo("MATERIAL_MODEL");
    assertThat(captor.getValue().getMatchKey()).isEqualTo("MODEL-B");
  }

  @Test
  @DisplayName("制造费用率导入：无料号型号时按事业部+产品名称插入")
  void importItemsInsertsDivisionProductNameLevel() {
    ManufactureRateMapper mapper = mock(ManufactureRateMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(mapper.insert(any(ManufactureRate.class))).thenReturn(1);

    ManufactureRateServiceImpl service = new ManufactureRateServiceImpl(mapper);
    ManufactureRateImportResponse result = service.importItems(request(row(4, null, null, "产品C")));

    assertThat(result.getInserted()).isEqualTo(1);
    ArgumentCaptor<ManufactureRate> captor = ArgumentCaptor.forClass(ManufactureRate.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getMatchLevel()).isEqualTo("DIVISION_PRODUCT_NAME");
    assertThat(captor.getValue().getMatchKey()).isEqualTo("四通阀事业部::产品C");
  }

  @Test
  @DisplayName("制造费用率导入：只有事业部时按事业部级配置插入")
  void importItemsInsertsDivisionLevel() {
    ManufactureRateMapper mapper = mock(ManufactureRateMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(mapper.insert(any(ManufactureRate.class))).thenReturn(1);

    ManufactureRateImportRequest.ManufactureRateRow row = row(5, null, null, null);
    ManufactureRateServiceImpl service = new ManufactureRateServiceImpl(mapper);
    ManufactureRateImportResponse result = service.importItems(request(row));

    assertThat(result.getInserted()).isEqualTo(1);
    ArgumentCaptor<ManufactureRate> captor = ArgumentCaptor.forClass(ManufactureRate.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getMatchLevel()).isEqualTo("DIVISION");
    assertThat(captor.getValue().getMatchKey()).isEqualTo("四通阀事业部");
  }

  @Test
  @DisplayName("制造费用率导入：缺制造费用率时跳过并返回行号")
  void importItemsRejectsMissingRate() {
    ManufactureRateMapper mapper = mock(ManufactureRateMapper.class);

    ManufactureRateImportRequest.ManufactureRateRow row = row(6, "P-001", null, null);
    row.setFeeRate(null);
    ManufactureRateServiceImpl service = new ManufactureRateServiceImpl(mapper);
    ManufactureRateImportResponse result = service.importItems(request(row));

    assertThat(result.getInserted()).isZero();
    assertThat(result.getSkipped()).isEqualTo(1);
    assertThat(result.getErrors()).isEqualTo(1);
    assertThat(result.getErrorMessages()).containsExactly("Excel第6行缺制造费用率");
    verify(mapper, never()).insert(any(ManufactureRate.class));
  }

  private ManufactureRateImportRequest request(ManufactureRateImportRequest.ManufactureRateRow row) {
    ManufactureRateImportRequest request = new ManufactureRateImportRequest();
    request.setRateYear(2026);
    request.setBusinessUnitType("COMMERCIAL");
    request.setRows(List.of(row));
    return request;
  }

  private ManufactureRateImportRequest.ManufactureRateRow row(
      int rowNo, String productCode, String productModel, String productName) {
    ManufactureRateImportRequest.ManufactureRateRow row =
        new ManufactureRateImportRequest.ManufactureRateRow();
    row.setRowNo(rowNo);
    row.setBusinessDivision("四通阀事业部");
    row.setProductCode(productCode);
    row.setProductName(productName);
    row.setProductModel(productModel);
    row.setProductSpec("SPEC");
    row.setFeeRate(new BigDecimal("0.050000"));
    row.setRemark("测试");
    return row;
  }
}
