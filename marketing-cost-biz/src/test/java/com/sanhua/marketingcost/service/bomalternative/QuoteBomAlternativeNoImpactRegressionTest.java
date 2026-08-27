package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BomByproductCostRule;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleMatcher;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleConditionEvaluator;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleMatcher;
import com.sanhua.marketingcost.service.settlement.BomSettlementBuildRequest;
import com.sanhua.marketingcost.service.settlement.BomSettlementByproduct;
import com.sanhua.marketingcost.service.settlement.BomSettlementNode;
import com.sanhua.marketingcost.service.settlement.BomSettlementRowBuildEngine;
import com.sanhua.marketingcost.service.settlement.BomSettlementRowBuildResult;
import com.sanhua.marketingcost.service.settlement.BomSettlementScrapRef;
import com.sanhua.marketingcost.service.settlement.BomSettlementSourceRef;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-13 无替代BOM逐字段无影响回归")
class QuoteBomAlternativeNoImpactRegressionTest {

  private static final LocalDate AS_OF =
      LocalDate.of(2026, 7, 30);
  private static final String PERIOD = "2026-07";
  private final BomAlternativeBranchPruner branchPruner =
      new BomAlternativeBranchPrunerImpl();
  private final BomSettlementRowBuildEngine engine =
      new BomSettlementRowBuildEngine(
          new BomSettlementRuleMatcher(
              new BomSettlementRuleConditionEvaluator(
                  new ObjectMapper())),
          new BomByproductCostRuleMatcher(
              new BomByproductCostRuleConditionEvaluator(
                  new ObjectMapper())));

