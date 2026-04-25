package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.BomHierarchyTreeDto;
import com.sanhua.marketingcost.dto.BuildHierarchyRequest;
import com.sanhua.marketingcost.dto.BuildHierarchyResult;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.BomHierarchyBuildService;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import com.sanhua.marketingcost.dto.BomImportResult;
import com.sanhua.marketingcost.service.BomImportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link U9SourceBuilder} 真实 DB 集成测试。
 *
 * <p>每个用例独立 importBatchId，{@code @AfterEach} 清掉两张表本次产生的行。
 * 核心验收项：{@link #testAppendOnlyPreservesHistoricVersion}。
 */
@Tag("integration")
@DisplayName("U9SourceBuilder · 阶段 B 构建 lp_bom_raw_hierarchy")
class U9SourceBuilderTest extends BomMapperTestBase {

  @Autowired private BomHierarchyBuildService buildService;
  @Autowired private BomU9SourceMapper u9Mapper;
  @Autowired private BomRawHierarchyMapper rawMapper;
  @Autowired private BomImportService bomImportService;

  private final String importBatchId = "b_test_" + UUID.randomUUID().toString().substring(0, 6);

  @AfterEach
  void cleanUp() throws Exception {
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      // 所有测试产生的 raw_hierarchy 都带 build_batch_id 以 'h_' 开头，直接硬清
      stmt.executeUpdate("DELETE FROM lp_bom_raw_hierarchy WHERE build_batch_id LIKE 'h_%'");
      // u9_source 按本测试类用的前缀批量清（包括 append-only 用例的 V2 批次）
      stmt.executeUpdate(
          "DELETE FROM lp_bom_u9_source WHERE import_batch_id LIKE 'b_test_%'");
    }
  }

  @Test
  @DisplayName("testSimpleThreeLevel + testLeafDetection：A→B(2) B→C(3) → 4 行，level/path/qty 正确")
  void testSimpleThreeLevel() {
    seedRow("A", "B", 1, "2.0", "主制造", "2026-01-01");
    seedRow("B", "C", 1, "3.0", "主制造", "2026-01-01");

    BuildHierarchyResult r = buildService.build(byProduct("A", "主制造"));

    assertThat(r.getProductsProcessed()).isEqualTo(1);
    assertThat(r.getFailedProducts()).isEmpty();

    List<BomRawHierarchy> rows =
        rawMapper.selectList(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getTopProductCode, "A")
                .eq(BomRawHierarchy::getBomPurpose, "主制造")
                .orderByAsc(BomRawHierarchy::getLevel));

    assertThat(rows).hasSize(3);
    BomRawHierarchy a = rows.get(0);
    BomRawHierarchy b = rows.get(1);
    BomRawHierarchy c = rows.get(2);
    assertThat(a.getLevel()).isZero();
    assertThat(a.getPath()).isEqualTo("/A/");
    assertThat(a.getQtyPerTop()).isEqualByComparingTo("1");
    assertThat(a.getIsLeaf()).isEqualTo(0);
    assertThat(b.getLevel()).isEqualTo(1);
    assertThat(b.getPath()).isEqualTo("/A/B/");
    assertThat(b.getQtyPerTop()).isEqualByComparingTo("2");
    assertThat(b.getIsLeaf()).isEqualTo(0);
    assertThat(c.getLevel()).isEqualTo(2);
    assertThat(c.getPath()).isEqualTo("/A/B/C/");
    assertThat(c.getQtyPerTop()).isEqualByComparingTo("6"); // 2 * 3
    assertThat(c.getIsLeaf()).isEqualTo(1);
  }

  @Test
  @DisplayName("testCycleDetection：A→B B→A → failedProducts 含 A，无行写入")
  void testCycleDetection() {
    seedRow("A", "B", 1, "1", "主制造", "2026-01-01");
    seedRow("B", "A", 1, "1", "主制造", "2026-01-01");

    // 本数据里 A 和 B 互为父子，parents = children = {A, B}，没有 "顶层"
    // buildAll 会跳过它（topCandidates 为空），不报错但也不做事
    BuildHierarchyResult r1 = buildService.build(all("主制造"));
    assertThat(r1.getProductsProcessed()).isZero();
    assertThat(r1.getRowsWritten()).isZero();

    // 把 A 作为顶层强行传 —— 构造 top：让 A 不作为任何行的 child
    // 先清掉 B→A，只留 A→B；再加一行 B→A 会让 A 变非顶层。因此改用明确"有顶层的环"场景
    seedRow("X", "A", 1, "1", "主制造", "2026-01-01"); // X → A
    // 现在 X 是顶层（X 不作为 child）；A 作为 X 的子件，又有 A→B, B→A 的环
    BuildHierarchyResult r2 = buildService.build(byProduct("X", "主制造"));
    assertThat(r2.getFailedProducts()).as("环导致 X 进 failedProducts").containsExactly("X");
    assertThat(r2.getProductsProcessed()).isZero();
  }

  @Test
  @DisplayName("testMultipleBomPurpose：同产品 3 个 purpose → 3 组独立 raw_hierarchy 行")
  void testMultipleBomPurpose() {
    for (String purpose : List.of("普机", "主制造", "精益")) {
      seedRow("P", "C1", 1, "1", purpose, "2026-01-01");
      seedRow("P", "C2", 2, "2", purpose, "2026-01-01");
    }

    BuildHierarchyResult r = buildService.build(all(null)); // 所有 purpose

    assertThat(r.getProductsProcessed()).isEqualTo(1); // 一个顶层 P
    // 每个 purpose：1 顶层 + 2 子件 = 3 行；共 9 行
    assertThat(r.getRowsWritten()).isEqualTo(9);
    List<BomRawHierarchy> rows =
        rawMapper.selectList(
            Wrappers.<BomRawHierarchy>lambdaQuery().eq(BomRawHierarchy::getTopProductCode, "P"));
    assertThat(rows).hasSize(9);
    assertThat(
            rows.stream().map(BomRawHierarchy::getBomPurpose).distinct().sorted().toList())
        .containsExactly("主制造", "普机", "精益");
  }

  @Test
  @DisplayName("testQtyPerTopMultiplication：BigDecimal 精确乘法 2.5 * 0.4 = 1.0（非 double 浮点误差）")
  void testQtyPerTopMultiplication() {
    seedRow("ROOT", "MID", 1, "2.5", "主制造", "2026-01-01");
    seedRow("MID", "LEAF", 1, "0.4", "主制造", "2026-01-01");

    buildService.build(byProduct("ROOT", "主制造"));

    BomRawHierarchy leaf =
        rawMapper.selectOne(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getTopProductCode, "ROOT")
                .eq(BomRawHierarchy::getMaterialCode, "LEAF"));
    assertThat(leaf.getQtyPerTop()).isEqualByComparingTo("1.00"); // 2.5 * 0.4 = 1.0 严格
  }

  @Test
  @DisplayName("testAppendOnlyPreservesHistoricVersion：两个 effective_from 版本并存，老版本不删")
  void testAppendOnlyPreservesHistoricVersion() {
    // V1：单独一次导入，2026-01-01 生效，X 有 3 个子件
    String batchV1 = "b_test_v1_" + UUID.randomUUID().toString().substring(0, 6);
    seedRowInto(batchV1, "X", "C1", 1, "1", "主制造", "2026-01-01");
    seedRowInto(batchV1, "X", "C2", 2, "1", "主制造", "2026-01-01");
    seedRowInto(batchV1, "X", "C3", 3, "1", "主制造", "2026-01-01");

    BuildHierarchyRequest reqV1 = new BuildHierarchyRequest();
    reqV1.setImportBatchId(batchV1);
    reqV1.setBomPurpose("主制造");
    reqV1.setMode("BY_PRODUCT");
    reqV1.setTopProductCode("X");
    buildService.build(reqV1);

    long v1Count =
        rawMapper.selectCount(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getTopProductCode, "X")
                .eq(BomRawHierarchy::getEffectiveFrom, LocalDate.of(2026, 1, 1)));
    assertThat(v1Count).as("V1 含顶层=1 + 3 子 = 4 行").isEqualTo(4);

    // V2：另一次导入，2026-05-01 生效，X 改款到 4 个子件
    String batchV2 = "b_test_v2_" + UUID.randomUUID().toString().substring(0, 6);
    seedRowInto(batchV2, "X", "C1", 1, "1", "主制造", "2026-05-01");
    seedRowInto(batchV2, "X", "C2", 2, "1", "主制造", "2026-05-01");
    seedRowInto(batchV2, "X", "C3", 3, "1", "主制造", "2026-05-01");
    seedRowInto(batchV2, "X", "C4", 4, "1", "主制造", "2026-05-01");

    BuildHierarchyRequest reqV2 = new BuildHierarchyRequest();
    reqV2.setImportBatchId(batchV2);
    reqV2.setBomPurpose("主制造");
    reqV2.setMode("BY_PRODUCT");
    reqV2.setTopProductCode("X");
    buildService.build(reqV2);

    long v1AfterCount =
        rawMapper.selectCount(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getTopProductCode, "X")
                .eq(BomRawHierarchy::getEffectiveFrom, LocalDate.of(2026, 1, 1)));
    long v2Count =
        rawMapper.selectCount(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getTopProductCode, "X")
                .eq(BomRawHierarchy::getEffectiveFrom, LocalDate.of(2026, 5, 1)));

    assertThat(v1AfterCount).as("V1 绝不能被删").isEqualTo(4);
    assertThat(v2Count).as("V2 含顶层=1 + 4 子 = 5 行").isEqualTo(5);
  }

  @Test
  @DisplayName("testRerunSameBatchIsIdempotent：同批次重跑行数不变，build_batch_id/built_at 已刷新")
  void testRerunSameBatchIsIdempotent() {
    seedRow("R", "R1", 1, "1", "主制造", "2026-01-01");
    seedRow("R", "R2", 2, "1", "主制造", "2026-01-01");

    BuildHierarchyResult r1 = buildService.build(byProduct("R", "主制造"));
    long firstRunCount =
        rawMapper.selectCount(
            Wrappers.<BomRawHierarchy>lambdaQuery().eq(BomRawHierarchy::getTopProductCode, "R"));
    BomRawHierarchy beforeRerun =
        rawMapper.selectOne(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getTopProductCode, "R")
                .eq(BomRawHierarchy::getMaterialCode, "R1"));

    // 等 1 秒以便 built_at 能观察到变化（DATETIME 秒级精度）
    try {
      Thread.sleep(1100);
    } catch (InterruptedException ignore) {
    }
    BuildHierarchyResult r2 = buildService.build(byProduct("R", "主制造"));

    long secondRunCount =
        rawMapper.selectCount(
            Wrappers.<BomRawHierarchy>lambdaQuery().eq(BomRawHierarchy::getTopProductCode, "R"));
    BomRawHierarchy afterRerun =
        rawMapper.selectOne(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getTopProductCode, "R")
                .eq(BomRawHierarchy::getMaterialCode, "R1"));

    assertThat(secondRunCount).as("重跑行数不应变化（ON DUPLICATE KEY UPDATE 命中）")
        .isEqualTo(firstRunCount);
    assertThat(afterRerun.getBuildBatchId()).isNotEqualTo(beforeRerun.getBuildBatchId());
    assertThat(afterRerun.getBuiltAt()).isAfter(beforeRerun.getBuiltAt());
    assertThat(r2.getBuildBatchId()).isNotEqualTo(r1.getBuildBatchId());
  }

  @Test
  @DisplayName("testGetHierarchyTree：嵌套树结构正确，顶层为根")
  void testGetHierarchyTree() {
    seedRow("T", "T1", 1, "1", "主制造", "2026-01-01");
    seedRow("T", "T2", 2, "1", "主制造", "2026-01-01");
    seedRow("T1", "T1A", 1, "1", "主制造", "2026-01-01");
    buildService.build(byProduct("T", "主制造"));

    BomHierarchyTreeDto tree =
        buildService.getHierarchyTree("T", "主制造", LocalDate.of(2026, 6, 1), "U9");

    assertThat(tree).isNotNull();
    assertThat(tree.getMaterialCode()).isEqualTo("T");
    assertThat(tree.getChildren()).hasSize(2);
    BomHierarchyTreeDto t1 = tree.getChildren().stream()
        .filter(c -> "T1".equals(c.getMaterialCode())).findFirst().orElseThrow();
    assertThat(t1.getChildren()).hasSize(1);
    assertThat(t1.getChildren().get(0).getMaterialCode()).isEqualTo("T1A");
  }

  @Test
  @DisplayName("build：importBatchId 空或 BY_PRODUCT 缺 top → IllegalArgumentException")
  void testInvalidRequest() {
    assertThatThrownBy(() -> buildService.build(new BuildHierarchyRequest()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("importBatchId");
    BuildHierarchyRequest req = new BuildHierarchyRequest();
    req.setImportBatchId("x");
    req.setMode("BY_PRODUCT");
    assertThatThrownBy(() -> buildService.build(req))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("topProductCode");
  }

  /**
   * testRealBomMaster1079：真实 Excel + 构建 1079900000536（阀 SHF-AA-79）的全链路集成。
   *
   * <p>先跑 T3 的 importExcel 导入真实 34 万行，再跑 T4 的 build-hierarchy 按产品构建；
   * 最后抽样 5 节点验证 path/level/qty_per_top 正确、hierarchy 树能返回。
   * Assumptions.assumeTrue 控制：文件存在才跑，避免 CI / 其他人机器被强制。
   */
  @Test
  @Tag("real-bom")
  @DisplayName("testRealBomMaster1079：真实 34 万行 → 构建顶层 1079900000536 → 抽样验证")
  void testRealBomMaster1079() throws Exception {
    String defaultPath = System.getProperty("user.home") + "/Desktop/BOMMaster20260423.xlsx";
    Path realXlsx = Path.of(System.getProperty("bom.real.xlsx.path", defaultPath));
    Assumptions.assumeTrue(Files.exists(realXlsx), "真实 Excel 不存在，跳过: " + realXlsx);

    // 1) 先导入（T3 链路）
    BomImportResult importResult;
    try (InputStream in = Files.newInputStream(realXlsx)) {
      importResult = bomImportService.importExcel(in, realXlsx.getFileName().toString(), "tester");
    }
    assertThat(importResult.getSuccessRows()).isGreaterThan(340_000);

    // 2) 构建 1079900000536（T4 链路），按主制造
    BuildHierarchyRequest req = new BuildHierarchyRequest();
    req.setImportBatchId(importResult.getImportBatchId());
    req.setBomPurpose("主制造");
    req.setMode("BY_PRODUCT");
    req.setTopProductCode("1079900000536");
    long t0 = System.currentTimeMillis();
    BuildHierarchyResult buildResult = buildService.build(req);
    long elapsedMs = System.currentTimeMillis() - t0;

    System.out.printf(
        "[REAL-BOM-BUILD] buildBatch=%s products=%d rowsWritten=%d failed=%d elapsedMs=%d%n",
        buildResult.getBuildBatchId(),
        buildResult.getProductsProcessed(),
        buildResult.getRowsWritten(),
        buildResult.getFailedProducts().size(),
        elapsedMs);

    assertThat(buildResult.getProductsProcessed()).isEqualTo(1);
    assertThat(buildResult.getFailedProducts()).isEmpty();
    assertThat(buildResult.getRowsWritten()).as("至少含顶层 + 直接子件").isGreaterThan(1);

    // 3) 抽样校验：所有行 path 以 /1079900000536/ 开头、顶层 level=0、path ends with material_code/
    List<BomRawHierarchy> rows =
        rawMapper.selectList(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getTopProductCode, "1079900000536")
                .eq(BomRawHierarchy::getBomPurpose, "主制造")
                .eq(BomRawHierarchy::getBuildBatchId, buildResult.getBuildBatchId()));
    assertThat(rows).isNotEmpty();
    BomRawHierarchy top = rows.stream().filter(r -> r.getLevel() == 0).findFirst().orElseThrow();
    assertThat(top.getMaterialCode()).isEqualTo("1079900000536");
    assertThat(top.getPath()).isEqualTo("/1079900000536/");
    assertThat(top.getQtyPerTop()).isEqualByComparingTo("1");

    // 所有非顶层 path 必须以 /1079900000536/ 开头且以 materialCode/ 结尾
    rows.stream().filter(r -> r.getLevel() > 0).forEach(r -> {
      assertThat(r.getPath())
          .as("path 应以顶层料号开头: " + r.getPath())
          .startsWith("/1079900000536/");
      assertThat(r.getPath()).endsWith("/" + r.getMaterialCode() + "/");
    });

    // 4) 树查询接口也能拿到非空结果
    BomHierarchyTreeDto tree =
        buildService.getHierarchyTree("1079900000536", "主制造", LocalDate.of(2026, 6, 1), "U9");
    assertThat(tree).isNotNull();
    assertThat(tree.getMaterialCode()).isEqualTo("1079900000536");
    assertThat(tree.getChildren()).isNotEmpty();

    // 清理（此用例在 AfterEach 之外建了外部批次，手工清一下便于重跑）
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(
          "DELETE FROM lp_bom_raw_hierarchy WHERE build_batch_id = '"
              + buildResult.getBuildBatchId() + "'");
      stmt.executeUpdate(
          "DELETE FROM lp_bom_u9_source WHERE import_batch_id = '"
              + importResult.getImportBatchId() + "'");
    }
  }

  // ============================ 辅助 ============================

  private BuildHierarchyRequest all(String purpose) {
    BuildHierarchyRequest req = new BuildHierarchyRequest();
    req.setImportBatchId(importBatchId);
    req.setBomPurpose(purpose);
    req.setMode("ALL");
    return req;
  }

  private BuildHierarchyRequest byProduct(String top, String purpose) {
    BuildHierarchyRequest req = new BuildHierarchyRequest();
    req.setImportBatchId(importBatchId);
    req.setBomPurpose(purpose);
    req.setMode("BY_PRODUCT");
    req.setTopProductCode(top);
    return req;
  }

  /** 插入一行 u9_source 到当前测试的默认批次 */
  private void seedRow(
      String parent, String child, int seq, String qty, String purpose, String effFrom) {
    seedRowInto(importBatchId, parent, child, seq, qty, purpose, effFrom);
  }

  /** 插入一行 u9_source 到指定批次（append-only 测试用，模拟多次独立导入） */
  private void seedRowInto(
      String batch, String parent, String child, int seq, String qty, String purpose, String effFrom) {
    BomU9Source row = new BomU9Source();
    row.setImportBatchId(batch);
    row.setSourceType("EXCEL");
    row.setImportedAt(LocalDateTime.now());
    row.setParentMaterialNo(parent);
    row.setChildMaterialNo(child);
    row.setChildSeq(seq);
    row.setQtyPerParent(new BigDecimal(qty));
    row.setBomPurpose(purpose);
    row.setEffectiveFrom(LocalDate.parse(effFrom));
    row.setEffectiveTo(LocalDate.of(9999, 12, 31));
    row.setChildMaterialName(child + "_name");
    u9Mapper.insert(row);
  }
}
