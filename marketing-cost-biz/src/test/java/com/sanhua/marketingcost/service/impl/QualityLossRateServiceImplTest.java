package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
  @DisplayName("质量损失率导入：按 A:J 业务字段批量写入裸品规则")
  void importItemsInsertsBareProductRule() {
    QualityLossRateMapper mapper = mock(QualityLossRateMapper.class);
    when(mapper.selectList(any())).thenReturn(List.of());

    QualityLossRateServiceImpl service = new QualityLossRateServiceImpl(mapper);
    QualityLossRateImportResponse result = service.importItems(request(row(2, "102053856", "0.0025")));

    assertThat(result.getInserted()).isEqualTo(1);
    assertThat(result.getUpdated()).isZero();
    assertThat(result.getSourceBatchNo()).startsWith("QUALITY_LOSS_");
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<QualityLossRate>> captor = ArgumentCaptor.forClass(List.class);
    verify(mapper).upsertBatch(captor.capture());
    QualityLossRate inserted = captor.getValue().get(0);
    assertThat(inserted.getRateYear()).isEqualTo(2026);
    assertThat(inserted.getBareProductCode()).isEqualTo("102053856");
    assertThat(inserted.getMaterialSpec()).isEqualTo("SPEC-C");
    assertThat(inserted.getCategorySpec()).isEqualTo("SPEC-H");
    assertThat(inserted.getFourthLevel()).isEqualTo("四级值");
    assertThat(inserted.getSourceType()).isEqualTo("EXCEL_IMPORT");
  }

  @Test
  @DisplayName("质量损失率导入：未报价和公式错误对应的空损失率直接跳过")
  void importItemsSkipsRowsWithoutEffectiveRate() {
    QualityLossRateMapper mapper = mock(QualityLossRateMapper.class);
    QualityLossRateImportRequest.QualityLossRateRow invalid = row(3, "102053857", null);

    QualityLossRateImportResponse result =
        new QualityLossRateServiceImpl(mapper).importItems(request(invalid));

    assertThat(result.getSkipped()).isEqualTo(1);
    assertThat(result.getErrors()).isZero();
    verify(mapper, never()).upsertBatch(any());
  }

  @Test
  @DisplayName("质量损失率导入：同一文件裸品料号重复时拒绝重复行")
  void importItemsRejectsDuplicateBareProductCode() {
    QualityLossRateMapper mapper = mock(QualityLossRateMapper.class);
    when(mapper.selectList(any())).thenReturn(List.of());
    QualityLossRateImportRequest request = new QualityLossRateImportRequest();
    request.setRateYear(2026);
    request.setRows(List.of(row(2, "102053856", "0.0025"), row(9, "102053856", "0.0030")));

    QualityLossRateImportResponse result =
        new QualityLossRateServiceImpl(mapper).importItems(request);

    assertThat(result.getInserted()).isEqualTo(1);
    assertThat(result.getSkipped()).isEqualTo(1);
    assertThat(result.getErrorMessages()).containsExactly("Excel第9行裸品料号重复：102053856");
  }

  @Test
  @DisplayName("质量损失率导入：导入覆盖已手工修正的同年度裸品规则")
  void importItemsCountsExistingRuleAsUpdated() {
    QualityLossRate existing = new QualityLossRate();
    existing.setBareProductCode("102053856");
    QualityLossRateMapper mapper = mock(QualityLossRateMapper.class);
    when(mapper.selectList(any())).thenReturn(List.of(existing));

    QualityLossRateImportResponse result =
        new QualityLossRateServiceImpl(mapper)
            .importItems(request(row(5, "102053856", "0.00475")));

    assertThat(result.getInserted()).isZero();
    assertThat(result.getUpdated()).isEqualTo(1);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<QualityLossRate>> captor = ArgumentCaptor.forClass(List.class);
    verify(mapper).upsertBatch(captor.capture());
    assertThat(captor.getValue().get(0).getLossRate()).isEqualByComparingTo("0.00475");
  }

  private QualityLossRateImportRequest request(QualityLossRateImportRequest.QualityLossRateRow row) {
    QualityLossRateImportRequest request = new QualityLossRateImportRequest();
    request.setRateYear(2026);
    request.setRows(List.of(row));
    return request;
  }

  private QualityLossRateImportRequest.QualityLossRateRow row(
      int rowNo, String bareProductCode, String lossRate) {
    QualityLossRateImportRequest.QualityLossRateRow row =
        new QualityLossRateImportRequest.QualityLossRateRow();
    row.setRowNo(rowNo);
    row.setBareProductCode(bareProductCode);
    row.setProductName("测试品名");
    row.setMaterialSpec("SPEC-C");
    row.setProductModel("MODEL-D");
    row.setBusinessDivision("商用四通阀");
    row.setProductCategory("大类F");
    row.setProductSubcategory("小类G");
    row.setCategorySpec("SPEC-H");
    row.setFourthLevel("四级值");
    row.setLossRate(lossRate == null ? null : new BigDecimal(lossRate));
    return row;
  }
}
