package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BomByproductCostRule;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleMatcher;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleMatcher;
import com.sanhua.marketingcost.service.rule.BomRuleMaterialAttributes;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BomSettlementRowBuildEngine · 统一 BOM 结算行生成引擎骨架")
class BomSettlementRowBuildEngineTest {

  private final BomSettlementRowBuildEngine engine = new BomSettlementRowBuildEngine(
      new BomSettlementRuleMatcher(
          new BomSettlementRuleConditionEvaluator(new ObjectMapper())),
      new BomByproductCostRuleMatcher(
          new BomByproductCostRuleConditionEvaluator(new ObjectMapper())));

  @Test
  @DisplayName("纯内存输入可以生成结算行、sub_ref 和 source_ref 候选")
  void buildsRowsSubRefsAndSourceRefsInMemory() {
    BomSettlementRowBuildResult result = engine.build(request(List.of(
        node("P", "P", null, 0, "/P/", 0, "组件", null),
        node("C1", "P", "P", 1, "/P/C1/", 1, "丝网", null),
        node("C2", "P", "P", 1, "/P/C2/", 1, "叶子二", null)
    ), List.of(rollupRule("SPECIAL_PURCHASE_ROLLUP_MESH", "丝网", 10))));

    assertThat(result.warnings()).isEmpty();
    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("P", "C2");
    BomCostingRow parentRow = result.costingRows().getFirst();
    assertThat(parentRow.getSettlementRowType()).isEqualTo("SPECIAL_ROLLUP_PARENT");
    assertThat(parentRow.getSubtreeCostRequired()).isEqualTo(1);
    assertThat(parentRow.getMatchedSettlementRuleId()).isEqualTo(10L);
    assertThat(result.subRefs()).hasSize(1);
    assertThat(result.subRefs().getFirst().costingRowPath()).isEqualTo("/P/");
    assertThat(result.subRefs().getFirst().subRef().getSubMaterialCode()).isEqualTo("C1");
    assertThat(result.sourceRefs()).hasSize(2);
    assertThat(result.stats().costingRowCount()).isEqualTo(2);
    assertThat(result.stats().subRefCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("同父多个特殊采购分类叶子只生成一条父结算行")
  void rollupBucketDeduplicatesByParentPath() {
    BomSettlementRowBuildResult result = engine.build(request(List.of(
        node("P", "P", null, 0, "/P/", 0, "组件", null),
        node("C1", "P", "P", 1, "/P/C1/", 1, "丝网一", null),
        node("C2", "P", "P", 1, "/P/C2/", 1, "丝网二", null)
    ), List.of(rollupRule("SPECIAL_PURCHASE_ROLLUP_MESH", "丝网", 10))));

    assertThat(result.costingRows()).hasSize(1);
    assertThat(result.costingRows().getFirst().getMaterialCode()).isEqualTo("P");
    assertThat(result.subRefs()).extracting(ref -> ref.subRef().getSubMaterialCode())
        .containsExactly("C1", "C2");
    assertThat(result.stats().rollupBucketCount()).isEqualTo(1);
    assertThat(result.stats().consumedLeafPathCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("上卷 bucket 不写 stoppedPaths，未命中特殊规则的同父兄弟叶子仍输出")
  void rollupDoesNotConsumeSiblingLeaves() {
    BomSettlementRowBuildResult result = engine.build(request(List.of(
        node("P", "P", null, 0, "/P/", 0, "组件", null),
        node("C1", "P", "P", 1, "/P/C1/", 1, "丝网", null),
        node("C2", "P", "P", 1, "/P/C2/", 1, "紫铜直管", null),
        node("S1", "P", "P", 1, "/P/S1/", 1, "普通兄弟叶子", null)
    ), List.of(
        rollupRule("SPECIAL_PURCHASE_ROLLUP_MESH", "丝网", 10),
        rollupRule("SPECIAL_PURCHASE_ROLLUP_PURPLE_COPPER_STRAIGHT_TUBE", "紫铜直管", 11))));

    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("P", "S1");
    assertThat(result.costingRows().get(1).getSettlementRowType()).isEqualTo("DEFAULT_LEAF");
    assertThat(result.subRefs()).extracting(ref -> ref.subRef().getSubMaterialCode())
        .containsExactly("C1", "C2");
    assertThat(result.stats().stoppedPathCount()).isZero();
  }

  @Test
  @DisplayName("制造件和非包装虚拟件是结构节点，默认继续下钻且不输出自身")
  void structuralManufacturedAndNonPackageVirtualNodesAreNotDefaultRows() {
    BomSettlementRowBuildResult result = engine.build(request(List.of(
        node("P", "P", null, 0, "/P/", 0, "顶层制造件", null),
        virtualNode("LBL", "P", "P", 1, "/P/LBL/", 0, "产品标贴", "1401"),
        purchaseNode("L1", "P", "LBL", 2, "/P/LBL/L1/", "标贴子件一"),
        purchaseNode("L2", "P", "LBL", 2, "/P/LBL/L2/", "标贴子件二")
    ), List.of()));

    assertThat(result.warnings()).isEmpty();
    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("L1", "L2");
    assertThat(result.costingRows()).extracting(BomCostingRow::getSettlementRowType)
        .containsExactly("DEFAULT_LEAF", "DEFAULT_LEAF");
  }

  @Test
  @DisplayName("非包装虚拟件没有末级子件时不兜底输出虚拟件自身")
  void nonPackageVirtualNodeWithoutTerminalLeafDoesNotCreateFallbackRow() {
    BomSettlementRowBuildResult result = engine.build(request(List.of(
        node("P", "P", null, 0, "/P/", 0, "顶层制造件", null),
        virtualNode("LBL", "P", "P", 1, "/P/LBL/", 1, "产品标贴", "1401")
    ), List.of()));

    assertThat(result.costingRows()).isEmpty();
    assertThat(result.warnings()).anyMatch(
        warning -> warning.contains("STRUCTURE_LEAF_NO_CHILD")
            && warning.contains("LBL"));
  }

  @Test
  @DisplayName("包装组件虚拟件输出父件并截断，包装子件不进入普通结算行")
  void packageComponentParentStopsPackageChildren() {
    BomSettlementRowBuildResult result = engine.build(request(List.of(
        node("P", "P", null, 0, "/P/", 0, "顶层制造件", null),
        virtualNode("9830000026238", "P", "P", 1, "/P/9830000026238/", 0, "包装组件", "1515501"),
        purchaseNode("PKG-C1", "P", "9830000026238", 2, "/P/9830000026238/PKG-C1/", "包装子件一"),
        purchaseNode("PKG-C2", "P", "9830000026238", 2, "/P/9830000026238/PKG-C2/", "包装子件二"),
        purchaseNode("N1", "P", "P", 1, "/P/N1/", "普通采购件")
    ), List.of()));

    assertThat(result.warnings()).isEmpty();
    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("9830000026238", "N1");
    assertThat(result.costingRows().getFirst().getSettlementRowType()).isEqualTo("PACKAGE_PARENT");
    assertThat(result.stats().stoppedPathCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("1079900000536 中产品标贴作为非包装虚拟件继续下钻，两个子采购件输出")
  void goldenProductLabelVirtualNodeDrillsDownToTwoPurchasedChildren() {
    BomSettlementRowBuildResult result = engine.build(new BomSettlementBuildRequest(
        "OA-GOLDEN-001",
        "1079900000536",
        LocalDate.of(2026, 5, 29),
        "2026-05",
        "bsr04",
        LocalDateTime.of(2026, 5, 29, 17, 0),
        "COMMERCIAL",
        "主制造",
        List.of(
            node("1079900000536", "1079900000536", null, 0, "/1079900000536/", 0, "金标产品", null),
            virtualNode(
                "产品标贴", "1079900000536", "1079900000536", 1,
                "/1079900000536/产品标贴/", 0, "产品标贴", "1401"),
            purchaseNode(
                "LBL-001", "1079900000536", "产品标贴", 2,
                "/1079900000536/产品标贴/LBL-001/", "产品标贴子采购件一"),
            purchaseNode(
                "LBL-002", "1079900000536", "产品标贴", 2,
                "/1079900000536/产品标贴/LBL-002/", "产品标贴子采购件二")
        ),
        List.of()));

    assertThat(result.warnings()).isEmpty();
    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("LBL-001", "LBL-002");
  }

  @Test
  @DisplayName("只处理主制造且当前有效的节点，顶层单节点和断链 path 输出 warning")
  void filtersMainManufacturingCurrentNodesAndWarnsAbnormalStructure() {
    BomSettlementNode expired = purchaseNode("OLD", "P", "P", 1, "/P/OLD/", "过期采购件");
    expired = withPurposeAndEffective(expired, "主制造", LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31));
    BomSettlementNode nonMain = withPurposeAndEffective(
        purchaseNode("AUX", "P", "P", 1, "/P/AUX/", "非主制造采购件"),
        "试制",
        LocalDate.of(2026, 1, 1),
        null);
    BomSettlementRowBuildResult result = engine.build(request(List.of(
        purchaseNode("SINGLE", "SINGLE", null, 0, "/SINGLE/", "顶层单节点采购件"),
        purchaseNode("BROKEN", "P", "MISSING", 2, "/P/MISSING/BROKEN/", "断链采购件"),
        expired,
        nonMain
    ), List.of()));

    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("BROKEN", "SINGLE");
    assertThat(result.warnings()).anyMatch(warning -> warning.startsWith("PATH_CHAIN_BROKEN"));
    assertThat(result.warnings()).anyMatch(warning -> warning.startsWith("TOP_SINGLE_NODE"));
  }

  @Test
  @DisplayName("1079900000536 的三条铜管叶子上卷为三个父结算行，sub_ref 只记录命中叶子")
  void goldenCopperLeavesRollUpToThreeParentsAndOnlyHitLeavesBecomeSubRefs() {
    BomSettlementRowBuildResult result = engine.build(new BomSettlementBuildRequest(
        "OA-GOLDEN-001",
        "1079900000536",
        LocalDate.of(2026, 5, 29),
        "2026-05",
        "bsr05",
        LocalDateTime.of(2026, 5, 29, 17, 30),
        "COMMERCIAL",
        "主制造",
        List.of(
            node("1079900000536", "1079900000536", null, 0, "/1079900000536/", 0, "金标产品", null),
            node(
                "203250582", "1079900000536", "1079900000536", 1,
                "/1079900000536/203250582/", 0, "铜管父件一", null),
            purchasedNodeWithCategory(
                "CU-582", "1079900000536", "203250582", 2,
                "/1079900000536/203250582/CU-582/", "拉制铜管一", "171711404", "拉制铜管", "拉制铜管"),
            purchasedNodeWithCategory(
                "OTHER-582", "1079900000536", "203250582", 2,
                "/1079900000536/203250582/OTHER-582/", "同父普通叶子", "1801", "普通主分类", null),
            node(
                "203250724", "1079900000536", "1079900000536", 1,
                "/1079900000536/203250724/", 0, "铜管父件二", null),
            purchasedNodeWithCategory(
                "CU-724", "1079900000536", "203250724", 2,
                "/1079900000536/203250724/CU-724/", "拉制铜管二", "171711404", "拉制铜管", "拉制铜管"),
            node(
                "203250749", "1079900000536", "1079900000536", 1,
                "/1079900000536/203250749/", 0, "铜管父件三", null),
            purchasedNodeWithCategory(
                "CU-749", "1079900000536", "203250749", 2,
                "/1079900000536/203250749/CU-749/", "拉制铜管三", "171711404", "拉制铜管", "拉制铜管")
        ),
        List.of(rollupRule("SPECIAL_PURCHASE_ROLLUP_DRAWN_COPPER_TUBE", "拉制铜管", 10))));

    assertThat(result.warnings()).isEmpty();
    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("203250582", "203250724", "203250749", "OTHER-582");
    assertThat(result.costingRows()).filteredOn(row -> "SPECIAL_ROLLUP_PARENT".equals(row.getSettlementRowType()))
        .extracting(BomCostingRow::getMaterialCode)
        .containsExactly("203250582", "203250724", "203250749");
    assertThat(result.subRefs()).extracting(ref -> ref.subRef().getSubMaterialCode())
        .containsExactly("CU-582", "CU-724", "CU-749");
  }

  @Test
  @DisplayName("辅料排除使用 U9 原始主分类编码，17开头可排除且分类名称不参与判断")
  void auxiliaryExcludeUsesRawMainCategoryCodeWithout18PrefixOrName() {
    BomSettlementRowBuildEngine financeEngine = new BomSettlementRowBuildEngine(
        new BomSettlementRuleMatcher(
            new BomSettlementRuleConditionEvaluator(new ObjectMapper())),
        new BomByproductCostRuleMatcher(
            new BomByproductCostRuleConditionEvaluator(new ObjectMapper())),
        (ignoredCodes, ignoredOrganization) -> Map.of(
            "EXCLUDED-17", new BomRuleMaterialAttributes("171721414", null),
            "NORMAL-18", new BomRuleMaterialAttributes("181811432", null),
            "NAME-ONLY", new BomRuleMaterialAttributes("121191304", null)));
    BomSettlementRowBuildResult result = financeEngine.build(request(List.of(
        node("P", "P", null, 0, "/P/", 0, "组件", null),
        purchasedNodeWithCategory("EXCLUDED-17", "P", "P", 1, "/P/EXCLUDED-17/", "胶黏剂", "17", "任意名称", null),
        purchasedNodeWithCategory("NORMAL-18", "P", "P", 1, "/P/NORMAL-18/", "普通焊料", "18", "任意名称", null),
        purchasedNodeWithCategory("NAME-ONLY", "P", "P", 1, "/P/NAME-ONLY/", "名称相同但编码不同", "18", "胶黏剂类", null)
    ), List.of(
        rollupRule("SPECIAL_ROLLUP_SAME_NODE", "胶黏剂", 10),
        auxiliaryExcludeCodeRule("AUXILIARY_EXCLUDE_ADHESIVE", "171721414", 40))));

    assertThat(result.warnings()).isEmpty();
    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("NAME-ONLY", "NORMAL-18");
  }

  @Test
  @DisplayName("特殊子项品名上卷只作用于末级采购件，命中结构节点时继续按结构默认下钻")
  void specialPurchaseRollupRequiresTerminalPurchasedNode() {
    BomSettlementRule broadRollup = rollupRule("SPECIAL_PURCHASE_ROLLUP_MESH", "丝网", 10);
    BomSettlementRowBuildResult result = engine.build(request(List.of(
        fullNode(
            "P", "P", null, 0, "/P/", 0, "组件", "制造件", "制造件",
            "18", "普通主分类", null, "主制造", LocalDate.of(2026, 1, 1), null),
        fullNode(
            "MID", "P", "P", 1, "/P/MID/", 0, "丝网结构件", "制造件", "制造件",
            "18", "普通主分类", null, "主制造", LocalDate.of(2026, 1, 1), null),
        purchaseNode("LEAF", "P", "MID", 2, "/P/MID/LEAF/", "普通采购叶子")
    ), List.of(broadRollup)));

    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("LEAF");
    assertThat(result.warnings())
        .anyMatch(warning -> warning.startsWith("SPECIAL_PURCHASE_ROLLUP_NOT_PURCHASE_LEAF"));
  }

  @Test
  @DisplayName("末级采购件未上卷且直接父件是委外加工件时，额外输出父件委外加工费行")
  void defaultLeafWithDirectOutsourcedParentAddsProcessingFee() {
    BomSettlementRowBuildResult result = engine.build(request(List.of(
        node("P", "P", null, 0, "/P/", 0, "组件", null),
        fullNode(
            "OUT-1", "P", "P", 1, "/P/OUT-1/", 0, "委外阀体", "委外加工件", "委外加工件",
            "12", "委外类", null, "主制造", LocalDate.of(2026, 1, 1), null),
        purchaseNode("RAW-1", "P", "OUT-1", 2, "/P/OUT-1/RAW-1/", "下层原材料")
    ), List.of()));

    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("OUT-1", "RAW-1");
    BomCostingRow feeRow = result.costingRows().getFirst();
    assertThat(feeRow.getSettlementRowType()).isEqualTo("OUTSOURCED_PROCESS_FEE");
    assertThat(feeRow.getMaterialName()).isEqualTo("委外阀体-委外加工费");
    assertThat(result.costingRows().get(1).getSettlementRowType()).isEqualTo("DEFAULT_LEAF");
    assertThat(result.stats().extraRowBucketCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("末级采购件上卷成功后，保留上卷父件并补上一级委外加工费行")
  void rolledUpLeafWithOutsourcedParentAddsProcessingFee() {
    BomSettlementRowBuildResult result = engine.build(request(List.of(
        node("P", "P", null, 0, "/P/", 0, "组件", null),
        fullNode(
            "PISTON", "P", "P", 1, "/P/PISTON/", 0, "活塞", "制造件", "制造件",
            "18", "普通主分类", null, "主制造", LocalDate.of(2026, 1, 1), null),
        fullNode(
            "OUT-PISTON", "P", "PISTON", 2, "/P/PISTON/OUT-PISTON/", 0, "活塞", "委外加工件", "委外加工件",
            "12", "委外类", null, "主制造", LocalDate.of(2026, 1, 1), null),
        fullNode(
            "MACHINED-PISTON", "P", "OUT-PISTON", 3, "/P/PISTON/OUT-PISTON/MACHINED-PISTON/", 0,
            "活塞", "制造件", "制造件",
            "18", "普通主分类", null, "主制造", LocalDate.of(2026, 1, 1), null),
        purchaseNode("AL-BAR", "P", "MACHINED-PISTON", 4, "/P/PISTON/OUT-PISTON/MACHINED-PISTON/AL-BAR/", "铝棒")
    ), List.of(rollupRule("SPECIAL_PURCHASE_ROLLUP_AL_BAR", "铝棒", 10))));

    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("MACHINED-PISTON", "OUT-PISTON");
    assertThat(result.costingRows().getFirst().getSettlementRowType()).isEqualTo("SPECIAL_ROLLUP_PARENT");
    BomCostingRow feeRow = result.costingRows().get(1);
    assertThat(feeRow.getSettlementRowType()).isEqualTo("OUTSOURCED_PROCESS_FEE");
    assertThat(feeRow.getMaterialName()).isEqualTo("活塞-委外加工费");
    assertThat(result.subRefs()).extracting(ref -> ref.subRef().getSubMaterialCode())
        .containsExactly("AL-BAR");
    assertThat(result.stats().extraRowBucketCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("末级采购件未上卷但委外件不是直接父件时，不额外输出委外加工费行")
  void defaultLeafOnlyChecksDirectOutsourcedParentForProcessingFee() {
    BomSettlementRowBuildResult result = engine.build(request(List.of(
        node("P", "P", null, 0, "/P/", 0, "组件", null),
        fullNode(
            "OUT-1", "P", "P", 1, "/P/OUT-1/", 0, "委外阀体", "委外加工件", "委外加工件",
            "12", "委外类", null, "主制造", LocalDate.of(2026, 1, 1), null),
        fullNode(
            "MID-1", "P", "OUT-1", 2, "/P/OUT-1/MID-1/", 0, "中间制造件", "制造件", "制造件",
            "18", "普通主分类", null, "主制造", LocalDate.of(2026, 1, 1), null),
        purchaseNode("RAW-1", "P", "MID-1", 3, "/P/OUT-1/MID-1/RAW-1/", "下层原材料")
    ), List.of()));

    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("RAW-1");
    assertThat(result.costingRows()).noneMatch(row -> "OUTSOURCED_PROCESS_FEE".equals(row.getSettlementRowType()));
    assertThat(result.stats().extraRowBucketCount()).isZero();
  }

  @Test
  @DisplayName("直接加工父件已按采购件废料计算时，即使废料号不同也不输出自身副产品")
  void directProcessingParentWithScrapMappingDoesNotAddItsOwnByproduct() {
    BomSettlementRowBuildResult result = engine.build(byproductRequest(
        List.of(
            node("1145900000302", "1145900000302", null, 0, "/1145900000302/", 0, "成品", null),
            fullNode(
                "7218501041211", "1145900000302", "1145900000302", 1,
                "/1145900000302/7218501041211/", 0, "基座", "制造件", "制造件",
                "12", "专用件", null, "主制造", LocalDate.of(2026, 1, 1), null),
            purchaseNode(
                "201850068", "1145900000302", "7218501041211", 2,
                "/1145900000302/7218501041211/201850068/", "基座毛坯")
        ),
        List.of(byproduct(
            "7218501041211", "301991066", "油铁沫", new BigDecimal("0.0108"))),
        List.of(scrapRef("201850068", "301990444")),
        List.of(byproductRule())));

    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("201850068");
    assertThat(result.costingRows()).noneMatch(row -> "BYPRODUCT_EXTRA".equals(row.getSettlementRowType()));
  }

  @Test
  @DisplayName("不锈钢带上卷后跳过直接加工父件，但保留膜片上级副产品")
  void stainlessStripRollupKeepsOnlyMembraneAncestorByproduct() {
    BomSettlementRowBuildResult result = engine.build(byproductRequest(
        List.of(
            node("1145900000302", "1145900000302", null, 0, "/1145900000302/", 0, "成品", null),
            fullNode(
                "721850074", "1145900000302", "1145900000302", 1,
                "/1145900000302/721850074/", 0, "膜片", "制造件", "制造件",
                "12", "专用件", null, "主制造", LocalDate.of(2026, 1, 1), null),
            fullNode(
                "3012402681163", "1145900000302", "721850074", 2,
                "/1145900000302/721850074/3012402681163/", 0, "不锈钢带", "制造件", "制造件",
                "12", "专用件", null, "主制造", LocalDate.of(2026, 1, 1), null),
            purchaseNode(
                "301240268", "1145900000302", "3012402681163", 3,
                "/1145900000302/721850074/3012402681163/301240268/", "不锈钢带")
        ),
        List.of(
            byproduct(
                "3012402681163", "301990444", "直接加工父件废料", new BigDecimal("0.000130")),
            byproduct(
                "721850074", "301991071", "废不锈钢316L", new BigDecimal("0.000088"))),
        List.of(scrapRef("301240268", "301990444")),
        List.of(byproductRule()),
        List.of(rollupRule("SPECIAL_PURCHASE_ROLLUP_STAINLESS_STRIP", "不锈钢带", 11))));

    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("3012402681163", "301991071");
    assertThat(result.costingRows())
        .filteredOn(row -> "BYPRODUCT_EXTRA".equals(row.getSettlementRowType()))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.getParentCode()).isEqualTo("721850074");
          assertThat(row.getMaterialCode()).isEqualTo("301991071");
          assertThat(row.getQtyPerTop()).isEqualByComparingTo(new BigDecimal("-0.000088"));
        });
  }

  @Test
  @DisplayName("采购件废料映射不穿透子制造件，所有更上级副产品逐级独立输出")
  void scrapRefStopsAtNearestManufacturedParentAndKeepsAncestorByproducts() {
    BomSettlementRowBuildResult result = engine.build(byproductRequest(
        List.of(
            node("P", "P", null, 0, "/P/", 0, "组件", null),
            fullNode(
                "UPPER-MAKE", "P", "P", 1, "/P/UPPER-MAKE/", 0, "上上级部件", "制造件", "制造件",
                "12", "专用件", null, "主制造", LocalDate.of(2026, 1, 1), null),
            fullNode(
                "PARENT-MAKE", "P", "UPPER-MAKE", 2, "/P/UPPER-MAKE/PARENT-MAKE/", 0, "上级部件", "制造件", "制造件",
                "12", "专用件", null, "主制造", LocalDate.of(2026, 1, 1), null),
            fullNode(
                "DIRECT-MAKE", "P", "PARENT-MAKE", 3, "/P/UPPER-MAKE/PARENT-MAKE/DIRECT-MAKE/", 0, "直接加工父件", "制造件", "制造件",
                "12", "专用件", null, "主制造", LocalDate.of(2026, 1, 1), null),
            purchaseNode(
                "RAW-STEEL", "P", "DIRECT-MAKE", 4,
                "/P/UPPER-MAKE/PARENT-MAKE/DIRECT-MAKE/RAW-STEEL/", "不锈钢板")
        ),
        List.of(
            byproduct("UPPER-MAKE", "SCRAP-STEEL", "上上级废料"),
            byproduct("PARENT-MAKE", "SCRAP-STEEL", "上级废料"),
            byproduct("DIRECT-MAKE", "SCRAP-STEEL", "直接加工废料")),
        List.of(scrapRef("RAW-STEEL", "OTHER-SCRAP")),
        List.of(byproductRule())));

    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("SCRAP-STEEL", "SCRAP-STEEL", "RAW-STEEL");
    assertThat(result.costingRows())
        .filteredOn(row -> "BYPRODUCT_EXTRA".equals(row.getSettlementRowType()))
        .extracting(BomCostingRow::getParentCode)
        .containsExactly("UPPER-MAKE", "PARENT-MAKE");
    assertThat(result.costingRows())
        .noneMatch(row -> "DIRECT-MAKE".equals(row.getParentCode())
            && "BYPRODUCT_EXTRA".equals(row.getSettlementRowType()));
  }

  @Test
  @DisplayName("当前加工层采购件没有废料映射时，父件副产品作为附加结算行输出")
  void byproductWithoutAnyScrapMappingAddsExtraRow() {
    BomSettlementRowBuildResult result = engine.build(byproductRequest(
        List.of(
            node("P", "P", null, 0, "/P/", 0, "组件", null),
            node("MAKE-1", "P", "P", 1, "/P/MAKE-1/", 0, "制造件一", null),
            purchaseNode("RAW-1", "P", "MAKE-1", 2, "/P/MAKE-1/RAW-1/", "原材料一")
        ),
        List.of(byproduct("MAKE-1", "BYP-1", "副产品一")),
        List.of(),
        List.of(byproductRule())));

    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("BYP-1", "RAW-1");
    BomCostingRow byproductRow = result.costingRows().getFirst();
    assertThat(byproductRow.getSettlementRowType()).isEqualTo("BYPRODUCT_EXTRA");
    assertThat(byproductRow.getParentCode()).isEqualTo("MAKE-1");
    assertThat(byproductRow.getMaterialName()).isEqualTo("制造件一/SPEC 废料");
    assertThat(byproductRow.getQtyPerParent()).isEqualByComparingTo(BigDecimal.valueOf(-1));
    assertThat(byproductRow.getQtyPerTop()).isEqualByComparingTo(BigDecimal.valueOf(-1));
    assertThat(byproductRow.getMatchedSettlementRuleId()).isEqualTo(90L);
    assertThat(result.stats().extraRowBucketCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("上卷父件不额外输出副产品，非上卷叶子的制造件直接父级仍输出副产品")
  void rolledUpParentDoesNotAddByproductButDefaultLeafParentDoes() {
    BomSettlementRowBuildResult result = engine.build(byproductRequest(
        List.of(
            node("P", "P", null, 0, "/P/", 0, "组件", null),
            fullNode(
                "ROLL-MAKE", "P", "P", 1, "/P/ROLL-MAKE/", 0, "上卷制造件", "制造件", "制造件",
                "12", "专用件", null, "主制造", LocalDate.of(2026, 1, 1), null),
            purchaseNode("COPPER-BAR", "P", "ROLL-MAKE", 2, "/P/ROLL-MAKE/COPPER-BAR/", "圆形黄铜棒"),
            fullNode(
                "NORMAL-MAKE", "P", "P", 1, "/P/NORMAL-MAKE/", 0, "普通制造件", "制造件", "制造件",
                "12", "专用件", null, "主制造", LocalDate.of(2026, 1, 1), null),
            purchaseNode("RAW-1", "P", "NORMAL-MAKE", 2, "/P/NORMAL-MAKE/RAW-1/", "普通采购件")
        ),
        List.of(
            byproduct("ROLL-MAKE", "BYP-ROLL", "上卷废料"),
            byproduct("NORMAL-MAKE", "BYP-NORMAL", "普通废料")),
        List.of(),
        List.of(byproductRule()),
        List.of(rollupRule("SPECIAL_PURCHASE_ROLLUP_BAR", "圆形黄铜棒", 10))));

    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("ROLL-MAKE", "BYP-NORMAL", "RAW-1");
    assertThat(result.costingRows()).noneMatch(row -> "BYP-ROLL".equals(row.getMaterialCode()));
    BomCostingRow byproductRow = result.costingRows().get(1);
    assertThat(byproductRow.getSettlementRowType()).isEqualTo("BYPRODUCT_EXTRA");
    assertThat(byproductRow.getParentCode()).isEqualTo("NORMAL-MAKE");
    assertThat(byproductRow.getMaterialName()).isEqualTo("普通制造件/SPEC 废料");
    assertThat(byproductRow.getQtyPerTop()).isEqualByComparingTo(BigDecimal.valueOf(-1));
  }

  @Test
  @DisplayName("子制造件上卷时，同码废料映射只覆盖子件且上级副产品仍输出")
  void rollupChildSkipsItselfButAddsAncestorByproduct() {
    BomSettlementRowBuildResult result = engine.build(byproductRequest(
        List.of(
            node("P", "P", null, 0, "/P/", 0, "组件", null),
            fullNode(
                "PARENT-MAKE", "P", "P", 1, "/P/PARENT-MAKE/", 0, "端板", "制造件", "制造件",
                "12", "专用件", null, "主制造", LocalDate.of(2026, 1, 1), null),
            fullNode(
                "ROLL-CHILD", "P", "PARENT-MAKE", 2, "/P/PARENT-MAKE/ROLL-CHILD/", 0, "端板", "制造件", "制造件",
                "12", "专用件", null, "主制造", LocalDate.of(2026, 1, 1), null),
            purchaseNode("RAW-STEEL", "P", "ROLL-CHILD", 3, "/P/PARENT-MAKE/ROLL-CHILD/RAW-STEEL/", "不锈钢板")
        ),
        List.of(
            byproduct("PARENT-MAKE", "SCRAP-STEEL", "废不锈钢", new BigDecimal("0.0237")),
            byproduct("ROLL-CHILD", "SCRAP-STEEL", "上卷子件废料", new BigDecimal("0.0139"))),
        List.of(scrapRef("RAW-STEEL", "SCRAP-STEEL")),
        List.of(byproductRule()),
        List.of(rollupRule("SPECIAL_PURCHASE_ROLLUP_STEEL", "不锈钢板", 10))));

    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("ROLL-CHILD", "SCRAP-STEEL");
    assertThat(result.costingRows())
        .filteredOn(row -> "SCRAP-STEEL".equals(row.getMaterialCode()))
        .hasSize(1);
    BomCostingRow byproductRow = result.costingRows().get(1);
    assertThat(byproductRow.getSettlementRowType()).isEqualTo("BYPRODUCT_EXTRA");
    assertThat(byproductRow.getParentCode()).isEqualTo("PARENT-MAKE");
    assertThat(byproductRow.getMaterialName()).isEqualTo("端板/SPEC 废料");
    assertThat(byproductRow.getQtyPerTop()).isEqualByComparingTo(new BigDecimal("-0.0237"));
  }

  @Test
  @DisplayName("默认叶子行沿路径向上追溯，所有有副产品的制造件祖先都输出副产品")
  void leafAncestorsWithByproductAddExtraRows() {
    BomSettlementRowBuildResult result = engine.build(byproductRequest(
        List.of(
            node("P", "P", null, 0, "/P/", 0, "组件", null),
            fullNode(
                "GRAND-MAKE", "P", "P", 1, "/P/GRAND-MAKE/", 0, "上层制造件", "制造件", "制造件",
                "12", "专用件", null, "主制造", LocalDate.of(2026, 1, 1), null),
            fullNode(
                "DIRECT-MAKE", "P", "GRAND-MAKE", 2, "/P/GRAND-MAKE/DIRECT-MAKE/", 0, "直接制造件", "制造件", "制造件",
                "12", "专用件", null, "主制造", LocalDate.of(2026, 1, 1), null),
            purchaseNode("RAW-1", "P", "DIRECT-MAKE", 3, "/P/GRAND-MAKE/DIRECT-MAKE/RAW-1/", "普通采购件")
        ),
        List.of(
            byproduct("GRAND-MAKE", "BYP-GRAND", "上层废料"),
            byproduct("DIRECT-MAKE", "BYP-DIRECT", "直接废料")),
        List.of(),
        List.of(byproductRule())));

    assertThat(result.costingRows()).extracting(BomCostingRow::getMaterialCode)
        .containsExactly("BYP-GRAND", "BYP-DIRECT", "RAW-1");
  }

  private static BomSettlementBuildRequest request(
      List<BomSettlementNode> nodes, List<BomSettlementRule> rules) {
    return new BomSettlementBuildRequest(
        "OA-BSR-03",
        "P",
        LocalDate.of(2026, 5, 29),
        "2026-05",
        "bsr03",
        LocalDateTime.of(2026, 5, 29, 16, 30),
        "COMMERCIAL",
        "主制造",
        nodes,
        rules);
  }

  private static BomSettlementBuildRequest byproductRequest(
      List<BomSettlementNode> nodes,
      List<BomSettlementByproduct> byproducts,
      List<BomSettlementScrapRef> scrapRefs,
      List<BomByproductCostRule> byproductRules) {
    return new BomSettlementBuildRequest(
        "OA-BSR-06",
        "P",
        LocalDate.of(2026, 5, 29),
        "2026-05",
        "bsr06",
        LocalDateTime.of(2026, 5, 29, 19, 30),
        "COMMERCIAL",
        "主制造",
        nodes,
        List.of(),
        byproducts,
        scrapRefs,
        byproductRules);
  }

  private static BomSettlementBuildRequest byproductRequest(
      List<BomSettlementNode> nodes,
      List<BomSettlementByproduct> byproducts,
      List<BomSettlementScrapRef> scrapRefs,
      List<BomByproductCostRule> byproductRules,
      List<BomSettlementRule> settlementRules) {
    return new BomSettlementBuildRequest(
        "OA-BSR-06",
        "P",
        LocalDate.of(2026, 5, 29),
        "2026-05",
        "bsr06",
        LocalDateTime.of(2026, 5, 29, 19, 30),
        "COMMERCIAL",
        "主制造",
        nodes,
        settlementRules,
        byproducts,
        scrapRefs,
        byproductRules);
  }

  private static BomSettlementNode node(
      String materialCode,
      String topProductCode,
      String parentCode,
      int level,
      String path,
      int isLeaf,
      String materialName,
      String purchaseCategory) {
    return new BomSettlementNode(
        (long) Math.abs(path.hashCode()),
        topProductCode,
        parentCode,
        materialCode,
        level,
        path,
        BigDecimal.ONE,
        BigDecimal.ONE,
        materialName,
        "SPEC",
        isLeaf == 1 ? "采购件" : "制造件",
        isLeaf == 1 ? "采购件" : "制造件",
        "RM01",
        "18",
        purchaseCategory == null ? "普通主分类" : purchaseCategory,
        purchaseCategory,
        "主制造",
        "V1",
        1,
        isLeaf,
        LocalDate.of(2026, 1, 1),
        null,
        LocalDate.of(2026, 1, 1),
        "210",
        "COMMERCIAL",
        "COMMERCIAL",
        new BomSettlementSourceRef(
            "OA-BSR-03",
            100L,
            topProductCode,
            "RAW_PRODUCT_BOM",
            (long) Math.abs(path.hashCode()),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            topProductCode,
            null,
            null,
            null,
            path));
  }

  private static BomSettlementNode purchaseNode(
      String materialCode,
      String topProductCode,
      String parentCode,
      int level,
      String path,
      String materialName) {
    return fullNode(
        materialCode,
        topProductCode,
        parentCode,
        level,
        path,
        1,
        materialName,
        "采购件",
        "采购件",
        "18",
        "普通主分类",
        null,
        "主制造",
        LocalDate.of(2026, 1, 1),
        null);
  }

  private static BomSettlementNode purchasedNodeWithCategory(
      String materialCode,
      String topProductCode,
      String parentCode,
      int level,
      String path,
      String materialName,
      String materialCategoryCode,
      String mainCategoryName,
      String purchaseCategory) {
    return fullNode(
        materialCode,
        topProductCode,
        parentCode,
        level,
        path,
        1,
        materialName,
        "采购件",
        "采购件",
        materialCategoryCode,
        mainCategoryName,
        purchaseCategory,
        "主制造",
        LocalDate.of(2026, 1, 1),
        null);
  }

  private static BomSettlementNode virtualNode(
      String materialCode,
      String topProductCode,
      String parentCode,
      int level,
      String path,
      int isLeaf,
      String materialName,
      String materialCategoryCode) {
    return fullNode(
        materialCode,
        topProductCode,
        parentCode,
        level,
        path,
        isLeaf,
        materialName,
        "虚拟",
        "制造件",
        materialCategoryCode,
        materialCategoryCode != null && materialCategoryCode.startsWith("15155") ? "包装组件" : "普通虚拟件",
        null,
        "主制造",
        LocalDate.of(2026, 1, 1),
        null);
  }

  private static BomSettlementNode fullNode(
      String materialCode,
      String topProductCode,
      String parentCode,
      int level,
      String path,
      int isLeaf,
      String materialName,
      String shapeAttr,
      String productionCategory,
      String materialCategoryCode,
      String mainCategoryName,
      String purchaseCategory,
      String bomPurpose,
      LocalDate effectiveFrom,
      LocalDate effectiveTo) {
    return new BomSettlementNode(
        (long) Math.abs(path.hashCode()),
        topProductCode,
        parentCode,
        materialCode,
        level,
        path,
        BigDecimal.ONE,
        BigDecimal.ONE,
        materialName,
        "SPEC",
        shapeAttr,
        productionCategory,
        "RM01",
        materialCategoryCode,
        mainCategoryName,
        purchaseCategory,
        bomPurpose,
        "V1",
        1,
        isLeaf,
        effectiveFrom,
        effectiveTo,
        effectiveFrom,
        "210",
        "COMMERCIAL",
        "COMMERCIAL",
        new BomSettlementSourceRef(
            "OA-BSR-03",
            100L,
            topProductCode,
            "RAW_PRODUCT_BOM",
            (long) Math.abs(path.hashCode()),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            topProductCode,
            null,
            null,
            null,
            path));
  }

  private static BomSettlementNode withPurposeAndEffective(
      BomSettlementNode node, String bomPurpose, LocalDate effectiveFrom, LocalDate effectiveTo) {
    return new BomSettlementNode(
        node.sourceNodeId(),
        node.topProductCode(),
        node.parentCode(),
        node.materialCode(),
        node.level(),
        node.path(),
        node.qtyPerParent(),
        node.qtyPerTop(),
        node.materialName(),
        node.materialSpec(),
        node.shapeAttr(),
        node.productionCategory(),
        node.costElementCode(),
        node.materialCategoryCode(),
        node.mainCategoryName(),
        node.purchaseCategory(),
        bomPurpose,
        node.bomVersion(),
        node.u9IsCostFlag(),
        node.isLeaf(),
        effectiveFrom,
        effectiveTo,
        effectiveFrom,
        node.priceOrgCode(),
        node.materialOrganizationCode(),
        node.businessUnitType(),
        node.sourceRef());
  }

  private static BomSettlementRule rollupRule(String code, String materialNameKeyword, int priority) {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setId((long) priority);
    rule.setRuleCode(code);
    rule.setRuleName(code);
    rule.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    rule.setSettlementAction("ROLLUP_TO_PARENT");
    rule.setSettlementRowType("SPECIAL_ROLLUP_PARENT");
    rule.setSubRefType("SPECIAL_ROLLUP_CHILD");
    rule.setMatchConditionJson("""
        {"nodeConditions":[{"field":"material_name","op":"LIKE","value":"%s"}]}
        """.formatted(materialNameKeyword));
    rule.setMarkSubtreeCostRequired(1);
    rule.setPriority(priority);
    rule.setEnabled(1);
    return rule;
  }

  private static BomSettlementRule auxiliaryExcludeCodeRule(
      String code, String mainCategoryCode, int priority) {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setId((long) priority);
    rule.setRuleCode(code);
    rule.setRuleName(code);
    rule.setRuleCategory("AUXILIARY_EXCLUDE");
    rule.setSettlementAction("EXCLUDE");
    rule.setSettlementRowType("EXCLUDED");
    rule.setMatchConditionJson("""
        {"nodeConditions":[{"field":"main_category_code","op":"EQ","value":"%s"}]}
        """.formatted(mainCategoryCode));
    rule.setMarkSubtreeCostRequired(0);
    rule.setPriority(priority);
    rule.setEnabled(1);
    return rule;
  }

  private static BomSettlementByproduct byproduct(
      String parentMaterialCode, String byproductMaterialCode, String byproductMaterialName) {
    return byproduct(
        parentMaterialCode, byproductMaterialCode, byproductMaterialName, BigDecimal.ONE);
  }

  private static BomSettlementByproduct byproduct(
      String parentMaterialCode,
      String byproductMaterialCode,
      String byproductMaterialName,
      BigDecimal outputQty) {
    return new BomSettlementByproduct(
        900L,
        parentMaterialCode,
        byproductMaterialCode,
        byproductMaterialName,
        "BYP-SPEC",
        outputQty,
        "KG",
        "主制造",
        "V1",
        LocalDate.of(2026, 1, 1),
        null,
        "COMMERCIAL");
  }

  private static BomSettlementScrapRef scrapRef(String materialCode, String scrapCode) {
    return new BomSettlementScrapRef(
        materialCode,
        scrapCode,
        "COMMERCIAL",
        LocalDate.of(2026, 1, 1),
        null);
  }

  private static BomByproductCostRule byproductRule() {
    BomByproductCostRule rule = new BomByproductCostRule();
    rule.setId(90L);
    rule.setRuleCode("BYPRODUCT_EXTRA_WHEN_NO_SCRAP_REF");
    rule.setRuleName("副产品未命中废料映射时输出结算行");
    rule.setRuleCategory("BYPRODUCT_EXTRA");
    rule.setAddConditionType("NO_SCRAP_REF_MATCH");
    rule.setSettlementRowType("BYPRODUCT_EXTRA");
    rule.setMatchConditionJson("""
        {"byproductConditions":[{"field":"shape_attr","op":"EQ","value":"制造件"}]}
        """);
    rule.setPriority(90);
    rule.setEnabled(1);
    return rule;
  }
}
