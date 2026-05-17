package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.FactorLinkedItemReferenceDto;
import com.sanhua.marketingcost.mapper.PriceVariableBindingMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FactorLinkedItemReferenceServiceImplTest {

  private PriceVariableBindingMapper bindingMapper;
  private FactorLinkedItemReferenceServiceImpl service;

  @BeforeEach
  void setUp() {
    bindingMapper = mock(PriceVariableBindingMapper.class);
    service = new FactorLinkedItemReferenceServiceImpl(bindingMapper);
  }

  @Test
  @DisplayName("有引用：按影响因素身份、月份、业务单元查询联动价行")
  void listLinkedItemsWithReferences() {
    FactorLinkedItemReferenceDto ref = ref(11L, "2026-05", "COMMERCIAL", "203259729");
    when(bindingMapper.findLinkedItemReferencesByFactorIdentity(191L, "2026-05", "COMMERCIAL"))
        .thenReturn(List.of(ref));

    List<FactorLinkedItemReferenceDto> rows =
        service.listLinkedItems(191L, " 2026-05 ", " COMMERCIAL ");

    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().getMaterialCode()).isEqualTo("203259729");
    verify(bindingMapper).findLinkedItemReferencesByFactorIdentity(
        191L, "2026-05", "COMMERCIAL");
  }

  @Test
  @DisplayName("无引用：返回空列表")
  void listLinkedItemsWithoutReferences() {
    when(bindingMapper.findLinkedItemReferencesByFactorIdentity(192L, "2026-05", "COMMERCIAL"))
        .thenReturn(List.of());

    List<FactorLinkedItemReferenceDto> rows =
        service.listLinkedItems(192L, "2026-05", "COMMERCIAL");

    assertThat(rows).isEmpty();
  }

  @Test
  @DisplayName("跨业务单元隔离：业务单元参数原样传入 Mapper")
  void listLinkedItemsIsolatedByBusinessUnit() {
    when(bindingMapper.findLinkedItemReferencesByFactorIdentity(191L, "2026-05", "HOUSEHOLD"))
        .thenReturn(List.of(ref(12L, "2026-05", "HOUSEHOLD", "H-001")));

    List<FactorLinkedItemReferenceDto> rows =
        service.listLinkedItems(191L, "2026-05", "HOUSEHOLD");

    assertThat(rows).extracting(FactorLinkedItemReferenceDto::getBusinessUnitType)
        .containsExactly("HOUSEHOLD");
    verify(bindingMapper).findLinkedItemReferencesByFactorIdentity(
        191L, "2026-05", "HOUSEHOLD");
  }

  @Test
  @DisplayName("缺少影响因素身份直接拒绝")
  void listLinkedItemsRejectsMissingFactorIdentityId() {
    assertThatThrownBy(() -> service.listLinkedItems(null, "2026-05", "COMMERCIAL"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("factorIdentityId 必填");
  }

  private static FactorLinkedItemReferenceDto ref(
      Long linkedItemId, String pricingMonth, String businessUnitType, String materialCode) {
    FactorLinkedItemReferenceDto dto = new FactorLinkedItemReferenceDto();
    dto.setBindingId(linkedItemId + 1000);
    dto.setLinkedItemId(linkedItemId);
    dto.setFactorIdentityId(191L);
    dto.setPricingMonth(pricingMonth);
    dto.setBusinessUnitType(businessUnitType);
    dto.setMaterialCode(materialCode);
    dto.setMaterialName("弹簧片");
    dto.setSupplierName("供应商");
    dto.setFormulaExpr("[blank_weight]*[factor_identity_191]");
    dto.setFormulaExprCn("下料重量 * 1#Mn");
    dto.setTokenName("材料含税价格");
    dto.setBindingSource("EXCEL_INFERRED");
    return dto;
  }
}
