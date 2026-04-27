package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FlattenRequest;
import com.sanhua.marketingcost.dto.FlattenResult;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomStopDrillRule;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSubRefMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomStopDrillRuleMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.BomFlattenService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * T11 · {@link BomFlattenServiceImpl} LEAF_ROLLUP_TO_PARENT 分支专项集成测试。
 *
 * <p>构造 raw_hierarchy fixture（绕过 T4 Builder）+ 插入 LEAF_ROLLUP 规则，
 * 然后调 flatten 并断言 lp_bom_costing_row + lp_bom_costing_row_sub_ref。
 *
 * <p>所有用例的 LEAF_ROLLUP 规则用 IN_DICT op + 字典 key 'bom_leaf_rollup_codes'，
 * 字典种子由 V43__bom_leaf_rollup_dict.sql 提供（拉制铜管编码 171711404 + 4 个 NAME 兜底）。
 */
@Tag("integration")
@DisplayName("T11 BomFlattenServiceImpl · LEAF_ROLLUP_TO_PARENT 分支")
class BomFlattenServiceImplLeafRollupTest extends BomMapperTestBase {

  @Autowired private BomFlattenService flattenService;
  @Autowired private BomRawHierarchyMapper rawMapper;
  @Autowired private BomCostingRowMapper costingMapper;
  @Autowired private BomCostingRowSubRefMapper subRefMapper;
  @Autowired private BomStopDrillRuleMapper ruleMapper;

  private final String oaNo = "OA_T11_" + UUID.randomUUID().toString().substring(0, 6);