  @Test
  @DisplayName("普通商用报价没有替代组时结算行逐字段不变")
  void ordinaryCommercialBomIsUnchanged() {
    Scenario scenario =
        new Scenario(
            "COMM-TOP",
            List.of(
                raw(
                    "COMM-TOP",
                    "COMM-TOP",
                    null,
                    0,
                    "/COMM-TOP/",
                    "1",
                    "制造件",
                    "210",
                    null),
                raw(
                    "COMM-RAW",
                    "COMM-TOP",
                    "COMM-TOP",
                    1,
                    "/COMM-TOP/COMM-RAW/",
                    "2.5",
                    "采购件",
                    "210",
                    null)),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    Comparison comparison = assertNoImpact(scenario);

    assertThat(comparison.candidate().rowFingerprints())
        .singleElement()
        .satisfies(
            row ->
                assertThat(row)
                    .contains("COMM-RAW")
                    .contains("|2.5|2.5|")
                    .contains("|DEFAULT_LEAF|"));
  }

  @Test
  @DisplayName("板换跨组织上卷报价没有替代组时父件、子件引用和组织不变")
  void plateRollupBomIsUnchanged() {
    Scenario scenario =
        new Scenario(
            "PLATE-TOP",
            List.of(
                raw(
                    "PLATE-TOP",
                    "PLATE-TOP",
                    null,
                    0,
                    "/PLATE-TOP/",
                    "1",
                    "制造件",
                    "220",
                    null),
                raw(
                    "COMM-PARENT",
                    "PLATE-TOP",
                    "PLATE-TOP",
                    1,
                    "/PLATE-TOP/COMM-PARENT/",
                    "2",
                    "制造件",
                    "210",
                    null),
                rawWithTopQty(
                    "CU-RAW",
                    "PLATE-TOP",
                    "COMM-PARENT",
                    2,
                    "/PLATE-TOP/COMM-PARENT/CU-RAW/",
                    "0.25",
                    "0.5",
                    "采购件",
                    "210",
                    "拉制铜管")),
            List.of(drawnTubeRollupRule()),
            List.of(),
            List.of(),
            List.of());

    Comparison comparison = assertNoImpact(scenario);

    assertThat(comparison.candidate().rowFingerprints())
        .singleElement()
        .satisfies(
            row ->
                assertThat(row)
                    .contains("COMM-PARENT")
                    .contains("|SPECIAL_ROLLUP_PARENT|")
                    .contains("|210|COMMERCIAL|"));
    assertThat(comparison.candidate().subRefFingerprints())
        .singleElement()
        .satisfies(
            ref ->
                assertThat(ref)
                    .contains("|CU-RAW|拉制铜管|")
                    .contains("|0.25|0.5|"));
  }

  @Test
  @DisplayName("包装组件没有替代组时仍只输出包装父件并截断子件")
  void packageComponentBomIsUnchanged() {
    Scenario scenario =
        new Scenario(
            "PACKAGE-TOP",
            List.of(
                raw(
                    "PACKAGE-TOP",
                    "PACKAGE-TOP",
                    null,
                    0,
                    "/PACKAGE-TOP/",
                    "1",
                    "制造件",
                    "210",
                    null),
                raw(
                    "9830000026238",
                    "PACKAGE-TOP",
                    "PACKAGE-TOP",
                    1,
                    "/PACKAGE-TOP/9830000026238/",
                    "1",
                    "虚拟",
                    "210",
                    "包装组件"),
                raw(
                    "PKG-A",
                    "PACKAGE-TOP",
                    "9830000026238",
                    2,
                    "/PACKAGE-TOP/9830000026238/PKG-A/",
                    "2",
                    "采购件",
                    "210",
                    null),
                raw(
                    "PKG-B",
                    "PACKAGE-TOP",
                    "9830000026238",
                    2,
                    "/PACKAGE-TOP/9830000026238/PKG-B/",
                    "3",
                    "采购件",
                    "210",
                    null)),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    Comparison comparison = assertNoImpact(scenario);

    assertThat(comparison.candidate().rowFingerprints())
        .singleElement()
        .satisfies(
            row ->
                assertThat(row)
                    .contains("9830000026238")
                    .contains("|PACKAGE_PARENT|"));
    assertThat(comparison.candidate().rowFingerprints())
        .noneMatch(
            row ->
                row.contains("PKG-A")
                    || row.contains("PKG-B"));
  }

  @Test
  @DisplayName("包含副产品的无替代报价仍输出相同废料抵减行")
  void byproductBomIsUnchanged() {
    Scenario scenario =
        byproductScenario(List.of());

    Comparison comparison = assertNoImpact(scenario);

    assertThat(comparison.candidate().rowFingerprints())
        .anyMatch(
            row ->
                row.contains("|SCRAP-1|")
                    && row.contains("|-0.2|-0.2|")
                    && row.contains("|BYPRODUCT_EXTRA|"))
        .anyMatch(
            row ->
                row.contains("|RAW-1|")
                    && row.contains("|DEFAULT_LEAF|"));
  }

  @Test
  @DisplayName("原材料废料映射存在时新分支入口不会重复增加废料行")
  void materialScrapMappingIsUnchanged() {
    Scenario scenario =
        byproductScenario(
            List.of(
                new BomSettlementScrapRef(
                    "RAW-1",
                    "SCRAP-1",
                    "COMMERCIAL",
                    LocalDate.of(2026, 1, 1),
                    null)));

    Comparison comparison = assertNoImpact(scenario);

    assertThat(comparison.candidate().rowFingerprints())
        .singleElement()
        .satisfies(
            row ->
                assertThat(row)
                    .contains("|RAW-1|")
                    .contains("|DEFAULT_LEAF|"));
    assertThat(comparison.candidate().rowFingerprints())
        .noneMatch(row -> row.contains("|SCRAP-1|"));
  }

  private Comparison assertNoImpact(Scenario scenario) {
    BomAlternativePruneResult pruned =
        branchPruner.prune(
            new BomAlternativePruneRequest(
                scenario.rows(),
                List.of(),
                Map.of()));
    assertThat(pruned.inputNodeCount())
        .isEqualTo(scenario.rows().size());
    assertThat(pruned.outputNodeCount())
        .isEqualTo(scenario.rows().size());
    assertThat(pruned.removedNodeCount()).isZero();
    assertThat(rawFingerprints(pruned.nodes()))
        .containsExactlyElementsOf(
            rawFingerprints(scenario.rows()));

    Output baseline = build(scenario, scenario.rows());
    Output candidate = build(scenario, pruned.nodes());
    assertThat(candidate.rowFingerprints())
        .containsExactlyElementsOf(
            baseline.rowFingerprints());
    assertThat(candidate.subRefFingerprints())
        .containsExactlyElementsOf(
            baseline.subRefFingerprints());
    assertThat(candidate.sourceRefFingerprints())
        .containsExactlyElementsOf(
            baseline.sourceRefFingerprints());
    assertThat(candidate.warnings())
        .containsExactlyElementsOf(
            baseline.warnings());
    return new Comparison(baseline, candidate);
  }

  private Output build(
      Scenario scenario,
      List<BomRawHierarchy> rows) {
    BomSettlementRowBuildResult result =
        engine.build(
            new BomSettlementBuildRequest(
                "OA-QBA-13-" + scenario.top(),
                scenario.top(),
                AS_OF,
                PERIOD,
                "qba13-no-impact",
                LocalDateTime.of(2026, 7, 30, 12, 0),
                "COMMERCIAL",
                "主制造",
                toNodes(rows),
                scenario.rules(),
                scenario.byproducts(),
                scenario.scrapRefs(),
                scenario.byproductRules()));
    return new Output(
        result.costingRows().stream()
            .map(QuoteBomAlternativeNoImpactRegressionTest::rowFingerprint)
            .sorted()
            .toList(),
        result.subRefs().stream()
            .map(
                candidate -> {
                  var ref = candidate.subRef();
                  return String.join(
                      "|",
                      text(candidate.costingRowPath()),
                      text(ref.getRefType()),
                      text(ref.getSubMaterialCode()),
                      text(ref.getSubMaterialName()),
                      decimal(ref.getSubQtyPerParent()),
                      decimal(ref.getSubQtyPerTop()),
                      text(ref.getSubPath()));
                })
            .sorted()
            .toList(),
        result.sourceRefs().stream()
            .map(
                candidate ->
                    candidate.costingRowPath()
                        + "|"
                        + candidate.sourceRef().getSourcePath())
            .sorted()
            .toList(),
        result.warnings());
  }

  private static List<BomSettlementNode> toNodes(
      List<BomRawHierarchy> rows) {
    return rows.stream()
        .map(
            row ->
                new BomSettlementNode(
                    row.getId(),
                    row.getTopProductCode(),
                    row.getParentCode(),
                    row.getMaterialCode(),
                    row.getLevel(),
                    row.getPath(),
                    row.getQtyPerParent(),
                    row.getQtyPerTop(),
                    row.getMaterialName(),
                    row.getMaterialSpec(),
                    row.getShapeAttr(),
                    row.getSourceCategory(),
                    row.getCostElementCode(),
                    row.getMaterialCategory1(),
                    row.getMaterialCategory2(),
                    null,
                    row.getBomPurpose(),
                    row.getBomVersion(),
                    row.getU9IsCostFlag(),
                    row.getIsLeaf(),
                    row.getEffectiveFrom(),
                    row.getEffectiveTo(),
                    row.getEffectiveFrom(),
                    row.getPriceOrgCode(),
                    "220".equals(row.getPriceOrgCode())
                        ? "PLATE"
                        : "COMMERCIAL",
                    row.getBusinessUnitType(),
                    new BomSettlementSourceRef(
                        "OA-QBA-13-" + row.getTopProductCode(),
                        13L,
                        row.getTopProductCode(),
                        "RAW_PRODUCT_BOM",
                        row.getId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        row.getTopProductCode(),
                        null,
                        null,
                        row.getSourceU9RowId(),
                        row.getPath())))
        .toList();
  }

  private static Scenario byproductScenario(
      List<BomSettlementScrapRef> scrapRefs) {
    return new Scenario(
        "BYP-TOP",
        List.of(
            raw(
                "BYP-TOP",
                "BYP-TOP",
                null,
                0,
                "/BYP-TOP/",
                "1",
                "制造件",
                "210",
                null),
            raw(
                "MAKE-1",
                "BYP-TOP",
                "BYP-TOP",
                1,
                "/BYP-TOP/MAKE-1/",
                "1",
                "制造件",
                "210",
                null),
            raw(
                "RAW-1",
                "BYP-TOP",
                "MAKE-1",
                2,
                "/BYP-TOP/MAKE-1/RAW-1/",
                "1",
                "采购件",
                "210",
                null)),
        List.of(),
        List.of(
            new BomSettlementByproduct(
                1L,
                "MAKE-1",
                "SCRAP-1",
                "制造件废料",
                null,
                new BigDecimal("0.2"),
                "千克",
                "主制造",
                "V1",
                LocalDate.of(2026, 1, 1),
                null,
                "COMMERCIAL")),
        scrapRefs,
        List.of(byproductRule()));
  }

  private static BomRawHierarchy raw(
      String code,
      String top,
      String parent,
      int level,
      String path,
      String qty,
      String shape,
      String priceOrg,
      String specialName) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setId((long) Math.abs(path.hashCode()));
    row.setTopProductCode(top);
    row.setParentCode(parent);
    row.setMaterialCode(code);
    row.setMaterialName(
        specialName == null ? "名称-" + code : specialName);
    row.setMaterialSpec("图号-" + code);
    row.setLevel(level);
    row.setPath(path);
    row.setQtyPerParent(new BigDecimal(qty));
    row.setQtyPerTop(new BigDecimal(qty));
    row.setShapeAttr(shape);
    row.setSourceCategory(shape);
    row.setCostElementCode("No101");
    if ("虚拟".equals(shape)) {
      row.setMaterialCategory1("1515501");
      row.setMaterialCategory2("包装组件");
    }
    row.setBomPurpose("主制造");
    row.setBomVersion("V1");
    row.setU9IsCostFlag(1);
    row.setIsLeaf(
        "采购件".equals(shape) ? 1 : 0);
    row.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    row.setPriceOrgCode(priceOrg);
    row.setBusinessUnitType("COMMERCIAL");
    row.setSourceU9RowId(row.getId());
    return row;
  }

  private static BomRawHierarchy rawWithTopQty(
      String code,
      String top,
      String parent,
      int level,
      String path,
      String qtyPerParent,
      String qtyPerTop,
      String shape,
      String priceOrg,
      String specialName) {
    BomRawHierarchy row =
        raw(
            code,
            top,
            parent,
            level,
            path,
            qtyPerParent,
            shape,
            priceOrg,
            specialName);
    row.setQtyPerTop(new BigDecimal(qtyPerTop));
    return row;
  }

  private static BomSettlementRule drawnTubeRollupRule() {
    BomSettlementRule rule = new BomSettlementRule();
    rule.setId(13L);
    rule.setRuleCode(
        "SPECIAL_PURCHASE_ROLLUP_DRAWN_COPPER_TUBE");
    rule.setRuleName("拉制铜管上卷");
    rule.setRuleCategory("SPECIAL_PURCHASE_ROLLUP");
    rule.setSettlementAction("ROLLUP_TO_PARENT");
    rule.setSettlementRowType("SPECIAL_ROLLUP_PARENT");
    rule.setSubRefType("SPECIAL_ROLLUP_CHILD");
    rule.setMatchConditionJson(
        """
        {"nodeConditions":[{"field":"material_name","op":"LIKE","value":"拉制铜管"}]}
        """);
    rule.setMarkSubtreeCostRequired(1);
    rule.setPriority(13);
    rule.setEnabled(1);
    return rule;
  }

  private static BomByproductCostRule byproductRule() {
    BomByproductCostRule rule =
        new BomByproductCostRule();
    rule.setId(90L);
    rule.setRuleCode(
        "BYPRODUCT_EXTRA_WHEN_NO_SCRAP_REF");
    rule.setRuleName("副产品未映射时输出");
    rule.setRuleCategory("BYPRODUCT_EXTRA");
    rule.setAddConditionType("NO_SCRAP_REF_MATCH");
    rule.setSettlementRowType("BYPRODUCT_EXTRA");
    rule.setMatchConditionJson(
        """
        {"byproductConditions":[{"op":"EQ","field":"shape_attr","value":"制造件"}]}
        """);
    rule.setPriority(10);
    rule.setEnabled(1);
    return rule;
  }

  private static List<String> rawFingerprints(
      List<BomRawHierarchy> rows) {
    return rows.stream()
        .map(
            row ->
                String.join(
                    "|",
                    text(row.getParentCode()),
                    text(row.getMaterialCode()),
                    decimal(row.getQtyPerParent()),
                    decimal(row.getQtyPerTop()),
                    text(row.getLevel()),
                    text(row.getPath()),
                    text(row.getShapeAttr()),
                    text(row.getPriceOrgCode())))
        .sorted()
        .toList();
  }

  private static String rowFingerprint(
      BomCostingRow row) {
    return String.join(
        "|",
        text(row.getParentCode()),
        text(row.getMaterialCode()),
        text(row.getMaterialName()),
        text(row.getMaterialSpec()),
        decimal(row.getQtyPerParent()),
        decimal(row.getQtyPerTop()),
        text(row.getLevel()),
        text(row.getPath()),
        text(row.getSettlementRowType()),
        text(row.getSubtreeCostRequired()),
        text(row.getShapeAttr()),
        text(row.getPriceOrgCode()),
        text(row.getMaterialOrganizationCode()),
        text(row.getBusinessUnitType()));
  }

  private static String text(Object value) {
    return value == null ? "" : value.toString();
  }

  private static String decimal(BigDecimal value) {
    return value == null
        ? ""
        : value.stripTrailingZeros().toPlainString();
  }

  private record Scenario(
      String top,
      List<BomRawHierarchy> rows,
      List<BomSettlementRule> rules,
      List<BomSettlementByproduct> byproducts,
      List<BomSettlementScrapRef> scrapRefs,
      List<BomByproductCostRule> byproductRules) {}

  private record Output(
      List<String> rowFingerprints,
      List<String> subRefFingerprints,
      List<String> sourceRefFingerprints,
      List<String> warnings) {}

  private record Comparison(
      Output baseline, Output candidate) {}
}
