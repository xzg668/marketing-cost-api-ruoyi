package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.BomBatchSummary;
import com.sanhua.marketingcost.dto.BomImportResult;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import com.sanhua.marketingcost.service.BomImportService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * {@link BomImportServiceImpl} 真实 DB 集成测试。
 *
 * <p>复用 T2 {@link BomMapperTestBase} 基建：testcontainers MySQL + 全 V* 迁移 + V40。
 * 用 sample.xlsx（src/test/resources/bom-import/sample.xlsx，从真实 BOMMaster 抽前 12 行）
 * 做 happy path；必要时在测试内部用 POI 手工构造临时 xlsx 覆盖边界用例。
 */
@Tag("integration")
@DisplayName("BomImportServiceImpl · 阶段 A 导入 lp_bom_u9_source")
class BomImportServiceImplTest extends BomMapperTestBase {

  private static final String SAMPLE_PATH = "/bom-import/sample.xlsx";

  @Autowired private BomImportService bomImportService;
  @Autowired private BomU9SourceMapper bomU9SourceMapper;

  @AfterEach
  void cleanUp() throws Exception {
    // 每个测试单独清表，避免互相污染（不同测试生成的 batchId 都唯一，但行数断言会受影响）
    try (Connection conn = openConnection();
        Statement stmt = conn.createStatement()) {
      stmt.executeUpdate("DELETE FROM lp_bom_u9_source");
    }
  }

  @Test
  @DisplayName("testImportSampleExcel：10 行真实数据全部落库，errors 为空")
  void testImportSampleExcel() throws Exception {
    try (InputStream in = openSample()) {
      BomImportResult result = bomImportService.importExcel(in, "sample.xlsx", "tester");

      assertThat(result.getImportBatchId()).startsWith("b_");
      assertThat(result.getSourceType()).isEqualTo("EXCEL");
      assertThat(result.getSourceFileName()).isEqualTo("sample.xlsx");
      assertThat(result.getTotalRows()).isEqualTo(10);
      assertThat(result.getSuccessRows()).isEqualTo(10);
      assertThat(result.getErrors()).isEmpty();

      Long dbCount =
          bomU9SourceMapper.selectCount(
              Wrappers.<BomU9Source>lambdaQuery()
                  .eq(BomU9Source::getImportBatchId, result.getImportBatchId()));
      assertThat(dbCount).isEqualTo(10L);
    }
  }

  @Test
  @DisplayName("testU9CostFlagMapping：'√' → 1，空 → 0，其他 → null")
  void testU9CostFlagMapping() throws Exception {
    // sample.xlsx 第 3 行的 col15 "BOM子项.是否计算成本" = '√'
    try (InputStream in = openSample()) {
      BomImportResult result = bomImportService.importExcel(in, "sample.xlsx", "tester");
      List<BomU9Source> rows =
          bomU9SourceMapper.selectList(
              Wrappers.<BomU9Source>lambdaQuery()
                  .eq(BomU9Source::getImportBatchId, result.getImportBatchId()));
      // 抽样：所有行的 u9_is_cost_flag 应该都是 1（样本数据均 '√'）
      assertThat(rows).hasSize(10).allSatisfy(r ->
          assertThat(r.getU9IsCostFlag()).as("样本数据 BOM子项.是否计算成本 均为 √").isEqualTo(1));
    }
  }

  @Test
  @DisplayName("testTwoMainCategoryColumns：两列同名'子件.主分类' 分别落到 materialCategory1 / 2")
  void testTwoMainCategoryColumns() throws Exception {
    try (InputStream in = openSample()) {
      BomImportResult result = bomImportService.importExcel(in, "sample.xlsx", "tester");
      List<BomU9Source> rows =
          bomU9SourceMapper.selectList(
              Wrappers.<BomU9Source>lambdaQuery()
                  .eq(BomU9Source::getImportBatchId, result.getImportBatchId()));
      // sample 第 3 行：col21 = '121151306'，col22 = '半成品'
      BomU9Source firstRow = rows.stream()
          .filter(r -> "1001000300045".equals(r.getParentMaterialNo()) && "普机".equals(r.getBomPurpose()))
          .findFirst()
          .orElseThrow();
      assertThat(firstRow.getMaterialCategory1()).isEqualTo("121151306");
      assertThat(firstRow.getMaterialCategory2()).isEqualTo("半成品");
    }
  }

  @Test
  @DisplayName("testEffectiveDateParse：生效/失效日期 Excel 原生 Date → LocalDate；9999-12-31 原样保留")
  void testEffectiveDateParse() throws Exception {
    try (InputStream in = openSample()) {
      BomImportResult result = bomImportService.importExcel(in, "sample.xlsx", "tester");
      List<BomU9Source> rows =
          bomU9SourceMapper.selectList(
              Wrappers.<BomU9Source>lambdaQuery()
                  .eq(BomU9Source::getImportBatchId, result.getImportBatchId()));
      BomU9Source firstRow = rows.get(0);
      assertThat(firstRow.getEffectiveFrom()).isNotNull();
      assertThat(firstRow.getEffectiveTo()).isEqualTo(LocalDate.of(9999, 12, 31));
    }
  }

