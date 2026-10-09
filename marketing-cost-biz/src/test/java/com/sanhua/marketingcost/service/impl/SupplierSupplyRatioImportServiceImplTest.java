package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioExcelRow;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SupplierSupplyRatioImportServiceImplTest {
  private SupplierSupplyRatioMapper mapper;
  private SupplierSupplyRatioImportServiceImpl service;

  @BeforeEach void setUp() {
    mapper = mock(SupplierSupplyRatioMapper.class);
    service = new SupplierSupplyRatioImportServiceImpl(mapper, new SupplierSupplyRatioWorkbookParserImpl(), org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher.class));
  }

  @Test void replacesWholeMaterialGroupWithoutOverwritingHistoryOrOtherMaterials() {
    when(mapper.deactivateMaterial(eq("COMMERCIAL"), eq("M1"), eq("alice"), any())).thenReturn(4);
    var result = service.importRows(List.of(row("M1", "S1", "0.7"), row("M1", "S2", "0.7")),
        "ratio.xlsx", "COMMERCIAL", "alice");
    assertThat(result.getInsertedRows()).isEqualTo(2);
    assertThat(result.getDeactivatedRows()).isEqualTo(4);
    var order = inOrder(mapper);
    order.verify(mapper).lockMaterial("COMMERCIAL", "M1");
    order.verify(mapper).deactivateMaterial(eq("COMMERCIAL"), eq("M1"), eq("alice"), any());
    ArgumentCaptor<SupplierSupplyRatio> capture = ArgumentCaptor.forClass(SupplierSupplyRatio.class);
    verify(mapper, times(2)).insert(capture.capture());
    assertThat(capture.getAllValues()).allSatisfy(r -> {
      assertThat(r.getIsActive()).isEqualTo(1);
      assertThat(r.getSourceBatchNo()).isEqualTo(result.getBatchNo());
      assertThat(r.getImportedAt()).isNotNull();
      assertThat(r.getSupplyRatio()).isEqualByComparingTo("0.7");
    });
    verify(mapper, never()).deactivateMaterial(eq("COMMERCIAL"), eq("M2"), anyString(), any());
    verify(mapper, never()).updateById(any(SupplierSupplyRatio.class));
  }

  @Test void repeatedImportsCreateDistinctHistoricalBatches() {
    var first = service.importRows(List.of(row("M1", "S1", "0.6")), "a.xlsx", "COMMERCIAL", "a");
    var second = service.importRows(List.of(row("M1", "S1", "0.8")), "b.xlsx", "COMMERCIAL", "b");
    assertThat(first.getBatchNo()).isNotEqualTo(second.getBatchNo());
    verify(mapper, times(2)).insert(any(SupplierSupplyRatio.class));
    verify(mapper, never()).updateById(any(SupplierSupplyRatio.class));
  }

  @Test void identicalDuplicatesOnlyInsertOnce() {
    var result = service.importRows(List.of(row("M1", "S1", "0.65"), row("M1", "S1", "0.65")),
        "duplicate.xlsx", "COMMERCIAL", "a");
    assertThat(result.getInsertedRows()).isEqualTo(1);
    assertThat(result.getDuplicateRows()).isEqualTo(1);
  }

  @Test void conflictingDuplicatesRejectEntireFileBeforeAnyChanges() {
    assertThatThrownBy(() -> service.importRows(
        List.of(row("M1", "S1", "0.3"), row("M2", "S2", "0.4"), row("M2", "S2", "0.6")),
        "conflict.xlsx", "COMMERCIAL", "a")).hasMessageContaining("冲突");
    verifyNoInteractions(mapper);
  }

  @Test void invalidRatioDoesNotSilentlyBecomeZero() {
    for (String value : new String[] {"-0.1", "1.1"}) {
      assertThatThrownBy(() -> service.importRows(List.of(row("M1", "S1", value)),
          "invalid.xlsx", "COMMERCIAL", "a")).isInstanceOf(IllegalArgumentException.class);
    }
    verifyNoInteractions(mapper);
  }

  @Test void nullRatioIsInsertedAndDistinguishedFromZeroDuringDeduplication() {
    var result = service.importRows(List.of(row("M1", "S1", null), row("M1", "S1", null),
        row("M1", "S2", "0")), "a.xlsx", "COMMERCIAL", "a");
    var records = ArgumentCaptor.forClass(SupplierSupplyRatio.class);
    verify(mapper, times(2)).insert(records.capture());
    assertThat(result.getInsertedRows()).isEqualTo(2);
    assertThat(result.getUnfilledRatioRows()).isEqualTo(1);
    assertThat(result.getDuplicateRows()).isEqualTo(1);
    assertThat(records.getAllValues().get(0).getSupplyRatio()).isNull();
    assertThat(records.getAllValues().get(1).getSupplyRatio()).isZero();
    clearInvocations(mapper);
    assertThatThrownBy(() -> service.importRows(List.of(row("M1", "S1", null),
        row("M1", "S1", "0")), "conflict.xlsx", "COMMERCIAL", "a"))
        .hasMessageContaining("冲突");
    verifyNoInteractions(mapper);
  }

  @Test void locksMaterialsInStableOrder() {
    service.importRows(List.of(row("M2", "S1", "0"), row("M1", "S1", "1")),
        "ordered.xlsx", "PLATE", "a");
    var order = inOrder(mapper);
    order.verify(mapper).lockMaterial("PLATE", "M1");
    order.verify(mapper).lockMaterial("PLATE", "M2");
  }

  private SupplierSupplyRatioExcelRow row(String material, String supplier, String ratio) {
    var row = new SupplierSupplyRatioExcelRow();
    row.setRowNo(2);
    row.setMaterialCode(material);
    row.setMaterialName("物料");
    row.setSupplierCode(supplier);
    row.setSupplierName("供应商" + supplier);
    row.setSpecModel("规格");
    row.setSupplyRatio(ratio == null ? null : new BigDecimal(ratio));
    return row;
  }
}