  @AfterEach
  void cleanUp() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      // 先删 sub_ref（FK 级联，但显式删保稳）
      stmt.executeUpdate(
          "DELETE sr FROM lp_bom_costing_row_sub_ref sr "
              + "JOIN lp_bom_costing_row cr ON cr.id=sr.costing_row_id "
              + "WHERE cr.oa_no='" + oaNo + "'");
      stmt.executeUpdate("DELETE FROM lp_bom_costing_row WHERE oa_no='" + oaNo + "'");
      stmt.executeUpdate(
          "DELETE FROM lp_bom_raw_hierarchy WHERE build_batch_id LIKE 'rawt11_%'");
      // 本测试自定义规则按 match_value 前缀 'T11_' 清
      stmt.executeUpdate(
          "DELETE FROM bom_stop_drill_rule WHERE match_value LIKE 'T11\\_%' ESCAPE '\\\\'");
    }
  }

  // ============================ 10 集成测试 ============================

  @Test
  @DisplayName("testSingleLeafCopperRollup：父 P 下 1 铜管叶子 + 1 非铜管叶子 → costing=2 / sub_ref=1")
  void testSingleLeafCopperRollup() {
    // 结构：P → P/Cu1（拉制铜管，LEAF_ROLLUP 命中） + P/X1（防尘帽，未命中）
    seedTop("P", "主制造", "2026-01-01", 0);
    seedLeaf("P", "Cu1", 1, "拉制铜管 D8x0.5", "171711404");
    seedLeaf("P", "X1", 2, "防尘帽", null);

    insertLeafRollupRule();
    flattenService.flatten(req("P", "主制造", LocalDate.of(2026, 6, 1)));

    List<BomCostingRow> rows = loadCostingByOa();
    // 期望 2 行：父 P（subtree_required=1）+ X1（默认叶子）
    assertThat(rows).hasSize(2);

    BomCostingRow parent = findByCode(rows, "P");
    assertThat(parent.getSubtreeCostRequired()).as("父结算行 subtree=1").isEqualTo(1);
    assertThat(parent.getMatchedDrillRuleId()).as("父行回填 LEAF_ROLLUP 规则 id").isNotNull();
    assertThat(parent.getIsCostingRow()).isEqualTo(1);

    BomCostingRow x1 = findByCode(rows, "X1");
    assertThat(x1.getSubtreeCostRequired()).isEqualTo(0);
    assertThat(x1.getMatchedDrillRuleId()).isNull();

    // 命中铜管叶子 Cu1 不在 costing
    assertThat(rows.stream().noneMatch(r -> "Cu1".equals(r.getMaterialCode()))).isTrue();

    // sub_ref：1 行（Cu1 → P）
    List<BomCostingRowSubRef> refs = loadSubRefForOa();
    assertThat(refs).hasSize(1);
    assertThat(refs.get(0).getSubMaterialCode()).isEqualTo("Cu1");
  }

  @Test
  @DisplayName("testMultiLeafCopperSameParent：同父 2 铜管叶子 → 父 1 行（去重）+ sub_ref 2")
  void testMultiLeafCopperSameParent() {
    seedTop("P", "主制造", "2026-01-01", 0);
    seedLeaf("P", "Cu1", 1, "拉制铜管 D8", "171711404");
    seedLeaf("P", "Cu2", 2, "拉制铜管 D10", "171711404");
    seedLeaf("P", "X1", 3, "焊环", null);

    insertLeafRollupRule();
    flattenService.flatten(req("P", "主制造", LocalDate.of(2026, 6, 1)));

    List<BomCostingRow> rows = loadCostingByOa();
    assertThat(rows).hasSize(2); // 父 P + 焊环 X1
    assertThat(findByCode(rows, "P").getSubtreeCostRequired()).isEqualTo(1);

    List<BomCostingRowSubRef> refs = loadSubRefForOa();
    assertThat(refs).hasSize(2);
    assertThat(refs).extracting(BomCostingRowSubRef::getSubMaterialCode)
        .containsExactlyInAnyOrder("Cu1", "Cu2");
  }

  @Test
  @DisplayName("testMultiLeafDifferentParents：不同父 P1 P2 各 1 铜管 → 2 父行 + 2 sub_ref")
  void testMultiLeafDifferentParents() {
    // T → P1(中间) → P1/Cu1（拉制铜管）
    // T → P2(中间) → P2/Cu2（拉制铜管）
    seedTop("T", "主制造", "2026-01-01", 0);
    seedChildAt("T", "P1", "/T/P1/", 1, 1, "1", "主制造", "2026-01-01",
        "组件 P1", null, "采购件", 0);
    seedChildAt("T", "P2", "/T/P2/", 2, 1, "1", "主制造", "2026-01-01",
        "组件 P2", null, "采购件", 0);
    seedChildAt("P1", "Cu1", "/T/P1/Cu1/", 2, 1, "1", "主制造", "2026-01-01",
        "拉制铜管 A", "171711404", "采购件", 1);
    seedChildAt("P2", "Cu2", "/T/P2/Cu2/", 2, 1, "1", "主制造", "2026-01-01",
        "拉制铜管 B", "171711404", "采购件", 1);

    insertLeafRollupRule();
    flattenService.flatten(req("T", "主制造", LocalDate.of(2026, 6, 1)));

    List<BomCostingRow> rows = loadCostingByOa();
    // 期望 2 行：P1（subtree=1）+ P2（subtree=1）
    assertThat(rows).hasSize(2);
    assertThat(rows).extracting(BomCostingRow::getMaterialCode)
        .containsExactlyInAnyOrder("P1", "P2");
    assertThat(rows).allSatisfy(r -> assertThat(r.getSubtreeCostRequired()).isEqualTo(1));

    List<BomCostingRowSubRef> refs = loadSubRefForOa();
    assertThat(refs).hasSize(2);
    assertThat(refs).extracting(BomCostingRowSubRef::getSubMaterialCode)
        .containsExactlyInAnyOrder("Cu1", "Cu2");
  }

  @Test
  @DisplayName("testCopperLeafIsTopLevel：顶层直接叶子是铜管 → warning + 按默认叶子入 costing")
  void testCopperLeafIsTopLevel() {
    // 顶层 T 自己就是叶子，名字命中"拉制铜管"
    BomRawHierarchy top = new BomRawHierarchy();
    top.setTopProductCode("T");
    top.setParentCode("T");
    top.setMaterialCode("T");
    top.setLevel(0);
    top.setPath("/T/");
    top.setQtyPerTop(BigDecimal.ONE);
    top.setBomPurpose("主制造");
    top.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    top.setEffectiveTo(LocalDate.of(9999, 12, 31));
    top.setSourceType("U9");
    top.setIsLeaf(1);  // 顶层直接是叶子
    top.setMaterialName("拉制铜管 单根");
    top.setMaterialCategory1("171711404");
    top.setSourceImportBatchId("rawt11_imp_" + UUID.randomUUID().toString().substring(0, 4));
    top.setBuildBatchId("rawt11_" + UUID.randomUUID().toString().substring(0, 6));
    top.setBuiltAt(LocalDateTime.now());
    rawMapper.insert(top);

    insertLeafRollupRule();
    FlattenResult r = flattenService.flatten(req("T", "主制造", LocalDate.of(2026, 6, 1)));

    List<BomCostingRow> rows = loadCostingByOa();
    // 顶层叶子无父可上卷 → 按默认叶子入 costing（subtree=0）
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getMaterialCode()).isEqualTo("T");
    assertThat(rows.get(0).getSubtreeCostRequired()).isZero();
    // sub_ref 应为 0
    assertThat(loadSubRefForOa()).isEmpty();
    // 应有 LEAF_ROLLUP_TOP_LEAF warning
    assertThat(r.getWarnings()).anyMatch(w -> w.contains("LEAF_ROLLUP_TOP_LEAF"));
  }

  @Test
  @DisplayName("testParentAlreadyStopped：父被 NAME_LIKE STOP 命中 → 铜管叶子在 stoppedSubtree 下也跳过")
  void testParentAlreadyStopped() {
    // T → T1（name='D接管' 被 V40 内置 NAME_LIKE 接管 STOP_AND_COST_ROW 命中）
    //  → T1A（拉制铜管）
    seedTop("T", "主制造", "2026-01-01", 0);
    seedChildAt("T", "T1", "/T/T1/", 1, 1, "1", "主制造", "2026-01-01",
        "D接管", null, "采购件", 0);
    seedChildAt("T1", "Cu", "/T/T1/Cu/", 2, 1, "1", "主制造", "2026-01-01",
        "拉制铜管 X", "171711404", "采购件", 1);

    insertLeafRollupRule();
    FlattenResult r = flattenService.flatten(req("T", "主制造", LocalDate.of(2026, 6, 1)));

    List<BomCostingRow> rows = loadCostingByOa();
    // 期望仅 T1（接管规则命中，subtree=1）；铜管叶子在 T1 子树下被剔除
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getMaterialCode()).isEqualTo("T1");
    assertThat(rows.get(0).getSubtreeCostRequired()).isEqualTo(1);
    // sub_ref 0（铜管叶子根本没进 LEAF_ROLLUP 分支，因为父先 STOP 把整子树屏蔽）
    assertThat(loadSubRefForOa()).isEmpty();
    // 此场景下 LEAF_ROLLUP 没真触发 → 不产生 LEAF_ROLLUP warning
    assertThat(r.getWarnings()).noneMatch(w -> w.contains("LEAF_ROLLUP"));
  }

  @Test
  @DisplayName("testNonLeafHitsRule：中间节点匹配字典（罕见但兜底）→ warning + 中间节点不入 costing 子件继续下钻")
  void testNonLeafHitsRule() {
    // T → P（中间节点；name='拉制铜管接头组件' 名称命中关键词"拉制铜管"）→ P/Leaf（叶子）
    seedTop("T", "主制造", "2026-01-01", 0);
    seedChildAt("T", "P", "/T/P/", 1, 1, "1", "主制造", "2026-01-01",
        "拉制铜管接头组件", null, "采购件", 0); // is_leaf=0
    seedChildAt("P", "Leaf1", "/T/P/Leaf1/", 2, 1, "1", "主制造", "2026-01-01",
        "防尘帽", null, "采购件", 1);

    insertLeafRollupRule();
    FlattenResult r = flattenService.flatten(req("T", "主制造", LocalDate.of(2026, 6, 1)));

    List<BomCostingRow> rows = loadCostingByOa();
    // 期望：P 不入（中间节点 + LEAF_ROLLUP 已 warn 跳过；走未命中分支 → 中间节点跳过），
    //   其子件 Leaf1 默认叶子入 costing
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getMaterialCode()).isEqualTo("Leaf1");
    assertThat(r.getWarnings()).anyMatch(w -> w.contains("LEAF_ROLLUP_NOT_LEAF"));
    assertThat(loadSubRefForOa()).isEmpty();
  }

  @Test
  @DisplayName("testNullCategoryHitsByName：cat1=NULL + name='紫铜直管 phi12' → 名称兜底命中")
  void testNullCategoryHitsByName() {
    seedTop("P", "主制造", "2026-01-01", 0);
    seedLeaf("P", "Cu1", 1, "紫铜直管 phi12 L80", null); // cat1=NULL，靠 NAME:紫铜直管 命中
    seedLeaf("P", "X1", 2, "防尘帽", null);

    insertLeafRollupRule();
    flattenService.flatten(req("P", "主制造", LocalDate.of(2026, 6, 1)));

    List<BomCostingRow> rows = loadCostingByOa();
    // 期望 2 行：P + X1
    assertThat(rows).hasSize(2);
    assertThat(findByCode(rows, "P").getSubtreeCostRequired()).isEqualTo(1);

    List<BomCostingRowSubRef> refs = loadSubRefForOa();
    assertThat(refs).hasSize(1);
    assertThat(refs.get(0).getSubMaterialCode()).isEqualTo("Cu1");
    // sub_ref 落的 sub_qty_per_parent 应是叶子的 qty_per_parent（子件相对父用量）
    assertThat(refs.get(0).getSubQtyPerParent()).isEqualTo(new BigDecimal("1.00000000"));
  }

  @Test
  @DisplayName("testCoexistWithLegacyRollup：同一拍平既有老 ROLLUP 命中又有新 LEAF_ROLLUP 命中互不干扰")
  void testCoexistWithLegacyRollup() {
    // 结构 1（命中 V41 老 ROLLUP）：T → A（cost_element='主要材料-原材料'，
    //   有子件 X 主分类='紫铜盘管' → 命中 V41 内置 'copper-tube-assembly' COMPOSITE 规则）
    //   注意：V43 已停用该规则（enabled=0）→ 老 ROLLUP 不再触发。
    //   要测共存必须自己插一条新的老 ROLLUP 规则。
    // 用 NAME_LIKE TEST 简单触发：name 含 'T11_RollupTrigger' → ROLLUP_TO_PARENT
    seedTop("T", "主制造", "2026-01-01", 0);
    seedChildAt("T", "A", "/T/A/", 1, 1, "1", "主制造", "2026-01-01",
        "T11_RollupTrigger 组件A", null, "采购件", 0);
    seedChildAt("A", "AC", "/T/A/AC/", 2, 1, "1", "主制造", "2026-01-01",
        "A的子件", null, "采购件", 1);
    // 结构 2（命中 LEAF_ROLLUP）：T → B(中间) → B/Cu(铜管叶子)
    seedChildAt("T", "B", "/T/B/", 2, 1, "1", "主制造", "2026-01-01",
        "组件B", null, "采购件", 0);
    seedChildAt("B", "Cu", "/T/B/Cu/", 2, 1, "1", "主制造", "2026-01-01",
        "拉制铜管 z", "171711404", "采购件", 1);
    seedChildAt("B", "BX", "/T/B/BX/", 3, 1, "1", "主制造", "2026-01-01",
        "B非铜管子件", null, "采购件", 1);

    // 老 ROLLUP 规则（NAME_LIKE 命中 A）
    insertRule("NAME_LIKE", "T11_RollupTrigger", "ROLLUP_TO_PARENT", 1, 4);
    // LEAF_ROLLUP 规则
    insertLeafRollupRule();

    flattenService.flatten(req("T", "主制造", LocalDate.of(2026, 6, 1)));

    List<BomCostingRow> rows = loadCostingByOa();
    // 期望：
    //   A（老 ROLLUP，subtree=1）
    //   B（LEAF_ROLLUP，subtree=1）
    //   BX（B 的非铜管兄弟，默认叶子）
    //   AC 不入（在 A 子树下被 stoppedPaths 屏蔽）
    //   Cu 不入（被 LEAF_ROLLUP 消化）
    assertThat(rows).hasSize(3);
    assertThat(rows).extracting(BomCostingRow::getMaterialCode)
        .containsExactlyInAnyOrder("A", "B", "BX");
    assertThat(findByCode(rows, "A").getSubtreeCostRequired()).isEqualTo(1);
    assertThat(findByCode(rows, "B").getSubtreeCostRequired()).isEqualTo(1);
    assertThat(findByCode(rows, "BX").getSubtreeCostRequired()).isZero();

    // sub_ref：老 ROLLUP 把 A 的所有直接子件（AC）都写进去 + LEAF_ROLLUP 只写 Cu
    List<BomCostingRowSubRef> refs = loadSubRefForOa();
    assertThat(refs).extracting(BomCostingRowSubRef::getSubMaterialCode)
        .containsExactlyInAnyOrder("AC", "Cu");
  }

  @Test
  @DisplayName("testIdempotentRerun：同 OA + asOfDate 重跑 → 行数稳定 + sub_ref 不重复")
  void testIdempotentRerun() {
    seedTop("P", "主制造", "2026-01-01", 0);
    seedLeaf("P", "Cu1", 1, "拉制铜管", "171711404");
    seedLeaf("P", "X1", 2, "防尘帽", null);

    insertLeafRollupRule();
    flattenService.flatten(req("P", "主制造", LocalDate.of(2026, 6, 1)));
    int firstCount = loadCostingByOa().size();
    int firstSubRef = loadSubRefForOa().size();

    flattenService.flatten(req("P", "主制造", LocalDate.of(2026, 6, 1)));
    assertThat(loadCostingByOa()).hasSize(firstCount);
    assertThat(loadSubRefForOa()).hasSize(firstSubRef);
  }

  @Test
  @Tag("real-bom")
  @DisplayName("testRealLeafRollupOnGolden：真实 Excel 导入 → 1079900000536 拍平 → 验证 LEAF_ROLLUP 父行 + sub_ref")
  void testRealLeafRollupOnGolden() throws Exception {
    String defaultPath = System.getProperty("user.home") + "/Desktop/BOMMaster20260423.xlsx";
    java.nio.file.Path realXlsx = java.nio.file.Path.of(
        System.getProperty("bom.real.xlsx.path", defaultPath));
    org.junit.jupiter.api.Assumptions.assumeTrue(
        java.nio.file.Files.exists(realXlsx), "真实 Excel 不存在，跳过: " + realXlsx);

    com.sanhua.marketingcost.service.BomImportService bomImportService =
        applicationContext.getBean(com.sanhua.marketingcost.service.BomImportService.class);
    com.sanhua.marketingcost.service.BomHierarchyBuildService buildService =
        applicationContext.getBean(com.sanhua.marketingcost.service.BomHierarchyBuildService.class);

    String goldenOa = "OA-GOLDEN-LEAFROLL-" + UUID.randomUUID().toString().substring(0, 8);
    insertLeafRollupRule(); // 本测试新加 LEAF_ROLLUP 规则

    try {
      // 1) 导入 Excel
      com.sanhua.marketingcost.dto.BomImportResult ir;
      try (java.io.InputStream in = java.nio.file.Files.newInputStream(realXlsx)) {
        ir = bomImportService.importExcel(in, realXlsx.getFileName().toString(), "tester-t11");
      }
      // 2) 构建 1079900000536 主制造层级
      com.sanhua.marketingcost.dto.BuildHierarchyRequest br =
          new com.sanhua.marketingcost.dto.BuildHierarchyRequest();
      br.setImportBatchId(ir.getImportBatchId());
      br.setBomPurpose("主制造");
      br.setMode("BY_PRODUCT");
      br.setTopProductCode("1079900000536");
      buildService.build(br);

      // 3) 拍平
      FlattenRequest fr = new FlattenRequest();
      fr.setOaNo(goldenOa);
      fr.setTopProductCode("1079900000536");
      fr.setBomPurpose("主制造");
      fr.setMode("BY_OA");
      fr.setAsOfDate(LocalDate.of(2026, 4, 27));
      FlattenResult fResult = flattenService.flatten(fr);

      // 4) 报告关键统计
      List<BomCostingRow> rows = costingMapper.selectList(
          Wrappers.<BomCostingRow>lambdaQuery()
              .eq(BomCostingRow::getOaNo, goldenOa));
      long parentRowsLeafRollup = rows.stream()
          .filter(r -> Integer.valueOf(1).equals(r.getSubtreeCostRequired()))
          .count();
      long defaultLeafRows = rows.stream()
          .filter(r -> Integer.valueOf(0).equals(r.getSubtreeCostRequired()))
          .count();
      List<BomCostingRowSubRef> allRefs = subRefMapper.selectList(
          Wrappers.<BomCostingRowSubRef>lambdaQuery()
              .in(BomCostingRowSubRef::getCostingRowId,
                  rows.stream().map(BomCostingRow::getId).toList()));

      System.out.printf(
          "[T11-REAL] oa=%s 总 costing=%d 父结算行(subtree=1)=%d 默认叶子结算=%d sub_ref=%d warnings=%d%n",
          goldenOa, rows.size(), parentRowsLeafRollup, defaultLeafRows,
          allRefs.size(), fResult.getWarnings().size());

      // 抽样：sub_ref 中至少应含名字命中"拉制铜管"或编码 171711404 的子件（验证 LEAF_ROLLUP 真触发了）
      long copperLikeRefs = allRefs.stream()
          .filter(r -> (r.getSubMaterialName() != null && r.getSubMaterialName().contains("拉制铜管"))
              || "171711404".equals(r.getSubMaterialCategory()))
          .count();
      System.out.printf("[T11-REAL] sub_ref 中 拉制铜管/编码命中 数=%d%n", copperLikeRefs);
      // 不强断言 > 0，因为真实 Excel 数据可能完全没有铜管；只作可见性
      assertThat(rows).isNotEmpty();
    } finally {
      // 清理
      try (Connection conn = openConnection();
          Statement stmt = conn.createStatement()) {
        stmt.executeUpdate(
            "DELETE sr FROM lp_bom_costing_row_sub_ref sr "
                + "JOIN lp_bom_costing_row cr ON cr.id=sr.costing_row_id "
                + "WHERE cr.oa_no='" + goldenOa + "'");
        stmt.executeUpdate("DELETE FROM lp_bom_costing_row WHERE oa_no='" + goldenOa + "'");
        stmt.executeUpdate(
            "DELETE FROM lp_bom_raw_hierarchy WHERE top_product_code='1079900000536'");
        stmt.executeUpdate("DELETE FROM lp_bom_u9_source WHERE import_batch_id LIKE 'b_2026%'");
      }
    }
  }

  /** 真实 BOM 测试用，按需取 BomImportService / BomHierarchyBuildService */
  @Autowired private org.springframework.context.ApplicationContext applicationContext;

  @Test
  @DisplayName("testSubtreeReqAndRuleIdSet：父行 subtree_cost_required=1 + matched_drill_rule_id=该规则 id")
  void testSubtreeReqAndRuleIdSet() {
    seedTop("P", "主制造", "2026-01-01", 0);
    seedLeaf("P", "Cu1", 1, "拉制铜管", "171711404");

    BomStopDrillRule rule = insertLeafRollupRule();
    flattenService.flatten(req("P", "主制造", LocalDate.of(2026, 6, 1)));

    List<BomCostingRow> rows = loadCostingByOa();
    BomCostingRow parent = findByCode(rows, "P");
    assertThat(parent.getSubtreeCostRequired()).isEqualTo(1);
    assertThat(parent.getMatchedDrillRuleId()).isEqualTo(rule.getId());
  }

  // ============================ 辅助 ============================

  private FlattenRequest req(String top, String purpose, LocalDate asOf) {
    FlattenRequest r = new FlattenRequest();
    r.setOaNo(oaNo);
    r.setTopProductCode(top);
    r.setBomPurpose(purpose);
    r.setMode("BY_OA");
    r.setAsOfDate(asOf);
    return r;
  }

  private List<BomCostingRow> loadCostingByOa() {
    return costingMapper.selectList(
        Wrappers.<BomCostingRow>lambdaQuery()
            .eq(BomCostingRow::getOaNo, oaNo)
            .orderByAsc(BomCostingRow::getLevel));
  }

  /** 反查本 OA 父行 id，再按 id 拉本次 sub_ref */
  private List<BomCostingRowSubRef> loadSubRefForOa() {
    List<BomCostingRow> rows = loadCostingByOa();
    if (rows.isEmpty()) return List.of();
    List<Long> ids = rows.stream().map(BomCostingRow::getId).toList();
    return subRefMapper.selectList(
        Wrappers.<BomCostingRowSubRef>lambdaQuery()
            .in(BomCostingRowSubRef::getCostingRowId, ids));
  }

  private static BomCostingRow findByCode(List<BomCostingRow> rows, String code) {
    return rows.stream().filter(r -> code.equals(r.getMaterialCode())).findFirst()
        .orElseThrow(() -> new AssertionError("找不到 material_code=" + code));
  }

  /** 顶层 raw 行（level=0, path=/top/） */
  private void seedTop(String top, String purpose, String effFrom, int isLeaf) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setTopProductCode(top);
    row.setParentCode(top);
    row.setMaterialCode(top);
    row.setLevel(0);
    row.setPath("/" + top + "/");
    row.setQtyPerTop(BigDecimal.ONE);
    row.setBomPurpose(purpose);
    row.setEffectiveFrom(LocalDate.parse(effFrom));
    row.setEffectiveTo(LocalDate.of(9999, 12, 31));
    row.setSourceType("U9");
    row.setIsLeaf(isLeaf);
    row.setMaterialName(top + "_name");
    row.setSourceImportBatchId("rawt11_imp_" + UUID.randomUUID().toString().substring(0, 4));
    row.setBuildBatchId("rawt11_" + UUID.randomUUID().toString().substring(0, 6));
    row.setBuiltAt(LocalDateTime.now());
    rawMapper.insert(row);
  }

  /** 直接父=top，level=1 的叶子 */
  private void seedLeaf(String parent, String child, int sortSeq, String name, String cat1) {
    seedChildAt(parent, child, "/" + parent + "/" + child + "/",
        1, sortSeq, "1", "主制造", "2026-01-01", name, cat1, "采购件", 1);
  }

  private void seedChildAt(
      String parent, String child, String path, int level, int sortSeq, String qty,
      String purpose, String effFrom, String name, String cat1, String sourceCategory,
      int isLeaf) {
    BomRawHierarchy row = new BomRawHierarchy();
    String top = path.substring(1, path.indexOf('/', 1));
    row.setTopProductCode(top);
    row.setParentCode(parent);
    row.setMaterialCode(child);
    row.setLevel(level);
    row.setPath(path);
    row.setSortSeq(sortSeq);
    row.setQtyPerParent(new BigDecimal(qty));
    row.setQtyPerTop(new BigDecimal(qty));
    row.setBomPurpose(purpose);
    row.setEffectiveFrom(LocalDate.parse(effFrom));
    row.setEffectiveTo(LocalDate.of(9999, 12, 31));
    row.setSourceType("U9");
    row.setIsLeaf(isLeaf);
    row.setMaterialName(name);
    row.setMaterialCategory1(cat1);
    row.setSourceCategory(sourceCategory);
    // T11 增强：默认填 cost_element_code='No101'（U9 主要材料-原材料），让 LEAF_ROLLUP IN_DICT 的
    //   3 路与前置条件 (cost_element ∈ 原材料字典) 满足。fixture 模拟的"拉制铜管 / 防尘帽" 等节点
    //   都按"原材料"对待 —— 即使是 X1 防尘帽这种非命中节点，cost_element 设啥都不影响（cat1/name 不命中字典 → IN_DICT 短路 false）
    row.setCostElementCode("No101");
    row.setSourceImportBatchId("rawt11_imp_" + UUID.randomUUID().toString().substring(0, 4));
    row.setBuildBatchId("rawt11_" + UUID.randomUUID().toString().substring(0, 6));
    row.setBuiltAt(LocalDateTime.now());
    rawMapper.insert(row);
  }

  /** 通用规则插入 */
  private BomStopDrillRule insertRule(
      String type, String value, String action, int markSubtree, int priority) {
    BomStopDrillRule rule = new BomStopDrillRule();
    rule.setMatchType(type);
    rule.setMatchValue(value);
    rule.setDrillAction(action);
    rule.setMarkSubtreeCostRequired(markSubtree);
    rule.setPriority(priority);
    rule.setEnabled(1);
    ruleMapper.insert(rule);
    return rule;
  }

  /** T11 标准 LEAF_ROLLUP 规则：COMPOSITE + nodeConditions IN_DICT bom_leaf_rollup_codes */
  private BomStopDrillRule insertLeafRollupRule() {
    BomStopDrillRule rule = new BomStopDrillRule();
    rule.setMatchType("COMPOSITE");
    rule.setMatchValue("T11_LeafRollup_" + UUID.randomUUID().toString().substring(0, 4));
    rule.setMatchConditionJson(
        "{\"nodeConditions\":[{\"field\":\"material_category_1\",\"op\":\"IN_DICT\","
            + "\"value\":\"bom_leaf_rollup_codes\"}]}");
    rule.setDrillAction("LEAF_ROLLUP_TO_PARENT");
    rule.setMarkSubtreeCostRequired(1);
    rule.setPriority(3);
    rule.setEnabled(1);
    ruleMapper.insert(rule);
    return rule;
  }
}