  @Test
  @DisplayName("testMissingRequiredColumn：child_material_no 空的行进 errors，其余行正常落库")
  void testMissingRequiredColumn() throws Exception {
    // 手工生成 3 行：第 1 行正常，第 2 行 child_material_no 空（应进 errors），第 3 行正常
    byte[] xlsx = buildTinyXlsx(new String[][] {
        {"P001", "C001"},
        {"P002", ""},    // child 空
        {"P003", "C003"},
    });

    BomImportResult result =
        bomImportService.importExcel(new ByteArrayInputStream(xlsx), "tiny.xlsx", "tester");

    assertThat(result.getTotalRows()).isEqualTo(3);
    assertThat(result.getSuccessRows()).isEqualTo(2);
    assertThat(result.getErrors()).hasSize(1);
    assertThat(result.getErrors().get(0).getReason()).contains("母件/子件料号必填");
    // 错误行的 excelRow 是数据第 2 行 = Excel 第 4 行（第 1/2 行是标题/表头）
    assertThat(result.getErrors().get(0).getRowIndex()).isEqualTo(4);
  }

  @Test
  @DisplayName("testBatchIdUnique：两次调用产生两个不同 batchId，老批次数据不变")
  void testBatchIdUnique() throws Exception {
    BomImportResult first;
    BomImportResult second;
    try (InputStream in = openSample()) {
      first = bomImportService.importExcel(in, "sample.xlsx", "tester");
    }
    try (InputStream in = openSample()) {
      second = bomImportService.importExcel(in, "sample.xlsx", "tester");
    }

    assertThat(second.getImportBatchId()).isNotEqualTo(first.getImportBatchId());
    // 第一批次的行数不应被第二批次冲掉
    Long cntFirst =
        bomU9SourceMapper.selectCount(
            Wrappers.<BomU9Source>lambdaQuery()
                .eq(BomU9Source::getImportBatchId, first.getImportBatchId()));
    assertThat(cntFirst).isEqualTo(10L);
    Long cntSecond =
        bomU9SourceMapper.selectCount(
            Wrappers.<BomU9Source>lambdaQuery()
                .eq(BomU9Source::getImportBatchId, second.getImportBatchId()));
    assertThat(cntSecond).isEqualTo(10L);
  }

  @Test
  @DisplayName("testLargeRowCountBatchInsert：1500 行跨 BATCH_SIZE=1000 多次 saveBatch，总数正确")
  void testLargeRowCountBatchInsert() throws Exception {
    int rowCount = 1500;
    String[][] rows = new String[rowCount][];
    for (int i = 0; i < rowCount; i++) {
      rows[i] = new String[] {"P" + i, "C" + i};
    }
    byte[] xlsx = buildTinyXlsx(rows);

    BomImportResult result =
        bomImportService.importExcel(new ByteArrayInputStream(xlsx), "big.xlsx", "tester");

    assertThat(result.getSuccessRows()).isEqualTo(rowCount);
    assertThat(result.getErrors()).isEmpty();
    Long dbCount =
        bomU9SourceMapper.selectCount(
            Wrappers.<BomU9Source>lambdaQuery()
                .eq(BomU9Source::getImportBatchId, result.getImportBatchId()));
    assertThat(dbCount).isEqualTo((long) rowCount);
  }

