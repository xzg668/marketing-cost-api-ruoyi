package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.BomImportResult;
import com.sanhua.marketingcost.dto.BuildHierarchyRequest;
import com.sanhua.marketingcost.dto.BuildHierarchyResult;
import com.sanhua.marketingcost.dto.FlattenRequest;
import com.sanhua.marketingcost.dto.FlattenResult;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomStopDrillRule;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomStopDrillRuleMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.BomFlattenService;
import com.sanhua.marketingcost.service.BomHierarchyBuildService;
import com.sanhua.marketingcost.service.BomImportService;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link BomFlattenServiceImpl} 真实 DB 集成测试。
 *
 * <p>每个用例手工构造 lp_bom_raw_hierarchy 数据（绕过 T4 Builder）+ 可选插入自定义规则，
 * 然后调 flatten 并断言 lp_bom_costing_row 的结果。
 */
@Tag("integration")
@DisplayName("BomFlattenServiceImpl · 阶段 C 拍平 lp_bom_costing_row")
class BomFlattenServiceImplTest extends BomMapperTestBase {

  @Autowired private BomFlattenService flattenService;
  @Autowired private BomRawHierarchyMapper rawMapper;
  @Autowired private BomCostingRowMapper costingMapper;
  @Autowired private BomStopDrillRuleMapper ruleMapper;
  @Autowired private BomImportService bomImportService;
  @Autowired private BomHierarchyBuildService buildService;

  /** 每个测试的 OA 编号，{@code cleanUp} 按此清 costing_row */
  private final String oaNo = "OA_TEST_" + UUID.randomUUID().toString().substring(0, 6);

