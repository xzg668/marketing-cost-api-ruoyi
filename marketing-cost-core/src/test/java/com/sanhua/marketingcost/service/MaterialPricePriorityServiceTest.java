package com.sanhua.marketingcost.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.enums.MaterialPriceSourceType;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.service.MaterialPricePriorityService.Scope;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

class MaterialPricePriorityServiceTest {
  private final MaterialPriceTypeMapper mapper = mock(MaterialPriceTypeMapper.class);
  private final MaterialPricePriorityService service = new MaterialPricePriorityService(mapper, mock(org.springframework.transaction.PlatformTransactionManager.class));
  private final Scope scope = new Scope("COMMERCIAL", "2026-10", "210", "M1");

  @Test void missingGroupUsesFiveDefaultsWithoutWriting() {
    when(mapper.selectScope(scope, false)).thenReturn(List.of());
    var result = service.read(scope);
    assertEquals(List.of("LINKED", "SETTLE_LINKED", "RANGE", "SETTLE_FIXED", "FIXED"),
        result.order().stream().map(entry -> entry.type().name()).toList());
    assertFalse(result.manual());
    verify(mapper).selectScope(scope, false);
    verifyNoMoreInteractions(mapper);
  }

  @Test void initialDefaultsDoNotChangeInputRevision() {
    assertEquals(service.configuration(scope, List.of()).revision(),
        service.configuration(scope, rows()).revision());
  }

  @Test void monthOrganizationAndBusinessUnitHaveIndependentConfigurationIdentity() {
    var baseline = service.configuration(scope, List.of()).revision();
    for (var other : List.of(new Scope("COMMERCIAL", "2026-11", "210", "M1"),
        new Scope("COMMERCIAL", "2026-10", "220", "M1"),
        new Scope("HOUSEHOLD", "2026-10", "210", "M1"),
        new Scope("COMMERCIAL", "2026-10", "210", "M2"))) {
      assertNotEquals(baseline, service.configuration(other, List.of()).revision());
    }
  }

  @Test void financeOrderOverridesDefaultsAndResetChangesRevision() {
    var rows = rows();
    for (int i = 0; i < rows.size(); i++) {
      rows.get(i).setManualPriority(5 - i);
      rows.get(i).setPriorityUpdatedBy(9L);
      rows.get(i).setPriorityUpdatedAt(LocalDateTime.of(2026, 10, 13, 12, 0));
    }
    var custom = service.configuration(scope, rows);
    assertTrue(custom.manual());
    assertEquals(MaterialPriceSourceType.FIXED, custom.order().getFirst().type());
    rows.forEach(row -> row.setManualPriority(null));
    var reset = service.configuration(scope, rows);
    assertFalse(reset.manual());
    assertEquals(MaterialPriceSourceType.LINKED, reset.order().getFirst().type());
    assertNotEquals(custom.revision(), reset.revision());
    assertNotEquals(service.configuration(scope, List.of()).revision(), reset.revision());
  }

  @Test void generationUsesInsertOnlyAndNeverWritesManualValues() {
    when(mapper.selectScope(scope, false)).thenReturn(List.of(), rows());
    service.initialize(scope, "测试料号");
    verify(mapper, times(2)).selectScope(scope, false);
    verify(mapper).insertDefaults(scope, "测试料号", MaterialPriceSourceType.DEFAULT_ORDER);
    verifyNoMoreInteractions(mapper);
  }

  @Test void concurrentFinanceEditIsRejectedBeforeAnyWrites() {
    when(mapper.selectScope(scope, true)).thenReturn(rows());
    assertThrows(OptimisticLockingFailureException.class,
        () -> service.change(scope, MaterialPriceSourceType.DEFAULT_ORDER, "stale", 9L));
    verify(mapper).selectScope(scope, true);
    verifyNoMoreInteractions(mapper);
  }

  @Test void editRequiresAllFiveTypesAndOperator() {
    assertThrows(IllegalArgumentException.class, () -> service.change(scope, List.of(), "v", 9L));
    assertThrows(IllegalArgumentException.class, () -> service.change(scope,
        List.of(MaterialPriceSourceType.FIXED, MaterialPriceSourceType.FIXED,
            MaterialPriceSourceType.LINKED, MaterialPriceSourceType.RANGE, MaterialPriceSourceType.SETTLE_FIXED), "v", 9L));
    assertThrows(IllegalArgumentException.class, () -> service.change(scope, null, "v", null));
    verifyNoInteractions(mapper);
  }

  @Test void malformedStoredConfigurationFailsInsteadOfSilentlyTakingDefault() {
    var rows = rows();
    rows.removeLast();
    assertThrows(IllegalStateException.class, () -> service.configuration(scope, rows));
    var partial = rows();
    partial.getFirst().setManualPriority(5);
    assertThrows(IllegalStateException.class, () -> service.configuration(scope, partial));
    var duplicate = rows();
    duplicate.forEach(row -> row.setManualPriority(1));
    assertThrows(IllegalStateException.class, () -> service.configuration(scope, duplicate));
  }

  @Test void requiredScopeCannotFallBackToAnotherOrganizationOrMonth() {
    assertThrows(IllegalArgumentException.class, () -> new Scope("COMMERCIAL", "2026-10", "", "M1"));
    assertThrows(IllegalArgumentException.class, () -> new Scope(null, "2026-10", "210", "M1"));
    assertThrows(RuntimeException.class, () -> new Scope("COMMERCIAL", "2026-13", "210", "M1"));
  }

  private List<MaterialPriceType> rows() {
    var rows = new ArrayList<MaterialPriceType>();
    for (var type : MaterialPriceSourceType.DEFAULT_ORDER) {
      var row = new MaterialPriceType();
      row.setId((long) type.getDefaultPriority());
      row.setPriceType(type.name());
      row.setPriority(type.getDefaultPriority());
      rows.add(row);
    }
    return rows;
  }
}
