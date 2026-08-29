package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.ProductPropertyImportResult;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.ProductProperty;
import com.sanhua.marketingcost.entity.ProductPropertyRule;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyMapper;
import com.sanhua.marketingcost.mapper.ProductPropertyRuleMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProductPropertyServiceImplTest {
  private ProductPropertyMapper propertyMapper;
  private ProductPropertyRuleMapper ruleMapper;
  private MaterialMasterRawMapper materialMapper;
  private ProductPropertyServiceImpl service;

  @BeforeEach
  void setUp() {
    propertyMapper = mock(ProductPropertyMapper.class);
    ruleMapper = mock(ProductPropertyRuleMapper.class);
    materialMapper = mock(MaterialMasterRawMapper.class);
    service = new ProductPropertyServiceImpl(propertyMapper, ruleMapper, materialMapper);
    when(ruleMapper.selectList(any())).thenReturn(rules());
    when(propertyMapper.selectList(any())).thenReturn(List.of());
    when(propertyMapper.upsertBatch(anyList())).thenReturn(1);
  }

  @Test
  void importsNamedSecondSheetAndUsesCachedFormulaDivisionFromExcel() throws Exception {
    MaterialMasterRaw database = material("1007900000147", "数据库事业部");
    when(materialMapper.selectActiveProductionDivisionsByCodes(anyList()))
        .thenReturn(List.of(database));

    ProductPropertyImportResult result = service.importExcel(
        new ByteArrayInputStream(workbookWithFormulaDivision()),
        "业务产品属性.xlsx", 2026, "COMMERCIAL", "INCREMENTAL");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getInserted()).isEqualTo(1);
    assertThat(result.getExcelDivision()).isEqualTo(1);
    assertThat(result.getWarnings()).singleElement().asString().contains("已按 Excel 导入");
    ArgumentCaptor<List<ProductProperty>> captor = ArgumentCaptor.forClass(List.class);
    verify(propertyMapper).upsertBatch(captor.capture());
    assertThat(captor.getValue()).singleElement().satisfies(row -> {
      assertThat(row.getProductCode()).isEqualTo("1007900000147");
      assertThat(row.getBusinessDivision()).isEqualTo("Excel事业部");
      assertThat(row.getProductAttr()).isEqualTo("非标品");
    });
  }

  @Test
  void importsStandaloneAeSheetAndResolvesDivisionFromDatabase() throws Exception {
    when(materialMapper.selectActiveProductionDivisionsByCodes(anyList()))
        .thenReturn(List.of(material("1145900000271", "电子产品事业部")));

    ProductPropertyImportResult result = service.importExcel(
        new ByteArrayInputStream(workbookAeOnly()),
        "单独第二页.xlsx", 2026, "COMMERCIAL", "INCREMENTAL");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getResolvedDivision()).isEqualTo(1);
    ArgumentCaptor<List<ProductProperty>> captor = ArgumentCaptor.forClass(List.class);
    verify(propertyMapper).upsertBatch(captor.capture());
    assertThat(captor.getValue().getFirst().getBusinessDivision()).isEqualTo("电子产品事业部");
  }

  @Test
  void rejectsAeRowWhenDatabaseHasConflictingDivisionsWithoutWriting() throws Exception {
    when(materialMapper.selectActiveProductionDivisionsByCodes(anyList()))
        .thenReturn(List.of(
            material("1145900000271", "电子产品事业部"),
            material("1145900000271", "商用部品事业部")));

    ProductPropertyImportResult result = service.importExcel(
        new ByteArrayInputStream(workbookAeOnly()),
        "单独第二页.xlsx", 2026, "COMMERCIAL", "INCREMENTAL");

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getErrors()).anyMatch(message -> message.contains("多个生产事业部"));
  }

  private byte[] workbookWithFormulaDivision() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var master = workbook.createSheet("物料主档");
      master.createRow(0).createCell(0).setCellValue("料号");
      master.getRow(0).createCell(1).setCellValue("生产事业部");
      master.createRow(1).createCell(0).setCellValue("1007900000147");
      master.getRow(1).createCell(1).setCellValue("Excel事业部");
      var sheet = workbook.createSheet("报价系统展示-产品属性");
      writeHeader(sheet.createRow(0), true);
      var row = sheet.createRow(1);
      row.createCell(0).setCellValue("1007900000147");
      row.createCell(1).setCellValue("单向阀");
      row.createCell(2).setCellValue("规格A");
      row.createCell(3).setCellValue("型号A");
      row.createCell(4).setCellValue("非标品");
      row.createCell(5).setCellFormula("VLOOKUP(A2,'物料主档'!A:B,2,FALSE)");
      workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private byte[] workbookAeOnly() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("Sheet1");
      writeHeader(sheet.createRow(0), false);
      var row = sheet.createRow(1);
      row.createCell(0).setCellValue("1145900000271");
      row.createCell(1).setCellValue("电子膨胀阀");
      row.createCell(2).setCellValue("规格B");
      row.createCell(3).setCellValue("型号B");
      row.createCell(4).setCellValue("标准品");
      workbook.write(output);
      return output.toByteArray();
    }
  }

  private void writeHeader(org.apache.poi.ss.usermodel.Row row, boolean includeDivision) {
    List<String> headers = includeDivision
        ? List.of("料号", "品名", "规格", "型号", "产品属性", "生产事业部")
        : List.of("料号", "品名", "规格", "型号", "产品属性");
    for (int index = 0; index < headers.size(); index++) {
      row.createCell(index).setCellValue(headers.get(index));
    }
  }

  private MaterialMasterRaw material(String code, String division) {
    MaterialMasterRaw row = new MaterialMasterRaw();
    row.setMaterialCode(code);
    row.setProductionDivision(division);
    return row;
  }

  private List<ProductPropertyRule> rules() {
    return List.of(
        rule("非标品", "0.05"), rule("标准品", "0"),
        rule("定制品", "0.05"), rule("OEM", "0"));
  }

  private ProductPropertyRule rule(String attr, String rate) {
    ProductPropertyRule row = new ProductPropertyRule();
    row.setBusinessUnitType("COMMERCIAL");
    row.setPropertyYear(2026);
    row.setProductAttr(attr);
    row.setUpliftRate(new BigDecimal(rate));
    return row;
  }
}
