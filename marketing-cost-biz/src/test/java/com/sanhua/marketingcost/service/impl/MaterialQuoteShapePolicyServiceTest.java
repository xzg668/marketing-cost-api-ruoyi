package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyQuery;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyRequest;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyResponse;
import com.sanhua.marketingcost.entity.MaterialQuoteShapePolicy;
import com.sanhua.marketingcost.mapper.MaterialQuoteShapePolicyMapper;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MaterialQuoteShapePolicyServiceTest {

  private MaterialQuoteShapePolicyMapper mapper;
  private MaterialQuoteShapePolicyServiceImpl service;

  @BeforeAll
  static void initializeMybatisTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""),
        MaterialQuoteShapePolicy.class);
  }

  @BeforeEach
  void setUp() {
    mapper = mock(MaterialQuoteShapePolicyMapper.class);
    service = new MaterialQuoteShapePolicyServiceImpl(mapper, new ObjectMapper());
    when(mapper.selectList(any())).thenReturn(List.of());
    when(mapper.insert(any(MaterialQuoteShapePolicy.class)))
        .thenAnswer(
            invocation -> {
              MaterialQuoteShapePolicy row = invocation.getArgument(0);
              row.setId(101L);
              return 1;
            });
    when(mapper.updateById(any(MaterialQuoteShapePolicy.class))).thenReturn(1);
    when(mapper.deleteById(any(Long.class))).thenReturn(1);
  }

  @Test
  @DisplayName("FIXED 规则保存时规范组织、形态、月份和空白文本")
  void createFixedPolicy() {
    MaterialQuoteShapePolicyResponse result = service.create(fixedRequest());

    ArgumentCaptor<MaterialQuoteShapePolicy> captor =
        ArgumentCaptor.forClass(MaterialQuoteShapePolicy.class);
    verify(mapper).insert(captor.capture());
    MaterialQuoteShapePolicy saved = captor.getValue();
    assertThat(result.getId()).isEqualTo(101L);
    assertThat(saved.getMaterialOrgCode()).isEqualTo("COMMERCIAL");
    assertThat(saved.getMaterialCode()).isEqualTo("201850113");
    assertThat(saved.getPolicyMode()).isEqualTo("FIXED");
    assertThat(saved.getFixedTargetShape()).isEqualTo("PURCHASE");
    assertThat(saved.getConditionConfigJson()).isNull();
    assertThat(saved.getActionConfigJson()).isNull();
    assertThat(saved.getEffectiveFromMonth()).isEqualTo("2026-08");
    assertThat(saved.getEffectiveToMonth()).isNull();
    assertThat(saved.getEnabled()).isEqualTo(1);
    assertThat(saved.getCreatedAt()).isNotNull();
    assertThat(saved.getUpdatedAt()).isNotNull();
  }

  @Test
  @DisplayName("SUPPLIER_RATIO 不再保存供应商名单，只保留可选的排除子件")
  void createSupplierRatioPolicy() throws Exception {
    MaterialQuoteShapePolicyRequest request = supplierRatioRequest();

    service.create(request);

    ArgumentCaptor<MaterialQuoteShapePolicy> captor =
        ArgumentCaptor.forClass(MaterialQuoteShapePolicy.class);
    verify(mapper).insert(captor.capture());
    MaterialQuoteShapePolicy saved = captor.getValue();
    Map<?, ?> action =
        new ObjectMapper().readValue(saved.getActionConfigJson(), Map.class);
    assertThat(saved.getConditionConfigJson()).isNull();
    assertThat(action.get("excludedDirectChildMaterialCodes"))
        .isEqualTo(List.of("311034930"));
    assertThat(action.containsKey("internalTargetShape")).isFalse();
    assertThat(action.containsKey("externalTargetShape")).isFalse();
  }

  @Test
  @DisplayName("列表查询同时支持料号、名称、规格、型号和月份")
  void listUsesAllMaterialFilters() {
    MaterialQuoteShapePolicy row = existing(9L, 1, "2026-01", null);
    when(mapper.selectList(any())).thenReturn(List.of(row));
    MaterialQuoteShapePolicyQuery query = new MaterialQuoteShapePolicyQuery();
    query.setMaterialOrgCode("商用");
    query.setMaterialCode("201850");
    query.setMaterialName("烧结");
    query.setMaterialSpec("规格A");
    query.setMaterialModel("型号B");
    query.setEffectiveMonth("2026-08");
    query.setEnabled(1);

    List<MaterialQuoteShapePolicyResponse> result = service.list(query);

    assertThat(result).hasSize(1);
    ArgumentCaptor<Wrapper<MaterialQuoteShapePolicy>> captor =
        ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectList(captor.capture());
    AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
    assertThat(wrapper.getSqlSegment())
        .contains(
            "material_org_code",
            "material_code",
            "material_name",
            "material_spec",
            "material_model",
            "effective_from_month",
            "effective_to_month",
            "enabled");
    assertThat(wrapper.getParamNameValuePairs().values())
        .contains(
            "COMMERCIAL",
            "%201850%",
            "%烧结%",
            "%规格A%",
            "%型号B%",
            "2026-08",
            1);
  }

  @Test
  @DisplayName("停用和删除只操作规则表，不依赖最终 BOM 节点 Mapper")
  void disableAndDeleteOnlyMutatePolicyTable() {
    MaterialQuoteShapePolicy existing = existing(7L, 1, "2026-08", null);
    when(mapper.selectById(7L)).thenReturn(existing);

    MaterialQuoteShapePolicyResponse disabled = service.setEnabled(7L, 0);
    boolean deleted = service.delete(7L);

    assertThat(disabled.getEnabled()).isZero();
    assertThat(deleted).isTrue();
    verify(mapper).updateById(any(MaterialQuoteShapePolicy.class));
    verify(mapper).deleteById(7L);
    assertThat(MaterialQuoteShapePolicyServiceImpl.class.getDeclaredFields())
        .allMatch(field -> !field.getType().getSimpleName().contains("QuoteEffectiveBom"));
  }

  @Test
  @DisplayName("修改规则保留创建信息、更新业务内容并忽略自身月份区间")
  void updateExistingPolicy() {
    MaterialQuoteShapePolicy existing = existing(8L, 1, "2026-08", null);
    existing.setFixedTargetShape("MANUFACTURE");
    existing.setCreatedAt(LocalDateTime.of(2026, 8, 1, 9, 0));
    existing.setCreatedBy(12L);
    when(mapper.selectById(8L)).thenReturn(existing);
    when(mapper.selectList(any())).thenReturn(List.of(existing));

    MaterialQuoteShapePolicyResponse result =
        service.update(8L, fixedRequest());

    ArgumentCaptor<MaterialQuoteShapePolicy> captor =
        ArgumentCaptor.forClass(MaterialQuoteShapePolicy.class);
    verify(mapper).updateById(captor.capture());
    assertThat(result.getFixedTargetShape()).isEqualTo("PURCHASE");
    assertThat(captor.getValue().getId()).isEqualTo(8L);
    assertThat(captor.getValue().getCreatedAt())
        .isEqualTo(LocalDateTime.of(2026, 8, 1, 9, 0));
    assertThat(captor.getValue().getCreatedBy()).isEqualTo(12L);
    assertThat(captor.getValue().getUpdatedAt()).isNotNull();
  }

  @Test
  @DisplayName("不存在的规则删除返回 false，不伪造成功")
  void deleteMissingReturnsFalse() {
    when(mapper.selectById(404L)).thenReturn(null);

    assertThat(service.delete(404L)).isFalse();

    verify(mapper, never()).deleteById(404L);
  }

  private static MaterialQuoteShapePolicyRequest fixedRequest() {
    MaterialQuoteShapePolicyRequest request = new MaterialQuoteShapePolicyRequest();
    request.setMaterialOrgCode(" 商用 ");
    request.setMaterialCode(" 201850113 ");
    request.setMaterialName(" 烧结基座 ");
    request.setMaterialSpec(" 规格A ");
    request.setMaterialModel(" 型号B ");
    request.setPolicyMode(" fixed ");
    request.setFixedTargetShape(" purchase ");
    request.setEffectiveFromMonth("2026-08");
    request.setEffectiveToMonth("   ");
    request.setEnabled(1);
    request.setRemark(" 固定改为采购件 ");
    return request;
  }

  static MaterialQuoteShapePolicyRequest supplierRatioRequest() {
    MaterialQuoteShapePolicyRequest request = fixedRequest();
    request.setPolicyMode("SUPPLIER_RATIO");
    request.setFixedTargetShape(null);
    request.setConditionConfigJson(
        "{\"internalSupplierCodes\":[\" SUP-210 \",\"SUP-210\",\"SUP-220\"]}");
    request.setActionConfigJson(
        "{\"internalTargetShape\":\"manufacture\","
            + "\"externalTargetShape\":\"outsource\","
            + "\"excludedDirectChildMaterialCodes\":[\"311034930\",\" 311034930 \"]}");
    return request;
  }

  static MaterialQuoteShapePolicy existing(
      Long id, Integer enabled, String fromMonth, String toMonth) {
    MaterialQuoteShapePolicy row = new MaterialQuoteShapePolicy();
    row.setId(id);
    row.setMaterialOrgCode("COMMERCIAL");
    row.setMaterialCode("201850113");
    row.setMaterialName("烧结基座");
    row.setPolicyMode("FIXED");
    row.setFixedTargetShape("PURCHASE");
    row.setEffectiveFromMonth(fromMonth);
    row.setEffectiveToMonth(toMonth);
    row.setEnabled(enabled);
    return row;
  }
}
