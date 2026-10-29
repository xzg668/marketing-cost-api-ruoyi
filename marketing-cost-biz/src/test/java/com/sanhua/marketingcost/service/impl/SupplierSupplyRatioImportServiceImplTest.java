package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.supplier.SupplierSupplyRatioImportLock;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class SupplierSupplyRatioImportServiceImplTest {
  SupplierSupplyRatioMapper mapper; BusinessChangeLogMapper logs; ApplicationEventPublisher events;
  SupplierSupplyRatioImportLock lock; SupplierSupplyRatioImportServiceImpl service;
  @BeforeEach void setup() {
    mapper=mock(SupplierSupplyRatioMapper.class);logs=mock(BusinessChangeLogMapper.class);
    lock=mock(SupplierSupplyRatioImportLock.class);events=mock(ApplicationEventPublisher.class);
    service=new SupplierSupplyRatioImportServiceImpl(mapper,new SupplierSupplyRatioWorkbookParserImpl(),events,logs,lock,new ObjectMapper());
    when(mapper.insert(any(SupplierSupplyRatio.class))).thenAnswer(i->{i.<SupplierSupplyRatio>getArgument(0).setId(42L);return 1;});
    when(logs.insert(any(BusinessChangeLog.class))).thenReturn(1);
    var auth=new UsernamePasswordAuthenticationToken("finance",null);auth.setDetails(Map.of("businessUnitType","COMMERCIAL"));SecurityContextHolder.getContext().setAuthentication(auth);
  }
  @AfterEach void clear(){SecurityContextHolder.clearContext();}
  static SupplierSupplyRatioExcelRow row(String supplier,String ratio) {
    var r=new SupplierSupplyRatioExcelRow();r.setMaterialCode("000M");r.setMaterialName("物料");r.setSupplierCode(supplier);r.setSupplierName("供应商"+supplier);r.setSpecModel("SPEC");r.setSupplyRatio(ratio==null?null:new BigDecimal(ratio));r.setRowNo(2);return r;
  }
  static SupplierSupplyRatio previous(String ratio) {
    var p=new SupplierSupplyRatio();p.setId(1L);p.setMaterialCode("000M");p.setSupplierCode("000A");p.setMaterialName("物料");p.setSupplierName("供应商000A");p.setSpecModel("SPEC");p.setSupplyRatio(ratio==null?null:new BigDecimal(ratio));return p;
  }
  @Test void identicalContentDoesNotWriteOrPublishARetryEvenWithDifferentScale() {
    when(mapper.currentRelation("COMMERCIAL","000M","000A")).thenReturn(List.of(previous("0.6")));
    var result=service.importRows(List.of(row("000A","0.600000")),"same.xlsx","COMMERCIAL","finance");
    assertThat(result.getUnchangedRows()).isEqualTo(1);assertThat(result.getBatchNo()).isNull();
    verify(mapper,never()).insert(any(SupplierSupplyRatio.class));verifyNoInteractions(logs,events);
  }
  @Test void addingSupplierDoesNotRetireAnotherRelationship() {
    var result=service.importRows(List.of(row("000C","0.2")),"add.xlsx","COMMERCIAL","finance");
    assertThat(result.getNewRelations()).isEqualTo(1);assertThat(result.getChangedRelations()).isZero();
    verify(mapper,never()).retireVersion(any(),anyLong(),any(),any());
    var capture=ArgumentCaptor.forClass(BusinessChangeLog.class);verify(logs).insert(capture.capture());
    assertThat(capture.getValue().getBizId()).isEqualTo(42);assertThat(capture.getValue().getBeforeValue()).isNull();
    assertThat(capture.getValue().getAfterValue()).contains("000C","add.xlsx");verify(events).publishEvent(any(Object.class));
  }
  @Test void zeroAndNullAreDifferentChangesAndCarryOldAndNewVersionIds() {
    when(mapper.currentRelation("COMMERCIAL","000M","000A")).thenReturn(List.of(previous(null)));
    when(mapper.retireVersion(eq("COMMERCIAL"),eq(1L),any(),any())).thenReturn(1);
    var result=service.importRows(List.of(row("000A","0")),"zero.xlsx","COMMERCIAL","finance");
    assertThat(result.getChangedRelations()).isEqualTo(1);
    var capture=ArgumentCaptor.forClass(BusinessChangeLog.class);verify(logs).insert(capture.capture());
    assertThat(capture.getValue().getBizDetailId()).isEqualTo(1);assertThat(capture.getValue().getBeforeValue()).contains("\"supplyRatio\":null");assertThat(capture.getValue().getAfterValue()).contains("\"supplyRatio\":0");
  }
  @Test void identicalDuplicatesCollapseAndConflictingDuplicatesRejectBeforeLock() {
    var result=service.importRows(List.of(row("000A","0.5"),row("000A","0.50")),"dup.xlsx","COMMERCIAL","finance");
    assertThat(result.getDuplicateRows()).isEqualTo(1);assertThat(result.getInsertedRows()).isEqualTo(1);
    reset(lock,mapper,logs,events);
    assertThatThrownBy(()->service.importRows(List.of(row("000A","0.5"),row("000A","0.4")),"bad.xlsx","COMMERCIAL","finance")).hasMessageContaining("冲突");
    verifyNoInteractions(lock,mapper,logs,events);
  }
  @Test void invalidRatioPrecisionAndCrossUnitAreRejectedBeforeWriting() {
    for(String ratio:List.of("-0.1","1.1","0.1234567")) assertThatThrownBy(()->service.importRows(List.of(row("A",ratio)),"bad.xlsx","COMMERCIAL","finance")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->service.importRows(List.of(row("A","0.1")),"other.xlsx","HOUSEHOLD","finance")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    verifyNoInteractions(lock,mapper,logs,events);
  }
  @Test void zeroRowsAndMissingNamesCannotEraseExistingData() {
    assertThatThrownBy(()->service.importRows(List.of(),"empty.xlsx","COMMERCIAL","finance")).hasMessageContaining("原数据未变更");
    var missingName=row("  ","0.1");missingName.setSupplierName(" ");
    assertThatThrownBy(()->service.importRows(List.of(missingName),"bad.xlsx","COMMERCIAL","finance")).hasMessageContaining("供应商名称");
    verifyNoInteractions(lock,mapper,logs,events);
  }

  @Test void unnamedSuppliersRemainDistinctAndIdenticalNameDuplicatesCollapse() {
    var a=row(null,"0.6");a.setSupplierName("甲供应商");
    var b=row(null,"0.4");b.setSupplierName("乙供应商");
    var result=service.importRows(List.of(a,b,a),"names.xlsx","COMMERCIAL","finance");
    assertThat(result.getInsertedRows()).isEqualTo(2);
    assertThat(result.getDuplicateRows()).isEqualTo(1);
    var capture=ArgumentCaptor.forClass(SupplierSupplyRatio.class);verify(mapper,times(2)).insert(capture.capture());
    assertThat(capture.getAllValues()).allSatisfy(v->assertThat(v.getSupplierCode()).isNull());
  }

  @Test void missingCodeMatchesExistingNameWithoutDroppingItsKnownCode() {
    var a=row(null,"0.6");a.setSupplierName("供应商000A");
    when(mapper.currentRelationByName("COMMERCIAL","000M","供应商000A",false)).thenReturn(List.of(previous("0.6")));
    assertThat(service.importRows(List.of(a),"names.xlsx","COMMERCIAL","finance").getUnchangedRows()).isEqualTo(1);
    verify(mapper,never()).insert(any(SupplierSupplyRatio.class));
  }

  @Test void mixedCodeAndNameConflictsRejectBeforeWriting() {
    var a=row("000A","0.6");var b=row(null,"0.4");b.setSupplierName(a.getSupplierName());
    assertThatThrownBy(()->service.importRows(List.of(a,b),"bad.xlsx","COMMERCIAL","finance")).hasMessageContaining("冲突");
    verifyNoInteractions(lock,mapper,logs,events);
  }
}
