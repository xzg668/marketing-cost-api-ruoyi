package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.FlattenRequest;
import com.sanhua.marketingcost.dto.FlattenResult;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSubRefMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.BomByproductCostRuleQueryService;
import com.sanhua.marketingcost.service.BomSettlementRuleQueryService;
import com.sanhua.marketingcost.service.PackageComponentIdentifyService;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleMatcher;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleMatcher;
import com.sanhua.marketingcost.service.settlement.BomByproductSettlementAdapter;
import com.sanhua.marketingcost.service.settlement.BomByproductSettlementReadResult;
import com.sanhua.marketingcost.service.settlement.BomSettlementRowBuildEngine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("BOM 拍平跨组织主链路")
class BomFlattenCrossOrganizationTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, BomRawHierarchy.class);
    TableInfoHelper.initTableInfo(assistant, BomCostingRow.class);
  }

  @Test
  @DisplayName("非裸品板换拍平使用商用制造子件并把行级组织写入结算行")
  void flattensCommercialMakeChildrenWithCommercialOrganization() {
    BomRawHierarchyMapper rawMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper masterMapper = mock(MaterialMasterRawMapper.class);
    BomCostingRowMapper costingMapper = mock(BomCostingRowMapper.class);
    BomCostingRowSubRefMapper subRefMapper = mock(BomCostingRowSubRefMapper.class);
    PackageComponentIdentifyService packageIdentify = mock(PackageComponentIdentifyService.class);
    BomSettlementRuleQueryService settlementRules = mock(BomSettlementRuleQueryService.class);
    BomByproductCostRuleQueryService byproductRules = mock(BomByproductCostRuleQueryService.class);
    BomByproductSettlementAdapter byproductAdapter = mock(BomByproductSettlementAdapter.class);
    BomRawHierarchy top = row(1L, "P", "P", "P", 0, "/P/", "制造件", "1", "220");
    BomRawHierarchy cross =
        row(2L, "P", "P", "C", 1, "/P/C@10/", "采购件", "2", "220");
    BomRawHierarchy commercialChild =
        row(20L, "C", "C", "R", 1, "/C/R@10/", "采购件", "0.25", "210");
    commercialChild.setMaterialName("拉制铜管");
    commercialChild.setQtyPerParent(new BigDecimal("0.25"));
    when(rawMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(top, cross), List.of(commercialChild));
    when(masterMapper.selectByLatestBatchAndCodes(
            any(Collection.class), isNull(), eq("PLATE")))
        .thenReturn(List.of(master("P", "制造件"), master("C", "采购件")));
    when(masterMapper.selectByLatestBatchAndCodes(
            any(Collection.class), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of(master("C", "制造件"), master("R", "采购件")));
    when(packageIdentify.batchIdentify(any(), eq("PLATE"))).thenReturn(Map.of());
    when(settlementRules.listEnabledCandidates()).thenReturn(List.of(drawnTubeRollupRule()));
    when(byproductRules.listEnabledCandidates()).thenReturn(List.of());
    when(byproductAdapter.read(any(), any(), any(), any(), any()))
        .thenReturn(new BomByproductSettlementReadResult(List.of(), List.of(), List.of()));
    when(costingMapper.batchUpsert(any())).thenReturn(1);
    ObjectMapper objectMapper = new ObjectMapper();
    BomSettlementRowBuildEngine engine =
        new BomSettlementRowBuildEngine(
            new BomSettlementRuleMatcher(new BomSettlementRuleConditionEvaluator(objectMapper)),
            new BomByproductCostRuleMatcher(
                new BomByproductCostRuleConditionEvaluator(objectMapper)));
    PlateCommercialMakeBomExpansionService expansionService =
        new PlateCommercialMakeBomExpansionService(rawMapper, masterMapper);
    BomFlattenServiceImpl service =
        new BomFlattenServiceImpl(
            rawMapper,
            costingMapper,
            subRefMapper,
            packageIdentify,
            settlementRules,
            byproductRules,
            byproductAdapter,
            engine,
            expansionService);

    FlattenRequest request = new FlattenRequest();
    request.setMode("BY_OA");
    request.setOaNo("OA-1");
    request.setOaFormItemId(10L);
    request.setTopProductCode("P");
    request.setPriceOrgCode("220");
    request.setMaterialOrganizationCode("PLATE");
    request.setBusinessUnitType("COMMERCIAL");
    request.setBomPurpose("主制造");
    request.setPeriodMonth("2026-07");
    request.setAsOfDate(LocalDate.of(2026, 7, 10));

    FlattenResult result = service.flatten(request);

    assertThat(result.getCostingRowsWritten()).isEqualTo(1);
    @SuppressWarnings("rawtypes")
    ArgumentCaptor<List> rowsCaptor = ArgumentCaptor.forClass(List.class);
    org.mockito.Mockito.verify(costingMapper).batchUpsert(rowsCaptor.capture());
    @SuppressWarnings("unchecked")
    List<BomCostingRow> rows = rowsCaptor.getValue();
    assertThat(rows).hasSize(1);
    BomCostingRow costingRow = rows.get(0);
    assertThat(costingRow.getMaterialCode()).isEqualTo("C");
    assertThat(costingRow.getPath()).isEqualTo("/P/C@10/");
    assertThat(costingRow.getQtyPerTop()).isEqualByComparingTo("2");
    assertThat(costingRow.getShapeAttr()).isEqualTo("制造件");
    assertThat(costingRow.getSettlementRowType()).isEqualTo("SPECIAL_ROLLUP_PARENT");
    assertThat(costingRow.getSubtreeCostRequired()).isEqualTo(1);
    assertThat(costingRow.getPriceOrgCode()).isEqualTo("210");
    assertThat(costingRow.getMaterialOrganizationCode()).isEqualTo("COMMERCIAL");
    assertThat(costingRow.getBusinessUnitType()).isEqualTo("COMMERCIAL");
  }

  private BomRawHierarchy row(
      Long id,
      String top,
      String parent,
      String code,
      int level,
      String path,
      String shape,
      String qty,
      String priceOrgCode) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId(id);
    row.setTopProductCode(top);
    row.setParentCode(parent);
    row.setMaterialCode(code);
    row.setMaterialName("NAME-" + code);
    row.setShapeAttr(shape);
    row.setLevel(level);
    row.setPath(path);
    row.setSortSeq(10);
    row.setQtyPerParent(new BigDecimal(qty));
    row.setQtyPerTop(new BigDecimal(qty));
    row.setIsLeaf("采购件".equals(shape) ? 1 : 0);
    row.setBomPurpose("主制造");
    row.setSourceType("U9");
    row.setPriceOrgCode(priceOrgCode);
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    return row;
  }

  private MaterialMasterRaw master(String code, String shape) {
    MaterialMasterRaw master = new MaterialMasterRaw();
    master.setMaterialCode(code);
    master.setShapeAttr(shape);
    master.setActiveFlag(1);
    return master;
  }

  private BomSettlementRule drawnTubeRollupRule() {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setId(5L);
    rule.setRuleCode("SPECIAL_PURCHASE_ROLLUP_DRAWN_COPPER_TUBE");
    rule.setRuleName("特殊子项品名上卷：拉制铜管");
    rule.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    rule.setSettlementAction("ROLLUP_TO_PARENT");
    rule.setSettlementRowType("SPECIAL_ROLLUP_PARENT");
    rule.setMarkSubtreeCostRequired(1);
    rule.setMatchConditionJson(
        "{\"nodeConditions\":[{\"field\":\"material_name\",\"op\":\"LIKE\",\"value\":\"拉制铜管\"}]}");
    rule.setPriority(14);
    rule.setEnabled(1);
    return rule;
  }
}
