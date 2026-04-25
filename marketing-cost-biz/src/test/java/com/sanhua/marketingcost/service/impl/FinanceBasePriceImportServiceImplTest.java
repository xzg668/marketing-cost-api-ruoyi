package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.InfluenceFactorImportResponse;
import com.sanhua.marketingcost.dto.InfluenceFactorImportRow;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.formula.normalize.VariableAliasIndex;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * FinanceBasePriceImportServiceImpl 单测 —— T17 核心路径。
 *
 * <p>分两个维度覆盖：
 * <ol>
 *   <li>{@code importRows}：校验（空简称 / 价格 ≤0 / null 价格 → skipped）+ upsert（命中/未命中）+ batchId 贯穿</li>
 *   <li>{@code importExcel}：用 POI 构造一份 3 数据行 + 标题行 + 表头行 的 xlsx，验证端到端解析能进 DB</li>
 * </ol>
 *
 * <p>MP LambdaQuery 依赖 TableInfo 缓存，故 {@code @BeforeAll} 手工预热（非 Spring 启动）。
 */
class FinanceBasePriceImportServiceImplTest {

  private FinanceBasePriceMapper financeBasePriceMapper;
  private VariableAliasIndex variableAliasIndex;
  private FinanceBasePriceImportServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FinanceBasePrice.class);
  }

  @BeforeEach
  void setUp() {
    financeBasePriceMapper = mock(FinanceBasePriceMapper.class);
    variableAliasIndex = mock(VariableAliasIndex.class);
    service = new FinanceBasePriceImportServiceImpl(financeBasePriceMapper, variableAliasIndex);
  }

  @Test
  @DisplayName("importRows：priceMonth 空返 skipped=1，不碰 DB")
  void importRows_missingMonth_skipsAll() {
    InfluenceFactorImportResponse resp = service.importRows(
        List.of(row(1, "Cu", "电解铜", "出厂价", "90", "公斤")), null);

    assertThat(resp.getImported()).isZero();
    assertThat(resp.getSkipped()).isEqualTo(1);
    assertThat(resp.getBatchId()).isNotBlank();
    verify(financeBasePriceMapper, never()).insert(any(FinanceBasePrice.class));
    verify(financeBasePriceMapper, never()).updateById(any(FinanceBasePrice.class));
  }

  @Test
  @DisplayName("importRows：校验失败（空简称 / 价格≤0）逐行 skipped，其余正常 upsert")
  void importRows_validationFailures_skipRowKeepBatch() {
    // 让 selectOne 始终返 null → 走 insert 分支
    stubSelectOneReturnsNull();

    List<InfluenceFactorImportRow> rows = new ArrayList<>();
    rows.add(row(1, "Cu", "电解铜", "出厂价", "90", "公斤")); // ok
    rows.add(row(2, "", "空简称示例", "出厂价", "50", "公斤")); // shortName 空
    rows.add(row(3, "Zn", "电解锌", "出厂价", "0", "公斤")); // 价格 ≤0
    rows.add(row(4, "Ag", null, "出厂价", null, null)); // 价格 null
    rows.add(row(5, "Al", "电解铝", null, "17.5", null)); // priceSource 空 → 默认 "未指定"，应成功

    InfluenceFactorImportResponse resp = service.importRows(rows, "2026-02");

    assertThat(resp.getImported()).isEqualTo(2); // Cu + Al
    assertThat(resp.getSkipped()).isEqualTo(3);
    assertThat(resp.getErrors()).hasSize(3);
    assertThat(resp.getErrors().get(0).getMessage()).contains("简称");
    assertThat(resp.getErrors().get(1).getMessage()).contains("必须 > 0");
    assertThat(resp.getErrors().get(2).getMessage()).contains("价格为空");
    verify(financeBasePriceMapper, times(2)).insert(any(FinanceBasePrice.class));
    // Excel 行号 = offset(3) + idx
    assertThat(resp.getErrors().get(0).getRowNumber()).isEqualTo(4); // idx=1
    verify(variableAliasIndex, atLeastOnce()).refresh();
  }

  @Test
  @DisplayName("importRows：命中已存在行走 update，未命中走 insert；batchId 贯穿")
  void importRows_upsertHitsAndMisses() {
    FinanceBasePrice existing = new FinanceBasePrice();
    existing.setId(100L);
    existing.setPriceMonth("2026-02");
    existing.setShortName("Cu");
    existing.setPriceSource("出厂价");
    // importRows 按顺序处理：第 1 次查询是 Cu（命中），第 2 次是 Zn（未命中）
    when(financeBasePriceMapper.selectOne(any(Wrapper.class)))
        .thenReturn(existing)
        .thenReturn(null);

    List<InfluenceFactorImportRow> rows = List.of(
        row(1, "Cu", "电解铜1#", "出厂价", "91.2", "公斤"),
        row(2, "Zn", "电解锌", "出厂价", "22.5", "公斤"));

    InfluenceFactorImportResponse resp = service.importRows(rows, "2026-02");

    assertThat(resp.getImported()).isEqualTo(2);
    ArgumentCaptor<FinanceBasePrice> insertCap = ArgumentCaptor.forClass(FinanceBasePrice.class);
    verify(financeBasePriceMapper).insert(insertCap.capture());
    assertThat(insertCap.getValue().getShortName()).isEqualTo("Zn");
    assertThat(insertCap.getValue().getImportBatchId()).isEqualTo(resp.getBatchId());

    ArgumentCaptor<FinanceBasePrice> updateCap = ArgumentCaptor.forClass(FinanceBasePrice.class);
    verify(financeBasePriceMapper).updateById(updateCap.capture());
    assertThat(updateCap.getValue().getId()).isEqualTo(100L);
    assertThat(updateCap.getValue().getPrice()).isEqualByComparingTo("91.2");
    assertThat(updateCap.getValue().getImportBatchId()).isEqualTo(resp.getBatchId());
  }

  @Test
  @DisplayName("importExcel：真正用 POI 生成 xlsx（标题行+表头+3 数据行），能正确解析入库")
  void importExcel_endToEndWithPoiGeneratedXlsx() throws Exception {
    stubSelectOneReturnsNull();

    byte[] xlsx = buildMiniXlsx();
    InfluenceFactorImportResponse resp =
        service.importExcel(new ByteArrayInputStream(xlsx), "2026-02");

    assertThat(resp.getImported()).isEqualTo(3);
    assertThat(resp.getSkipped()).isZero();
    assertThat(resp.getBatchId()).isNotBlank();
    verify(financeBasePriceMapper, times(3)).insert(any(FinanceBasePrice.class));
    verify(variableAliasIndex).refresh();
  }

  @Test
  @DisplayName("importExcel：别名刷新抛异常（冲突）不影响本次导入成功")
  void importExcel_aliasRefreshFailure_stillReportsImported() {
    stubSelectOneReturnsNull();
    org.mockito.Mockito.doThrow(new IllegalStateException("别名冲突"))
        .when(variableAliasIndex).refresh();

    InfluenceFactorImportResponse resp = service.importRows(
        List.of(row(1, "Cu", "电解铜", "出厂价", "90", "公斤")), "2026-02");

    assertThat(resp.getImported()).isEqualTo(1);
    assertThat(resp.getSkipped()).isZero();
  }

  // ===================== helpers =====================

  @SuppressWarnings("unchecked")
  private void stubSelectOneReturnsNull() {
    when(financeBasePriceMapper.selectOne(any(Wrapper.class))).thenAnswer(inv -> {
      AbstractWrapper<?, ?, ?> w = (AbstractWrapper<?, ?, ?>) inv.getArgument(0);
      w.getSqlSegment();
      return null;
    });
  }

  private InfluenceFactorImportRow row(
      Integer seq, String shortName, String factorName,
      String priceSource, String price, String unit) {
    InfluenceFactorImportRow r = new InfluenceFactorImportRow();
    r.setSeq(seq);
    r.setShortName(shortName);
    r.setFactorName(factorName);
    r.setPriceSource(priceSource);
    r.setPrice(price == null ? null : new BigDecimal(price));
    r.setUnit(unit);
    return r;
  }

  /** 用 POI 直接拼一个与影响因素 10 真实 Excel 同样结构的 mini xlsx（1 标题 + 1 表头 + 3 数据）。 */
  private byte[] buildMiniXlsx() throws Exception {
    try (XSSFWorkbook wb = new XSSFWorkbook();
         ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = wb.createSheet("影响因素10");
      // 标题行（被 headRowNumber(2) 跳过）
      sheet.createRow(0).createCell(0).setCellValue("2026年2月参照基准");
      // 表头行：7 列顺序与 InfluenceFactorImportRow 的 @ExcelProperty 完全对齐
      Row header = sheet.createRow(1);
      String[] cols = {"序号", "价表影响因素名称", "简称", "取价来源", "价格", "单位", "价格-原价"};
      for (int i = 0; i < cols.length; i++) {
        header.createCell(i).setCellValue(cols[i]);
      }
      // 数据：Cu / Zn / Al 各一行
      writeDataRow(sheet, 2, 1, "电解铜1#", "Cu", "出厂价", 91.2, "公斤", 90.1);
      writeDataRow(sheet, 3, 2, "电解锌1#", "Zn", "出厂价", 22.5, "公斤", 22.0);
      writeDataRow(sheet, 4, 3, "电解铝A00", "Al", "出厂价", 17.5, "公斤", 17.3);
      wb.write(out);
      return out.toByteArray();
    }
  }

  private void writeDataRow(
      Sheet sheet, int rowIdx, int seq, String factorName, String shortName,
      String priceSource, double price, String unit, double priceOriginal) {
    Row r = sheet.createRow(rowIdx);
    r.createCell(0).setCellValue(seq);
    r.createCell(1).setCellValue(factorName);
    r.createCell(2).setCellValue(shortName);
    r.createCell(3).setCellValue(priceSource);
    r.createCell(4).setCellValue(price);
    r.createCell(5).setCellValue(unit);
    r.createCell(6).setCellValue(priceOriginal);
  }
}