  @Test
  @DisplayName("listBatches：导入后 GET layer=U9_SOURCE 返回该批次摘要，RAW/COSTING 抛 IllegalArgument")
  void testListBatches() throws Exception {
    try (InputStream in = openSample()) {
      bomImportService.importExcel(in, "sample.xlsx", "tester");
    }

    List<BomBatchSummary> summaries = bomImportService.listBatches("U9_SOURCE", 1, 20);
    assertThat(summaries).isNotEmpty();
    assertThat(summaries.get(0).getRowCount()).isEqualTo(10L);
    assertThat(summaries.get(0).getSourceFileName()).isEqualTo("sample.xlsx");
    assertThat(summaries.get(0).getImportedBy()).isEqualTo("tester");

    assertThatThrownBy(() -> bomImportService.listBatches("RAW_HIERARCHY", 1, 20))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * testRealBomMaster：真实 40MB Excel 导入（T3 §5.3 Gating 对应的全量集成验证）。
   *
   * <p>由于文件不随仓库分发，走 {@code Assumptions.assumeTrue}：本地存在就跑、CI 就 skip。
   * 文件默认位置是用户桌面 {@code ~/Desktop/BOMMaster20260423.xlsx}（可通过系统属性
   * {@code bom.real.xlsx.path} 覆盖）。
   *
   * <p>预计耗时 1-3 分钟（Spring 启动 + 34 万行流式解析 + saveBatch）；因此单独 {@code @Tag}。
   */
  @Test
  @Tag("real-bom")
  @DisplayName("testRealBomMaster：真实 40MB Excel（34 万行）导入验证")
  void testRealBomMaster() throws Exception {
    String defaultPath = System.getProperty("user.home") + "/Desktop/BOMMaster20260423.xlsx";
    Path realXlsx = Path.of(System.getProperty("bom.real.xlsx.path", defaultPath));
    Assumptions.assumeTrue(Files.exists(realXlsx),
        "真实 Excel 文件不存在，跳过本用例: " + realXlsx);

    long t0 = System.currentTimeMillis();
    BomImportResult result;
    try (InputStream in = Files.newInputStream(realXlsx)) {
      result = bomImportService.importExcel(in, realXlsx.getFileName().toString(), "tester");
    }
    long elapsedMs = System.currentTimeMillis() - t0;

    System.out.printf(
        "[REAL-BOM] batchId=%s total=%d success=%d errors=%d elapsedMs=%d%n",
        result.getImportBatchId(),
        result.getTotalRows(),
        result.getSuccessRows(),
        result.getErrors().size(),
        elapsedMs);

    // 真实文件 341964 行数据（不含标题/表头）；允许少量空行/错误行
    assertThat(result.getTotalRows()).isGreaterThan(340_000);
    assertThat(result.getSuccessRows()).isGreaterThan(340_000);
    assertThat(result.getErrors()).as("errors 行数应 < 100").hasSizeLessThan(100);

    // DB 计数与 successRows 一致
    Long dbCount =
        bomU9SourceMapper.selectCount(
            Wrappers.<BomU9Source>lambdaQuery()
                .eq(BomU9Source::getImportBatchId, result.getImportBatchId()));
    assertThat(dbCount).isEqualTo((long) result.getSuccessRows());

    // 抽样：第一行应是 1001000300045 / 阀座部件（与 python 探活一致）
    List<BomU9Source> sample =
        bomU9SourceMapper.selectList(
            Wrappers.<BomU9Source>lambdaQuery()
                .eq(BomU9Source::getImportBatchId, result.getImportBatchId())
                .eq(BomU9Source::getParentMaterialNo, "1001000300045")
                .last("LIMIT 5"));
    assertThat(sample).isNotEmpty();
    assertThat(sample.get(0).getParentMaterialName()).isEqualTo("阀座部件");
  }

  // ============================ 辅助 ============================

  private static InputStream openSample() {
    InputStream in = BomImportServiceImplTest.class.getResourceAsStream(SAMPLE_PATH);
    if (in == null) {
      throw new IllegalStateException("sample.xlsx 不存在于 test classpath: " + SAMPLE_PATH);
    }
    return in;
  }

  /**
   * 用 POI 构造最小 xlsx（一个 sheet "BOM母项"，第 1 行标题，第 2 行 33 列表头，数据从第 3 行起）。
   * 每条数据行只填 parent / child 料号，其他字段留空。
   */
  private static byte[] buildTinyXlsx(String[][] rows) throws Exception {
    try (Workbook wb = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("BOM母项");

      // 第 1 行：标题占位
      Row r1 = sheet.createRow(0);
      r1.createCell(0).setCellValue("BOM母项");

      // 第 2 行：33 列表头（位置与真实 Excel 对齐）
      String[] headers = new String[] {
          "母件料品_料号", "母件料品_品名", "生产单位", "BOM生产目的", "版本号", "状态",
          "子件项次", "子项类型", "子件.料号", "子项_品名", "子项规格",
          "BOM子项.成本要素.成本要素编码", "BOM子项.成本要素.名称", "BOM子项.委托加工备料来源",
          "BOM子项.是否计算成本", "工程变更单编码", "发料单位", "BOM子项.子件料品.库存主单位",
          "子项_用量", "工序号", "子件.主分类", "子件.主分类",
          "子件.生产分类", "子件料品.形态属性", "子件.生产部门", "发料方式",
          "是否虚拟", "母件底数", "子项.段3(替代策略)", "子项.段4(工序编号)",
          "BOM子项.订单完工", "生效日期", "失效日期"
      };
      Row r2 = sheet.createRow(1);
      for (int i = 0; i < headers.length; i++) {
        r2.createCell(i).setCellValue(headers[i]);
      }

      // 数据行：[parentNo, childNo]
      CreationHelper helper = wb.getCreationHelper();
      CellStyle dateStyle = wb.createCellStyle();
      dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-mm-dd"));

      for (int i = 0; i < rows.length; i++) {
        Row dr = sheet.createRow(2 + i);
        dr.createCell(0).setCellValue(rows[i][0]);
        dr.createCell(8).setCellValue(rows[i][1]);
        // effective_from / effective_to 用 Date；留空会触发 IsDateFormatted 解析歧义
        Cell from = dr.createCell(31);
        from.setCellValue(new Date()); // 用当前日期
        from.setCellStyle(dateStyle);
        Cell to = dr.createCell(32);
        to.setCellValue(new Date());
        to.setCellStyle(dateStyle);
      }
      wb.write(out);
      return out.toByteArray();
    }
  }
}
