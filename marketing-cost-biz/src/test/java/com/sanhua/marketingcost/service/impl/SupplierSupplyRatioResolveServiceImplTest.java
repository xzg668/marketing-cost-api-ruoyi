package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.entity.monthly.MonthlySupplierRatioInput;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SupplierSupplyRatioResolveServiceImplTest {
  final SupplierSupplyRatioMapper mapper=mock(SupplierSupplyRatioMapper.class);
  final SupplierSupplyRatioResolveServiceImpl service=new SupplierSupplyRatioResolveServiceImpl(mapper);
  MonthlySupplierRatioInput row(long id,String supplier,String ratio) {
    var r=new MonthlySupplierRatioInput();r.setRequestedMaterialCode("000M");r.setId(id);r.setBusinessUnitType("COMMERCIAL");r.setMaterialCode("000M");r.setSupplierCode(supplier);r.setSupplierName(supplier);r.setSupplyRatio(ratio==null?null:new BigDecimal(ratio));r.setIsActive(1);r.setDeleted(0);r.setUpdatedAt(LocalDateTime.of(2026,10,27,0,0));return r;
  }
  @Test void completeSetRetainsZeroAndNullButOnlyPositiveRatiosChoosePrimary() {
    var rows=List.of(row(1,"A","0"),row(2,"B",null),row(3,"C","0.6"));when(mapper.currentRelations(anyString(),nullable(LocalDate.class),anyCollection())).thenReturn(rows);
    var r=service.resolve("COMMERCIAL","000M","different name","different spec",LocalDate.of(2026,11,1));
    assertThat(r.getSupplierCode()).isEqualTo("C");assertThat(r.getCurrentRelations()).containsExactlyElementsOf(rows);
  }
  @Test void primaryTieKeepsItsExistingUpdateThenIdRule() {
    var a=row(1,"A","0.5");var b=row(2,"B","0.5");when(mapper.currentRelations(anyString(),nullable(LocalDate.class),anyCollection())).thenReturn(List.of(a,b));
    assertThat(service.resolve("COMMERCIAL","000M",null,null,null).getId()).isEqualTo(2L);
    a.setUpdatedAt(b.getUpdatedAt().plusDays(1));assertThat(service.resolve("COMMERCIAL","000M",null,null,null).getId()).isEqualTo(1L);
  }
  @Test void retiredPositiveCannotResurrectWhenCurrentIsZero() {
    var old=row(1,"A","0.9");old.setIsActive(0);var zero=row(2,"A","0");when(mapper.currentRelations(anyString(),nullable(LocalDate.class),anyCollection())).thenReturn(List.of(old,zero));
    var r=service.resolve("COMMERCIAL","000M",null,null,null);assertThat(r.isMatched()).isFalse();assertThat(r.getCurrentRelations()).containsExactly(zero);
  }
  @Test void absentRelationshipsRemainMissing() {
    assertThat(service.resolve("COMMERCIAL","000M",null,null,null).isMatched()).isFalse();
    assertThat(service.resolve("COMMERCIAL","",null,null,null).isMatched()).isFalse();
  }
  @Test void legacyBoundsStillApplyWhileNewUndatedCurrentRowsApplyAcrossMonths() {
    var expired=row(1,"A","0.9");expired.setEffectiveTo(LocalDate.of(2026,9,30));var future=row(2,"B","0.8");future.setEffectiveFrom(LocalDate.of(2026,12,1));var current=row(3,"C","0.6");
    when(mapper.currentRelations(anyString(),nullable(LocalDate.class),anyCollection())).thenReturn(List.of(expired,future,current));
    assertThat(service.resolveByMonth("COMMERCIAL","000M",null,null,"2026-10").getId()).isEqualTo(3L);
    assertThat(service.resolveByMonth("COMMERCIAL","000M",null,null,"2026-11").getId()).isEqualTo(3L);
  }
  @Test void duplicateCurrentZeroAndPositiveAreRejectedInsteadOfPickingOldPositive() {
    when(mapper.currentRelations(anyString(),nullable(LocalDate.class),anyCollection())).thenReturn(List.of(row(1,"A","0.9"),row(2,"A","0")));
    assertThatThrownBy(()->service.resolve("COMMERCIAL","000M",null,null,null)).isInstanceOf(IllegalStateException.class);
  }
}
