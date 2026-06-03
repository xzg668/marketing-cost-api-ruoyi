package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioExcelRow;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioImportRow;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioImportResponse;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.enums.SupplierSupplyRatioSourceType;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SupplierSupplyRatioImportServiceImplTest {
  private static final Path REAL_SAMPLE =
      Path.of("/Users/xiexicheng/Documents/sales_cost/3 产品成本计算表（3.29- 提供）5.15改.xls");

  private SupplierSupplyRatioMapper mapper;
  private SupplierSupplyRatioImportServiceImpl service;

  @BeforeEach
  void setUp() {
    mapper = mock(SupplierSupplyRatioMapper.class);
    service = new SupplierSupplyRatioImportServiceImpl(
        mapper, new SupplierSupplyRatioWorkbookParserImpl());
  }

  @Test
  @DisplayName("导入新增：有效行按 Excel 来源和批次号入库")
  void importRowsInsertsNewRows() {
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);

    SupplierSupplyRatioImportResponse response =
        service.importRows(List.of(row("203240251", "小阀座", "SHF-01", "供应商A", "0.6")),
            "ratio.xls", "COMMERCIAL", "alice");

    assertThat(response.getInsertedRows()).isEqualTo(1);
    assertThat(response.getUpdatedRows()).isZero();
    assertThat(response.getBatchNo()).startsWith("SSR-");

    ArgumentCaptor<SupplierSupplyRatio> captor = ArgumentCaptor.forClass(SupplierSupplyRatio.class);
    verify(mapper).insert(captor.capture());
    SupplierSupplyRatio inserted = captor.getValue();
    assertThat(inserted.getSourceType()).isEqualTo("EXCEL");
    assertThat(inserted.getSourceBatchNo()).isEqualTo(response.getBatchNo());
    assertThat(inserted.getImportFileName()).isEqualTo("ratio.xls");
    assertThat(inserted.getImportedBy()).isEqualTo("alice");
    assertThat(inserted.getBusinessUnitType()).isEqualTo("COMMERCIAL");
  }

  @Test
  @DisplayName("重复导入同一业务键：第二次更新已有行，不新增重复行")
  void repeatedImportUpdatesExistingRow() {
    SupplierSupplyRatio existing = new SupplierSupplyRatio();
    existing.setId(99L);
    existing.setDeleted(0);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null, existing);

    SupplierSupplyRatioExcelRow row = row("203240251", "小阀座", "SHF-01", "供应商A", "0.6");
    SupplierSupplyRatioImportResponse first =
        service.importRows(List.of(row), "ratio.xls", "COMMERCIAL", "alice");
    SupplierSupplyRatioImportResponse second =
        service.importRows(List.of(row), "ratio.xls", "COMMERCIAL", "bob");

    assertThat(first.getInsertedRows()).isEqualTo(1);
    assertThat(second.getInsertedRows()).isZero();
    assertThat(second.getUpdatedRows()).isEqualTo(1);
    verify(mapper).insert(any(SupplierSupplyRatio.class));
    verify(mapper).updateById(existing);
    assertThat(existing.getUpdatedBy()).isEqualTo("bob");
  }

  @Test
  @DisplayName("SRM 同步复用 upsert：同键更新已有行，不新增重复行")
  void srmUpsertUpdatesExistingRowWithSameBusinessKey() {
    SupplierSupplyRatio existing = new SupplierSupplyRatio();
    existing.setId(120L);
    existing.setDeleted(0);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(existing);

    SupplierSupplyRatioImportResponse response =
        service.upsertFromRows(
            List.of(importRow("203240251", "小阀座", "SHF-01", "供应商A", "0.75")),
            SupplierSupplyRatioSourceType.SRM,
            "SRM-20260518-001",
            null,
            "COMMERCIAL",
            "srm-job");

    assertThat(response.getInsertedRows()).isZero();
    assertThat(response.getUpdatedRows()).isEqualTo(1);
    assertThat(response.getBatchNo()).isEqualTo("SRM-20260518-001");
    verify(mapper, never()).insert(any(SupplierSupplyRatio.class));
    verify(mapper).updateById(existing);
    assertThat(existing.getSourceType()).isEqualTo("SRM");
    assertThat(existing.getSourceBatchNo()).isEqualTo("SRM-20260518-001");
    assertThat(existing.getSupplyRatio()).isEqualByComparingTo("0.75");
    assertThat(existing.getUpdatedBy()).isEqualTo("srm-job");
  }

  @Test
  @DisplayName("Excel 与 SRM 先后写入同键：后写入者更新来源字段和供货比例")
  void excelThenSrmUpdatesSourceAndSupplyRatio() {
    SupplierSupplyRatio existing = new SupplierSupplyRatio();
    existing.setId(121L);
    existing.setDeleted(0);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null, existing);

    SupplierSupplyRatioImportResponse excelResponse =
        service.importRows(
            List.of(row("203240251", "小阀座", "SHF-01", "供应商A", "0.6")),
            "ratio.xls",
            "COMMERCIAL",
            "alice");
    SupplierSupplyRatioImportResponse srmResponse =
        service.upsertFromRows(
            List.of(importRow("203240251", "小阀座", "SHF-01", "供应商A", "0.8")),
            SupplierSupplyRatioSourceType.SRM,
            "SRM-20260518-002",
            null,
            "COMMERCIAL",
            "srm-job");

    assertThat(excelResponse.getInsertedRows()).isEqualTo(1);
    assertThat(srmResponse.getInsertedRows()).isZero();
    assertThat(srmResponse.getUpdatedRows()).isEqualTo(1);
    verify(mapper).insert(any(SupplierSupplyRatio.class));
    verify(mapper).updateById(existing);
    assertThat(existing.getSourceType()).isEqualTo("SRM");
    assertThat(existing.getSourceBatchNo()).isEqualTo("SRM-20260518-002");
    assertThat(existing.getSupplyRatio()).isEqualByComparingTo("0.8");
    assertThat(existing.getImportedBy()).isEqualTo("srm-job");
  }

  @Test
  @DisplayName("同物料同型号不同供应商：保留多条供应商记录")
  void sameMaterialDifferentSuppliersCanCoexist() {
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);

    SupplierSupplyRatioImportResponse response =
        service.importRows(
            List.of(
                row("203240251", "小阀座", "SHF-01", "供应商A", "0.6"),
                row("203240251", "小阀座", "SHF-01", "供应商B", "0.4")),
            "ratio.xls", "COMMERCIAL", "alice");

    assertThat(response.getInsertedRows()).isEqualTo(2);
    ArgumentCaptor<SupplierSupplyRatio> captor = ArgumentCaptor.forClass(SupplierSupplyRatio.class);
    verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(SupplierSupplyRatio::getSupplierName)
        .containsExactly("供应商A", "供应商B");
  }

  @Test
  @DisplayName("同物料同供应商不同型号：按新业务键更新同一条记录")
  void sameMaterialAndSupplierWithDifferentSpecUpdatesSameRow() {
    SupplierSupplyRatio existing = new SupplierSupplyRatio();
    existing.setId(130L);
    existing.setDeleted(0);
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null, existing);

    SupplierSupplyRatioImportResponse first =
        service.importRows(
            List.of(row("203240251", "小阀座", "SHF-01", "供应商A", "0.6")),
            "ratio.xls",
            "COMMERCIAL",
            "alice");
    SupplierSupplyRatioImportResponse second =
        service.importRows(
            List.of(row("203240251", "小阀座改名", "SHF-02", "供应商A", "0.8")),
            "ratio.xls",
            "COMMERCIAL",
            "bob");

    assertThat(first.getInsertedRows()).isEqualTo(1);
    assertThat(second.getInsertedRows()).isZero();
    assertThat(second.getUpdatedRows()).isEqualTo(1);
    verify(mapper).updateById(existing);
    assertThat(existing.getMaterialName()).isEqualTo("小阀座改名");
    assertThat(existing.getSpecModel()).isEqualTo("SHF-02");
    assertThat(existing.getSupplyRatio()).isEqualByComparingTo("0.8");
  }

  @Test
  @DisplayName("Excel 导入：60% 和 0.6 都解析为 0.6，错误行进入响应且不阻断有效行")
  void importExcelParsesRatiosAndCollectsInvalidRows() throws Exception {
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);

    SupplierSupplyRatioImportResponse response =
        service.importExcel(workbookWithMixedRows(), "ratio.xlsx", "COMMERCIAL", "alice");

    assertThat(response.getInsertedRows()).isEqualTo(2);
    assertThat(response.getUpdatedRows()).isZero();
    assertThat(response.getSkippedRows()).isEqualTo(3);
    assertThat(response.getErrorRows()).isEqualTo(3);
    assertThat(response.getErrors())
        .anySatisfy(error -> assertThat(error).contains("物料代码不能为空"))
        .anySatisfy(error -> assertThat(error).contains("供应商不能为空"))
        .anySatisfy(error -> assertThat(error).contains("供货比例数字格式不正确"));

    ArgumentCaptor<SupplierSupplyRatio> captor = ArgumentCaptor.forClass(SupplierSupplyRatio.class);
    verify(mapper, org.mockito.Mockito.times(2)).insert(captor.capture());
    assertThat(captor.getAllValues())
        .extracting(SupplierSupplyRatio::getSupplyRatio)
        .allSatisfy(value -> assertThat(value).isEqualByComparingTo("0.6"));
  }

  @Test
  @DisplayName("SSR-09 真实 Excel 重复导入：同业务键更新，不增加总行数")
  void realExcelRepeatedImportIsIdempotentByBusinessKey() throws Exception {
    assumeTrue(Files.exists(REAL_SAMPLE), "真实 Excel 不存在，跳过本地回归");

    SupplierSupplyRatioWorkbookParserImpl parser = new SupplierSupplyRatioWorkbookParserImpl();
    List<SupplierSupplyRatioExcelRow> parsedRows;
    try (var input = Files.newInputStream(REAL_SAMPLE)) {
      parsedRows = parser.parse(input, REAL_SAMPLE.getFileName().toString()).getRows();
    }
    assertThat(parsedRows).hasSize(51);
    long uniqueKeys = parsedRows.stream().map(this::keyOf).distinct().count();
    assertThat(uniqueKeys).isPositive();

    Map<String, SupplierSupplyRatio> repo = new LinkedHashMap<>();
    AtomicInteger selectIndex = new AtomicInteger();
    AtomicLong idSequence = new AtomicLong(1);
    when(mapper.selectOne(any(Wrapper.class))).thenAnswer(invocation -> {
      SupplierSupplyRatioExcelRow current = parsedRows.get(selectIndex.getAndIncrement() % parsedRows.size());
      return repo.get(keyOf(current));
    });
    when(mapper.insert(any(SupplierSupplyRatio.class))).thenAnswer(invocation -> {
      SupplierSupplyRatio entity = invocation.getArgument(0);
      entity.setId(idSequence.getAndIncrement());
      repo.put(keyOf(entity), entity);
      return 1;
    });
    when(mapper.updateById(any(SupplierSupplyRatio.class))).thenAnswer(invocation -> {
      SupplierSupplyRatio entity = invocation.getArgument(0);
      repo.put(keyOf(entity), entity);
      return 1;
    });

    SupplierSupplyRatioImportResponse first;
    try (var input = Files.newInputStream(REAL_SAMPLE)) {
      first = service.importExcel(input, REAL_SAMPLE.getFileName().toString(), "COMMERCIAL", "alice");
    }
    SupplierSupplyRatioImportResponse second;
    try (var input = Files.newInputStream(REAL_SAMPLE)) {
      second = service.importExcel(input, REAL_SAMPLE.getFileName().toString(), "COMMERCIAL", "bob");
    }

    assertThat(first.getTotalRows()).isEqualTo(51);
    assertThat(first.getInsertedRows()).isEqualTo((int) uniqueKeys);
    assertThat(first.getUpdatedRows()).isEqualTo(parsedRows.size() - (int) uniqueKeys);
    assertThat(first.getErrorRows()).isZero();
    assertThat(repo).hasSize((int) uniqueKeys);
    assertThat(second.getTotalRows()).isEqualTo(51);
    assertThat(second.getInsertedRows()).isZero();
    assertThat(second.getUpdatedRows()).isEqualTo(51);
    assertThat(second.getErrorRows()).isZero();
    assertThat(repo).hasSize((int) uniqueKeys);
    assertThat(repo.values()).allSatisfy(row -> assertThat(row.getImportedBy()).isEqualTo("bob"));
  }

  @Test
  @DisplayName("错误行测试：缺物料代码、缺供应商时跳过；供货比例为空按 0 入库")
  void importRowsSkipsInvalidRows() {
    when(mapper.selectOne(any(Wrapper.class))).thenReturn(null);

    SupplierSupplyRatioImportResponse response =
        service.importRows(
            List.of(
                row(null, "小阀座", "SHF-01", "供应商A", "0.6"),
                row("203240251", "小阀座", "SHF-01", null, "0.6"),
                row("203240252", "小阀座", "SHF-01", "供应商B", null)),
            "ratio.xls", "COMMERCIAL", "alice");

    assertThat(response.getInsertedRows()).isEqualTo(1);
    assertThat(response.getSkippedRows()).isEqualTo(2);
    assertThat(response.getErrors()).hasSize(2);
    ArgumentCaptor<SupplierSupplyRatio> captor = ArgumentCaptor.forClass(SupplierSupplyRatio.class);
    verify(mapper).insert(captor.capture());
    assertThat(captor.getValue().getSupplyRatio()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  private SupplierSupplyRatioExcelRow row(
      String materialCode,
      String materialName,
      String specModel,
      String supplierName,
      String ratio) {
    SupplierSupplyRatioExcelRow row = new SupplierSupplyRatioExcelRow();
    row.setRowNo(2);
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialName);
    row.setSpecModel(specModel);
    row.setUnit("个");
    row.setMaterialShape("B");
    row.setSupplierName(supplierName);
    row.setSupplyRatio(ratio == null ? null : new BigDecimal(ratio));
    return row;
  }

  private SupplierSupplyRatioImportRow importRow(
      String materialCode,
      String materialName,
      String specModel,
      String supplierName,
      String ratio) {
    SupplierSupplyRatioImportRow row = new SupplierSupplyRatioImportRow();
    row.setRowNo(2);
    row.setMaterialCode(materialCode);
    row.setMaterialName(materialName);
    row.setSpecModel(specModel);
    row.setUnit("个");
    row.setMaterialShape("B");
    row.setSupplierName(supplierName);
    row.setSupplierCode("SRM-A");
    row.setSupplyRatio(ratio == null ? null : new BigDecimal(ratio));
    return row;
  }

  private String keyOf(SupplierSupplyRatioExcelRow row) {
    return SupplierSupplyRatioNormalizeUtils.buildDedupeKey(
        row.getMaterialCode(), row.getMaterialName(), row.getSupplierName(), row.getSpecModel());
  }

  private String keyOf(SupplierSupplyRatio row) {
    return SupplierSupplyRatioNormalizeUtils.buildDedupeKey(
        row.getMaterialCode(), row.getMaterialName(), row.getSupplierName(), row.getSpecModel());
  }

  private ByteArrayInputStream workbookWithMixedRows() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet("供货比例-SRM");
      Row header = sheet.createRow(0);
      List<String> headers = List.of("物料代码", "物料名称", "型号", "单位", "物料形态属性", "供应商", "供货比例", "规则：取供货比例大的");
      for (int i = 0; i < headers.size(); i++) {
        header.createCell(i).setCellValue(headers.get(i));
      }
      excelRow(sheet, 1, "203240251", "小阀座", "SHF-01", "个", "B", "供应商A", "60%");
      excelRow(sheet, 2, "203240251", "小阀座", "SHF-01", "个", "B", "供应商B", "0.6");
      excelRow(sheet, 3, "", "小阀座", "SHF-01", "个", "B", "供应商C", "0.6");
      excelRow(sheet, 4, "203240252", "小阀座", "SHF-01", "个", "B", "", "0.6");
      excelRow(sheet, 5, "203240253", "小阀座", "SHF-01", "个", "B", "供应商D", "abc");
      workbook.write(out);
      return new ByteArrayInputStream(out.toByteArray());
    }
  }

  private void excelRow(
      Sheet sheet,
      int rowIndex,
      String materialCode,
      String materialName,
      String specModel,
      String unit,
      String materialShape,
      String supplierName,
      String ratio) {
    Row row = sheet.createRow(rowIndex);
    row.createCell(0).setCellValue(materialCode);
    row.createCell(1).setCellValue(materialName);
    row.createCell(2).setCellValue(specModel);
    row.createCell(3).setCellValue(unit);
    row.createCell(4).setCellValue(materialShape);
    row.createCell(5).setCellValue(supplierName);
    row.createCell(6).setCellValue(ratio);
  }
}
