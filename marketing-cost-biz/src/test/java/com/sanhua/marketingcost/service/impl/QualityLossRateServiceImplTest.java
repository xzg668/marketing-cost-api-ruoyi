package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.QualityLossRateImportRequest;
import com.sanhua.marketingcost.dto.QualityLossRateImportResponse;
import com.sanhua.marketingcost.entity.QualityLossRate;
import com.sanhua.marketingcost.mapper.QualityLossRateMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QualityLossRateServiceImplTest {

  @Test
  @DisplayName("净损失率导入：有料号按料号级配置插入")
  void importItemsInsertsMaterialCodeLevel() {
    QualityLossRateMapper mapper = mock(QualityLossRateMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(mapper.insert(any(QualityLossRate.class))).thenReturn(1);

    QualityLossRateServiceImpl service = new QualityLossRateServiceImpl(mapper);
    QualityLossRateImportResponse result = service.importItems(request(row(2, "P-001", "MODEL-A")));

    assertThat(result.getInserted()).isEqualTo(1);
    assertThat(result.getUpdated()).isZero();
    ArgumentCaptor<QualityLossRate> captor = ArgumentCaptor.forClass(QualityLossRate.class);
    verify(mapper).insert(captor.capture());
    QualityLossRate inserted = captor.getValue();
    assertThat(inserted.getRateYear()).isEqualTo(2026);
    assertThat(inserted.getMatchLevel()).isEqualTo("MATERIAL_CODE");
    assertThat(inserted.getMatchKey()).isEqualTo("P-001");
    assertThat(inserted.getPeriod()).isEqualTo("2026-01");
    assertThat(inserted.getCompany()).isEmpty();
    assertThat(inserted.getProductSubcategory()).isEmpty();
  }

  @Test
  @DisplayName("净损失率导入：无料号但有型号按型号级配置插入")
  void importItemsInsertsMaterialModelLevel() {
    QualityLossRateMapper mapper = mock(QualityLossRateMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(mapper.insert(any(QualityLossRate.class))).thenReturn(1);

    QualityLossRateServiceImpl service = new QualityLossRateServiceImpl(mapper);
    QualityLossRateImportResponse result = service.importItems(request(row(3, null, "MODEL-B")));

    assertThat(result.getInserted()).isEqualTo(1);
    ArgumentCaptor<QualityLossRate> captor = ArgumentCaptor.forClass(QualityLossRate.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getMatchLevel()).isEqualTo("MATERIAL_MODEL");
    assertThat(captor.getValue().getMatchKey()).isEqualTo("MODEL-B");
  }

  @Test
  @DisplayName("净损失率导入：料号和型号都没有时跳过并返回 Excel 行号")
  void importItemsRejectsMissingMaterialCodeAndModel() {
    QualityLossRateMapper mapper = mock(QualityLossRateMapper.class);

    QualityLossRateServiceImpl service = new QualityLossRateServiceImpl(mapper);
    QualityLossRateImportResponse result = service.importItems(request(row(4, null, null)));

    assertThat(result.getInserted()).isZero();
    assertThat(result.getSkipped()).isEqualTo(1);
    assertThat(result.getErrors()).isEqualTo(1);
    assertThat(result.getErrorMessages()).containsExactly("Excel第4行缺产品料号或产品型号");
    verify(mapper, never()).insert(any(QualityLossRate.class));
  }

  @Test
  @DisplayName("净损失率导入：同年度同业务单元同匹配键更新既有配置")
  void importItemsUpdatesExistingMatchKey() {
    QualityLossRate existing = new QualityLossRate();
    existing.setId(1L);
    existing.setRateYear(2026);
    existing.setBusinessUnitType("COMMERCIAL");
    existing.setMatchLevel("MATERIAL_CODE");
    existing.setMatchKey("P-001");
    existing.setLossRate(new BigDecimal("0.010000"));

    QualityLossRateMapper mapper = mock(QualityLossRateMapper.class);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);
    when(mapper.updateById(any(QualityLossRate.class))).thenReturn(1);

    QualityLossRateServiceImpl service = new QualityLossRateServiceImpl(mapper);
    QualityLossRateImportResponse result = service.importItems(request(row(5, "P-001", "MODEL-A")));

    assertThat(result.getInserted()).isZero();
    assertThat(result.getUpdated()).isEqualTo(1);
    ArgumentCaptor<QualityLossRate> captor = ArgumentCaptor.forClass(QualityLossRate.class);
    verify(mapper).updateById(captor.capture());
    assertThat(captor.getValue().getLossRate()).isEqualByComparingTo("0.012000");
    assertThat(captor.getValue().getProductModel()).isEqualTo("MODEL-A");
  }

  private QualityLossRateImportRequest request(QualityLossRateImportRequest.QualityLossRateRow row) {
    QualityLossRateImportRequest request = new QualityLossRateImportRequest();
    request.setRateYear(2026);
    request.setBusinessUnitType("COMMERCIAL");
    request.setRows(List.of(row));
    return request;
  }

  private QualityLossRateImportRequest.QualityLossRateRow row(
      int rowNo, String productCode, String productModel) {
    QualityLossRateImportRequest.QualityLossRateRow row =
        new QualityLossRateImportRequest.QualityLossRateRow();
    row.setRowNo(rowNo);
    row.setBusinessDivision("四通阀事业部");
    row.setProductCategory("热力膨胀阀");
    row.setProductCode(productCode);
    row.setProductName("测试产品");
    row.setProductModel(productModel);
    row.setProductSpec("SPEC");
    row.setLossRate(new BigDecimal("0.012000"));
    row.setRemark("测试");
    return row;
  }
}
