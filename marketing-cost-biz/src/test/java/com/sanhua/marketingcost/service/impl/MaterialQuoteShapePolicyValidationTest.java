package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyRequest;
import com.sanhua.marketingcost.entity.MaterialQuoteShapePolicy;
import com.sanhua.marketingcost.mapper.MaterialQuoteShapePolicyMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaterialQuoteShapePolicyValidationTest {

  private MaterialQuoteShapePolicyMapper mapper;
  private MaterialQuoteShapePolicyServiceImpl service;

  @BeforeEach
  void setUp() {
    mapper = mock(MaterialQuoteShapePolicyMapper.class);
    service = new MaterialQuoteShapePolicyServiceImpl(mapper, new ObjectMapper());
    when(mapper.selectList(any())).thenReturn(List.of());
    when(mapper.insert(any(MaterialQuoteShapePolicy.class))).thenReturn(1);
    when(mapper.updateById(any(MaterialQuoteShapePolicy.class))).thenReturn(1);
  }

  @Test
  @DisplayName("相邻月份不重叠：旧规则到 8 月，新规则从 9 月开始")
  void adjacentMonthAllowed() {
    when(mapper.selectList(any()))
        .thenReturn(
            List.of(
                MaterialQuoteShapePolicyServiceTest.existing(
                    1L, 1, "2026-01", "2026-08")));
    MaterialQuoteShapePolicyRequest request = fixed("2026-09", null);

    assertThatCode(() -> service.create(request)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("启用规则月份区间按闭区间判断，月份重叠时拒绝")
  void overlappingMonthRejected() {
    when(mapper.selectList(any()))
        .thenReturn(
            List.of(
                MaterialQuoteShapePolicyServiceTest.existing(
                    1L, 1, "2026-01", "2026-08")));

    assertThatThrownBy(() -> service.create(fixed("2026-08", "2026-12")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("月份重叠");
  }

  @Test
  @DisplayName("停用规则不参与重叠校验；重新启用时必须重新校验")
  void disabledPolicySkipsOverlapButEnableChecksAgain() {
    MaterialQuoteShapePolicy active =
        MaterialQuoteShapePolicyServiceTest.existing(1L, 1, "2026-01", null);
    when(mapper.selectList(any())).thenReturn(List.of(active));
    MaterialQuoteShapePolicyRequest disabled = fixed("2026-08", null);
    disabled.setEnabled(0);

    assertThatCode(() -> service.create(disabled)).doesNotThrowAnyException();

    MaterialQuoteShapePolicy row =
        MaterialQuoteShapePolicyServiceTest.existing(2L, 0, "2026-08", null);
    when(mapper.selectById(2L)).thenReturn(row);
    assertThatThrownBy(() -> service.setEnabled(2L, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("月份重叠");
  }

  @Test
  @DisplayName("FIXED 必须配置合法目标形态")
  void fixedShapeRequiredAndValidated() {
    MaterialQuoteShapePolicyRequest missing = fixed("2026-08", null);
    missing.setFixedTargetShape(" ");
    assertThatThrownBy(() -> service.create(missing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("目标形态");

    MaterialQuoteShapePolicyRequest invalid = fixed("2026-08", null);
    invalid.setFixedTargetShape("BUY");
    assertThatThrownBy(() -> service.create(invalid))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("非法形态");

    MaterialQuoteShapePolicyRequest virtual = fixed("2026-08", null);
    virtual.setFixedTargetShape("VIRTUAL");
    assertThatThrownBy(() -> service.create(virtual))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不再支持虚拟件");
  }

  @Test
  @DisplayName("SUPPLIER_RATIO 不要求供应商条件，统一读取供货比率关系")
  void supplierConditionIsNotRequired() {
    MaterialQuoteShapePolicyRequest request = supplier();
    request.setConditionConfigJson(null);

    assertThatCode(() -> service.create(request)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("SUPPLIER_RATIO 只校验可选的排除子件配置")
  void supplierActionValidation() {
    MaterialQuoteShapePolicyRequest blankChild = supplier();
    blankChild.setActionConfigJson(
        "{\"internalTargetShape\":\"MANUFACTURE\","
            + "\"externalTargetShape\":\"OUTSOURCE\","
            + "\"excludedDirectChildMaterialCodes\":[\"\"]}");
    assertThatThrownBy(() -> service.create(blankChild))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("排除子件料号");

    MaterialQuoteShapePolicyRequest invalidJson = supplier();
    invalidJson.setActionConfigJson("{");
    assertThatThrownBy(() -> service.create(invalidJson))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("动作 JSON");

    MaterialQuoteShapePolicyRequest noAction = supplier();
    noAction.setActionConfigJson(null);
    assertThatCode(() -> service.create(noAction)).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("月份必须是 YYYY-MM，且结束月不能早于开始月")
  void monthValidation() {
    assertThatThrownBy(() -> service.create(fixed("2026-8", null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("YYYY-MM");
    assertThatThrownBy(() -> service.create(fixed("2026-09", "2026-08")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能早于");
  }

  private static MaterialQuoteShapePolicyRequest fixed(String from, String to) {
    MaterialQuoteShapePolicyRequest request = new MaterialQuoteShapePolicyRequest();
    request.setMaterialOrgCode("COMMERCIAL");
    request.setMaterialCode("201850113");
    request.setMaterialName("烧结基座");
    request.setPolicyMode("FIXED");
    request.setFixedTargetShape("PURCHASE");
    request.setEffectiveFromMonth(from);
    request.setEffectiveToMonth(to);
    request.setEnabled(1);
    return request;
  }

  private static MaterialQuoteShapePolicyRequest supplier() {
    MaterialQuoteShapePolicyRequest request = fixed("2026-08", null);
    request.setPolicyMode("SUPPLIER_RATIO");
    request.setFixedTargetShape(null);
    request.setConditionConfigJson("{\"internalSupplierCodes\":[\"SUP-210\"]}");
    request.setActionConfigJson(
        "{\"internalTargetShape\":\"MANUFACTURE\","
            + "\"externalTargetShape\":\"OUTSOURCE\","
            + "\"excludedDirectChildMaterialCodes\":[\"311034930\"]}");
    return request;
  }
}