  @AfterEach
  void cleanUp() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate("DELETE FROM lp_bom_costing_row WHERE oa_no = '" + oaNo + "'");
      // raw_hierarchy：按本测试产生的 build_batch_id 前缀 'rawtest_' 清（见 seedRaw）
      stmt.executeUpdate("DELETE FROM lp_bom_raw_hierarchy WHERE build_batch_id LIKE 'rawtest_%'");
      // 自定义规则（非 V40 种子）按 match_value 前缀 'TEST_' 清
      stmt.executeUpdate(
          "DELETE FROM bom_stop_drill_rule WHERE match_value LIKE 'TEST\\_%' ESCAPE '\\\\'");
    }
  }

  @Test
  @DisplayName("testLeafDefaultIsCostingRow：纯叶子树无规则命中 → 所有叶子 is_costing_row=1")
  void testLeafDefaultIsCostingRow() {
    // 构造：T → T1 / T2 / T3（全是叶子），purpose=主制造
    seedTop("T", "主制造", "2026-01-01", 0);  // 顶层非叶子
    seedChild("T", "T1", 1, "1", "主制造", "2026-01-01", "T1_name", null, "采购件", 1);
    seedChild("T", "T2", 2, "1", "主制造", "2026-01-01", "T2_name", null, "采购件", 1);
    seedChild("T", "T3", 3, "1", "主制造", "2026-01-01", "T3_name", null, "采购件", 1);

    FlattenResult r = flattenService.flatten(req("T", "主制造", LocalDate.of(2026, 6, 1)));

    List<BomCostingRow> rows = loadCostingByOa();
    // 只有 3 个叶子入 costing_row（顶层非叶子未命中规则，跳过）
    assertThat(rows).hasSize(3);
    assertThat(rows).allSatisfy(c -> {
      assertThat(c.getIsCostingRow()).isEqualTo(1);
      assertThat(c.getSubtreeCostRequired()).isEqualTo(0);
      assertThat(c.getMatchedDrillRuleId()).isNull();
    });
    assertThat(r.getCostingRowsWritten()).isGreaterThan(0);
    assertThat(r.getSubtreeRequiredCount()).isZero();
  }

  @Test
  @DisplayName("testStopAndCostRow_SubtreeNotIncluded：NAME_LIKE 接管 命中 → 子孙不入 costing")
  void testStopAndCostRow_SubtreeNotIncluded() {
    // T → T1(name='D接管')→T1A(拉制铜管)；T1 被"接管"规则命中 STOP_AND_COST_ROW
    seedTop("T", "主制造", "2026-01-01", 0);
    seedChildAt("T", "T1", "/T/T1/", 1, 1, "1", "主制造", "2026-01-01",
        "D接管", null, "采购件", /*isLeaf=*/0);
    seedChildAt("T1", "T1A", "/T/T1/T1A/", 2, 1, "1", "主制造", "2026-01-01",
        "拉制铜管", null, "采购件", 1);

    // V40 种子已含 NAME_LIKE '接管' 规则，priority=10, mark_subtree=1
    FlattenResult r = flattenService.flatten(req("T", "主制造", LocalDate.of(2026, 6, 1)));

    List<BomCostingRow> rows = loadCostingByOa();
    assertThat(rows).hasSize(1);
    BomCostingRow t1 = rows.get(0);
    assertThat(t1.getMaterialCode()).isEqualTo("T1");
    assertThat(t1.getIsCostingRow()).isEqualTo(1);
    assertThat(t1.getSubtreeCostRequired()).as("接管规则 mark_subtree=1").isEqualTo(1);
    assertThat(t1.getMatchedDrillRuleId()).isNotNull();
    // 拉制铜管不在 costing_row
    assertThat(rows.stream().noneMatch(c -> "T1A".equals(c.getMaterialCode()))).isTrue();
    assertThat(r.getSubtreeRequiredCount()).isEqualTo(1);
  }

  // 注：原 testStopAndCostRow_NoMark（SHAPE_ATTR_EQ '部品联动'）已删除
  // 业务复核确认：取价始终以叶子料号为单位，"部品联动" 不是独立取价路由
  // 该 seed 规则和对应测试同时移除，避免误导后来者

  @Test
  @DisplayName("testExclude：规则 drill_action=EXCLUDE → 该节点不入 costing，子树也不下钻")
  void testExclude() {
    seedTop("T", "主制造", "2026-01-01", 0);
    seedChildAt("T", "T1", "/T/T1/", 1, 1, "1", "主制造", "2026-01-01",
        "TEST_被排除", null, "采购件", 0);
    seedChildAt("T1", "T1A", "/T/T1/T1A/", 2, 1, "1", "主制造", "2026-01-01",
        "T1A 子件", null, "采购件", 1);

    insertRule("NAME_LIKE", "TEST_被排除", "EXCLUDE", 0, 5);

    flattenService.flatten(req("T", "主制造", LocalDate.of(2026, 6, 1)));

    List<BomCostingRow> rows = loadCostingByOa();
    assertThat(rows).as("T1 被 EXCLUDE，T1A 在 T1 子树下也不入").isEmpty();
  }

  @Test
  @DisplayName("testMiddleNodeNotCosting：中间节点无规则命中 → 不入 costing，其子继续下钻")
  void testMiddleNodeNotCosting() {
    // T → T1(中间, name='中间件') → T1A(叶子)；T1 未命中任何规则
    seedTop("T", "主制造", "2026-01-01", 0);
    seedChildAt("T", "T1", "/T/T1/", 1, 1, "1", "主制造", "2026-01-01",
        "中间件", null, "采购件", 0);
    seedChildAt("T1", "T1A", "/T/T1/T1A/", 2, 1, "1", "主制造", "2026-01-01",
        "T1A 子件", null, "采购件", 1);

    flattenService.flatten(req("T", "主制造", LocalDate.of(2026, 6, 1)));

    List<BomCostingRow> rows = loadCostingByOa();
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getMaterialCode()).as("只有叶子 T1A 入 costing").isEqualTo("T1A");
  }

  @Test
  @DisplayName("testAsOfDateVersionSwap：两版本（2026-01 / 2026-05）按 asOfDate 返回不同快照")
  void testAsOfDateVersionSwap() {
    // V1：2026-01-01 生效，V2 生效前失效（2026-04-30）；T 有 C1 一个子件
    seedRawVersioned("T", "T", "T", "/T/", 0, null, "1",
        "主制造", "2026-01-01", "2026-04-30", "T_name", null, null, /*isLeaf=*/0);
    seedRawVersioned("T", "T", "C1", "/T/C1/", 1, "1", "1",
        "主制造", "2026-01-01", "2026-04-30", "C1_name", null, "采购件", 1);
    // V2：2026-05-01 生效到远期；T 有 C1 / C2
    seedRawVersioned("T", "T", "T", "/T/", 0, null, "1",
        "主制造", "2026-05-01", "9999-12-31", "T_name", null, null, 0);
    seedRawVersioned("T", "T", "C1", "/T/C1/", 1, "1", "1",
        "主制造", "2026-05-01", "9999-12-31", "C1_name", null, "采购件", 1);
    seedRawVersioned("T", "T", "C2", "/T/C2/", 2, "1", "1",
        "主制造", "2026-05-01", "9999-12-31", "C2_name", null, "采购件", 1);

    // asOfDate=2026-03 → V1 快照（1 个叶子）
    flattenService.flatten(req("T", "主制造", LocalDate.of(2026, 3, 1)));
    List<BomCostingRow> v1Rows = loadCostingByOa();
    assertThat(v1Rows.stream().filter(c -> c.getAsOfDate().equals(LocalDate.of(2026, 3, 1))).toList())
        .as("V1 快照 1 个叶子 C1")
        .hasSize(1);

    // asOfDate=2026-06 → V2 快照（2 个叶子）
    flattenService.flatten(req("T", "主制造", LocalDate.of(2026, 6, 1)));
    List<BomCostingRow> v2Rows = loadCostingByOa().stream()
        .filter(c -> c.getAsOfDate().equals(LocalDate.of(2026, 6, 1))).toList();
    assertThat(v2Rows).as("V2 快照 2 个叶子 C1/C2").hasSize(2);

    // V1 快照仍在（不同 as_of_date 并存）
    long v1Count =
        costingMapper.selectCount(
            Wrappers.<BomCostingRow>lambdaQuery()
                .eq(BomCostingRow::getOaNo, oaNo)
                .eq(BomCostingRow::getAsOfDate, LocalDate.of(2026, 3, 1)));
    assertThat(v1Count).as("V1 快照未被 V2 覆盖").isEqualTo(1L);
  }

  @Test
  @DisplayName("testAsOfDateFrozenInCostingRow：所有 costing_row.as_of_date 等于请求传入值")
  void testAsOfDateFrozenInCostingRow() {
    seedTop("T", "主制造", "2026-01-01", 0);
    seedChildAt("T", "C1", "/T/C1/", 1, 1, "1", "主制造", "2026-01-01",
        "C1_name", null, "采购件", 1);

    LocalDate chosen = LocalDate.of(2026, 3, 1);
    flattenService.flatten(req("T", "主制造", chosen));

    List<BomCostingRow> rows = loadCostingByOa();
    assertThat(rows).isNotEmpty();
    assertThat(rows).allSatisfy(c -> {
      assertThat(c.getAsOfDate()).isEqualTo(chosen);
      assertThat(c.getRawVersionEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
    });
  }

  @Test
  @DisplayName("testIdempotentRerun：同 OA + asOfDate 二次拍平 → 行数稳定，built_at 刷新")
  void testIdempotentRerun() {
    seedTop("T", "主制造", "2026-01-01", 0);
    seedChildAt("T", "C1", "/T/C1/", 1, 1, "1", "主制造", "2026-01-01",
        "C1_name", null, "采购件", 1);

    flattenService.flatten(req("T", "主制造", LocalDate.of(2026, 6, 1)));
    long firstCount = loadCostingByOa().size();
    LocalDateTime built1 = loadCostingByOa().get(0).getBuiltAt();

    try {
      Thread.sleep(1100);
    } catch (InterruptedException ignored) {
    }

    flattenService.flatten(req("T", "主制造", LocalDate.of(2026, 6, 1)));
    List<BomCostingRow> second = loadCostingByOa();
    assertThat(second.size()).as("重跑行数稳定").isEqualTo((int) firstCount);
    assertThat(second.get(0).getBuiltAt()).as("built_at 已刷新").isAfter(built1);
  }

  @Test
  @DisplayName("testInvalidRequest：asOfDate / oaNo / topProductCode 缺失 → IllegalArgumentException")
  void testInvalidRequest() {
    FlattenRequest r = new FlattenRequest();
    assertThatThrownBy(() -> flattenService.flatten(r))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("asOfDate");

    r.setAsOfDate(LocalDate.of(2026, 6, 1));
    assertThatThrownBy(() -> flattenService.flatten(r))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("oaNo");

    r.setOaNo(oaNo);
    assertThatThrownBy(() -> flattenService.flatten(r))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("topProductCode");
  }

  /**
   * testRealBomMaster1079Flatten：T3→T4→T5 全链路（导入 → 构建 → 拍平）的真实数据集成。
   *
   * <p>依赖 {@code ~/Desktop/BOMMaster20260423.xlsx}；Assumptions.assumeTrue 保护。
   */
  @Test
  @Tag("real-bom")
  @DisplayName("testRealBomMaster1079Flatten：真实 34 万行 → 构建 1079900000536 → 拍平 OA-GOLDEN-001")
  void testRealBomMaster1079Flatten() throws Exception {
    String defaultPath = System.getProperty("user.home") + "/Desktop/BOMMaster20260423.xlsx";
    Path realXlsx = Path.of(System.getProperty("bom.real.xlsx.path", defaultPath));
    Assumptions.assumeTrue(Files.exists(realXlsx), "真实 Excel 不存在，跳过: " + realXlsx);

    String goldenOa = "OA-GOLDEN-" + UUID.randomUUID().toString().substring(0, 8);
    try {
      // 1) 导入（T3）
      BomImportResult ir;
      try (InputStream in = Files.newInputStream(realXlsx)) {
        ir = bomImportService.importExcel(in, realXlsx.getFileName().toString(), "tester");
      }

      // 2) 构建 1079900000536 主制造（T4）
      BuildHierarchyRequest br = new BuildHierarchyRequest();
      br.setImportBatchId(ir.getImportBatchId());
      br.setBomPurpose("主制造");
      br.setMode("BY_PRODUCT");
      br.setTopProductCode("1079900000536");
      BuildHierarchyResult buildResult = buildService.build(br);
      assertThat(buildResult.getProductsProcessed()).isEqualTo(1);

      // 3) 拍平（T5）
      FlattenRequest fr = new FlattenRequest();
      fr.setOaNo(goldenOa);
      fr.setTopProductCode("1079900000536");
      fr.setBomPurpose("主制造");
      fr.setMode("BY_OA");
      fr.setAsOfDate(LocalDate.of(2026, 4, 23));
      long t0 = System.currentTimeMillis();
      FlattenResult fResult = flattenService.flatten(fr);
      long elapsedMs = System.currentTimeMillis() - t0;

      System.out.printf(
          "[REAL-BOM-FLATTEN] oa=%s written=%d subtreeRequired=%d warnings=%d elapsedMs=%d%n",
          goldenOa, fResult.getCostingRowsWritten(),
          fResult.getSubtreeRequiredCount(), fResult.getWarnings().size(), elapsedMs);

      assertThat(fResult.getCostingRowsWritten()).isGreaterThan(0);

      // 抽样校验：所有写入行 OA / top / asOfDate 冻结正确；is_costing_row=1
      List<BomCostingRow> rows =
          costingMapper.selectList(
              Wrappers.<BomCostingRow>lambdaQuery()
                  .eq(BomCostingRow::getOaNo, goldenOa));
      assertThat(rows).isNotEmpty();
      assertThat(rows).allSatisfy(c -> {
        assertThat(c.getTopProductCode()).isEqualTo("1079900000536");
        assertThat(c.getAsOfDate()).isEqualTo(LocalDate.of(2026, 4, 23));
        assertThat(c.getIsCostingRow()).isEqualTo(1);
        // 非顶层：path 必须以 /1079900000536/ 开头且以 /{materialCode}/ 结尾
        if (c.getLevel() != null && c.getLevel() > 0) {
          assertThat(c.getPath()).startsWith("/1079900000536/");
          assertThat(c.getPath()).endsWith("/" + c.getMaterialCode() + "/");
        }
      });
      // 若命中 V40 种子规则（NAME_LIKE 接管 / SHAPE_ATTR_EQ 部品联动），matched_drill_rule_id 非空
      long matched =
          rows.stream().filter(c -> c.getMatchedDrillRuleId() != null).count();
      System.out.printf("[REAL-BOM-FLATTEN] 命中规则的行数=%d%n", matched);
    } finally {
      // 清理：本次真实链路的 costing / raw / u9 行
      try (Connection conn = openConnection();
          Statement stmt = conn.createStatement()) {
        stmt.executeUpdate("DELETE FROM lp_bom_costing_row WHERE oa_no = '" + goldenOa + "'");
        stmt.executeUpdate(
            "DELETE FROM lp_bom_raw_hierarchy WHERE top_product_code = '1079900000536'");
        stmt.executeUpdate("DELETE FROM lp_bom_u9_source WHERE import_batch_id LIKE 'b_2026%'");
      }
    }
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

  /** 插入顶层 raw 行（level=0, path=/top/） */
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
    row.setSourceImportBatchId("rawtest_import_" + UUID.randomUUID().toString().substring(0, 4));
    row.setBuildBatchId("rawtest_" + UUID.randomUUID().toString().substring(0, 6));
    row.setBuiltAt(LocalDateTime.now());
    rawMapper.insert(row);
  }

  /** 插入子 raw 行（level=1 默认；path= /top/child/） */
  private void seedChild(
      String parent, String child, int sortSeq, String qty,
      String purpose, String effFrom, String name, String shapeAttr,
      String sourceCategory, int isLeaf) {
    seedChildAt(parent, child, "/" + parent + "/" + child + "/",
        /*level=*/1, sortSeq, qty, purpose, effFrom, name, shapeAttr, sourceCategory, isLeaf);
  }

  private void seedChildAt(
      String parent, String child, String path, int level, int sortSeq, String qty,
      String purpose, String effFrom, String name, String shapeAttr,
      String sourceCategory, int isLeaf) {
    BomRawHierarchy row = new BomRawHierarchy();
    // 从 path 里推顶层：/T/.../ → T
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
    row.setShapeAttr(shapeAttr);
    row.setSourceCategory(sourceCategory);
    row.setSourceImportBatchId("rawtest_import_" + UUID.randomUUID().toString().substring(0, 4));
    row.setBuildBatchId("rawtest_" + UUID.randomUUID().toString().substring(0, 6));
    row.setBuiltAt(LocalDateTime.now());
    rawMapper.insert(row);
  }

  /** 可控 effective_from/to 的 raw_hierarchy 插入，供版本切换测试用 */
  private void seedRawVersioned(
      String top, String parent, String material, String path, int level,
      String qtyPerParent, String qtyPerTop, String purpose,
      String effFrom, String effTo, String name, String shapeAttr,
      String sourceCategory, int isLeaf) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setTopProductCode(top);
    row.setParentCode(parent);
    row.setMaterialCode(material);
    row.setLevel(level);
    row.setPath(path);
    if (qtyPerParent != null) row.setQtyPerParent(new BigDecimal(qtyPerParent));
    row.setQtyPerTop(new BigDecimal(qtyPerTop));
    row.setBomPurpose(purpose);
    row.setEffectiveFrom(LocalDate.parse(effFrom));
    row.setEffectiveTo(LocalDate.parse(effTo));
    row.setSourceType("U9");
    row.setIsLeaf(isLeaf);
    row.setMaterialName(name);
    row.setShapeAttr(shapeAttr);
    row.setSourceCategory(sourceCategory);
    row.setSourceImportBatchId("rawtest_import_" + UUID.randomUUID().toString().substring(0, 4));
    row.setBuildBatchId("rawtest_" + UUID.randomUUID().toString().substring(0, 6));
    row.setBuiltAt(LocalDateTime.now());
    rawMapper.insert(row);
  }

  private void insertRule(
      String type, String value, String action, int markSubtree, int priority) {
    BomStopDrillRule rule = new BomStopDrillRule();
    rule.setMatchType(type);
    rule.setMatchValue(value);
    rule.setDrillAction(action);
    rule.setMarkSubtreeCostRequired(markSubtree);
    rule.setPriority(priority);
    rule.setEnabled(1);
    ruleMapper.insert(rule);
  }
}
