package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.CmsAuxSubjectDeriveResponse;
import com.sanhua.marketingcost.entity.AuxSubject;
import com.sanhua.marketingcost.entity.CmsCostDeriveLog;
import com.sanhua.marketingcost.entity.CmsCostImportBatch;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.mapper.AuxSubjectMapper;
import com.sanhua.marketingcost.mapper.CmsCostDeriveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostImportBatchMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CmsAuxSubjectDeriveServiceImplTest {
  private CmsCostImportBatchMapper batchMapper;
  private CmsProductSubjectCostRawMapper subjectMapper;
  private AuxSubjectMapper auxSubjectMapper;
  private CmsCostDeriveLogMapper deriveLogMapper;
  private CmsAuxSubjectDeriveServiceImpl service;

  @BeforeEach
  void setUp() {
    batchMapper = mock(CmsCostImportBatchMapper.class);
    subjectMapper = mock(CmsProductSubjectCostRawMapper.class);
    auxSubjectMapper = mock(AuxSubjectMapper.class);
    deriveLogMapper = mock(CmsCostDeriveLogMapper.class);
    service =
        new CmsAuxSubjectDeriveServiceImpl(
            batchMapper, subjectMapper, auxSubjectMapper, deriveLogMapper);
    when(batchMapper.selectById(10L)).thenReturn(batch(10L));
  }

  @Test
  void derivesAuxSubjectFromEarliestPeriodAndConvertsCentToYuan() {
    when(subjectMapper.selectList(any()))
        .thenReturn(
            List.of(
                subject("A", "2026-01", "0201", "辅助焊料类", "20.000000"),
                subject("A", "2026-01", "0201", "辅助焊料类", "20.000000"),
                subject("A", "2026-03", "0201", "辅助焊料类", "999.000000")));

    CmsAuxSubjectDeriveResponse response = service.deriveAuxSubjects(10L);

    assertThat(response.getAuxInsertCount()).isEqualTo(1);
    assertThat(response.getAuxSkipCount()).isZero();

    ArgumentCaptor<AuxSubject> auxCaptor = ArgumentCaptor.forClass(AuxSubject.class);
    verify(auxSubjectMapper).insert(auxCaptor.capture());
    AuxSubject aux = auxCaptor.getValue();
    assertThat(aux.getMaterialCode()).isEqualTo("A");
    assertThat(aux.getAuxSubjectCode()).isEqualTo("0201");
    assertThat(aux.getAuxSubjectName()).isEqualTo("辅助焊料类");
    assertThat(aux.getUnitPrice()).isEqualByComparingTo("0.400000");
    assertThat(aux.getPeriod()).isEqualTo("2026-01");
    assertThat(aux.getSource()).isEqualTo("CMS");
    assertThat(aux.getAmountCalcMode()).isEqualTo("DIRECT");
    assertThat(aux.getLockStatus()).isEqualTo("LOCKED");
    assertThat(aux.getBusinessUnitType()).isEqualTo("COMMERCIAL");

    ArgumentCaptor<CmsCostDeriveLog> logCaptor = ArgumentCaptor.forClass(CmsCostDeriveLog.class);
    verify(deriveLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getStatus()).isEqualTo("INSERTED");
    assertThat(logCaptor.getValue().getSubjectCode()).isEqualTo("0201");
    assertThat(logCaptor.getValue().getSubjectName()).isEqualTo("辅助焊料类");
    assertThat(logCaptor.getValue().getAmount()).isEqualByComparingTo("0.400000");

    ArgumentCaptor<CmsCostImportBatch> batchCaptor = ArgumentCaptor.forClass(CmsCostImportBatch.class);
    verify(batchMapper).updateById(batchCaptor.capture());
    assertThat(batchCaptor.getValue().getAuxInsertCount()).isEqualTo(1);
  }

  @Test
  void skipsExistingCmsAuxSubjectWithoutOverwritingLaterMonthChange() {
    when(subjectMapper.selectList(any()))
        .thenReturn(
            List.of(
                subject("A", "2026-01", "0201", "辅助焊料类", "40.000000"),
                subject("A", "2026-03", "0201", "辅助焊料类", "999.000000")));
    AuxSubject existing = new AuxSubject();
    existing.setId(99L);
    existing.setMaterialCode("A");
    existing.setAuxSubjectCode("0201");
    existing.setSource("CMS");
    when(auxSubjectMapper.selectOne(any())).thenReturn(existing);

    CmsAuxSubjectDeriveResponse response = service.deriveAuxSubjects(10L);

    assertThat(response.getAuxInsertCount()).isZero();
    assertThat(response.getAuxSkipCount()).isEqualTo(1);
    verify(auxSubjectMapper, never()).insert(any(AuxSubject.class));
    ArgumentCaptor<CmsCostDeriveLog> logCaptor = ArgumentCaptor.forClass(CmsCostDeriveLog.class);
    verify(deriveLogMapper).insert(logCaptor.capture());
    assertThat(logCaptor.getValue().getStatus()).isEqualTo("SKIPPED");
    assertThat(logCaptor.getValue().getPeriod()).isEqualTo("2026-01");
    assertThat(logCaptor.getValue().getAmount()).isEqualByComparingTo("0.400000");
  }

  @Test
  void ignoresPackagingAuxSubjectAndNonAuxMaterialSubject() {
    CmsProductSubjectCostRaw packaging = subject("A", "2026-01", "0215", "包装辅料", "2.000000");
    CmsProductSubjectCostRaw nonAuxMaterial = subject("A", "2026-01", "0201", "辅助焊料类", "40.000000");
    nonAuxMaterial.setFirstSubjectName("主要材料");
    when(subjectMapper.selectList(any())).thenReturn(List.of(packaging, nonAuxMaterial));

    CmsAuxSubjectDeriveResponse response = service.deriveAuxSubjects(10L);

    assertThat(response.getAuxInsertCount()).isZero();
    assertThat(response.getAuxSkipCount()).isZero();
    verify(auxSubjectMapper, never()).insert(any(AuxSubject.class));
    verify(deriveLogMapper, never()).insert(any(CmsCostDeriveLog.class));
  }

  @Test
  void derivesMultipleNonPackagingSubjectsIndependently() {
    when(subjectMapper.selectList(any()))
        .thenReturn(
            List.of(
                subject("A", "2026-01", "0201", "辅助焊料类", "40.000000"),
                subject("A", "2026-01", "0202", "表面处理类", "60.000000"),
                subject("A", "2026-01", "0215", "包装辅料", "2.000000")));

    CmsAuxSubjectDeriveResponse response = service.deriveAuxSubjects(10L);

    assertThat(response.getAuxInsertCount()).isEqualTo(2);
    ArgumentCaptor<AuxSubject> auxCaptor = ArgumentCaptor.forClass(AuxSubject.class);
    verify(auxSubjectMapper, org.mockito.Mockito.times(2)).insert(auxCaptor.capture());
    assertThat(auxCaptor.getAllValues()).extracting(AuxSubject::getAuxSubjectCode)
        .containsExactly("0201", "0202");
    assertThat(auxCaptor.getAllValues()).extracting(AuxSubject::getUnitPrice)
        .containsExactly(new BigDecimal("0.400000"), new BigDecimal("0.600000"));
  }

  @Test
  void rejectsMissingBatch() {
    when(batchMapper.selectById(99L)).thenReturn(null);

    assertThatThrownBy(() -> service.deriveAuxSubjects(99L))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不存在");
  }

  private CmsCostImportBatch batch(Long id) {
    CmsCostImportBatch batch = new CmsCostImportBatch();
    batch.setId(id);
    batch.setBusinessUnitType("COMMERCIAL");
    return batch;
  }

  private CmsProductSubjectCostRaw subject(
      String parentCode, String period, String secondCode, String secondName, String materialPrice) {
    CmsProductSubjectCostRaw row = new CmsProductSubjectCostRaw();
    row.setImportBatchId(10L);
    row.setRowNo(4);
    row.setPeriod(period);
    row.setParentCode(parentCode);
    row.setParentName("四通换向阀阀体");
    row.setParentSpec("SHF-P35792-001");
    row.setParentType("SHF-35B-79-01(P)");
    row.setFirstSubjectName("辅助材料");
    row.setSecondSubjectCode(secondCode);
    row.setSecondSubjectName(secondName);
    row.setMaterialPrice(new BigDecimal(materialPrice));
    row.setBusinessUnitType("COMMERCIAL");
    return row;
  }
}
