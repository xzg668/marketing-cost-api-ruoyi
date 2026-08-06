package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioExcelRow;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioWorkbookParseResult;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SupplierSupplyRatioWorkbookParserImplTest {
  private SupplierSupplyRatioWorkbookParserImpl parser;

  @BeforeEach
  void setUp() {
    parser = new SupplierSupplyRatioWorkbookParserImpl();
  }

  @Test
  void parsesSupplyRatioSheetHeaderAndFirstRows() {
    SupplierSupplyRatioWorkbookParseResult result =
        parser.parse(
            workbook(
                "供货比例-SRM",
                List.of(
                    List.of("物料代码", "物料名称", "型号", "单位", "物料形态属性", "供应商", "供货比例", "规则：取供货比例大的"),
                    List.of("201800082", "滑碗", "SHF-000-042002", "只", "采购件", "A", "1", ""),
                    List.of("203240246", "扁平头铆钉", "SHF-000-088002（商用专用）", "只", "采购件", "B", "1.0", ""),
                    List.of("203240251", "小阀座", "SHF-000-036003（商用专用）", "只", "采购件", "新昌县大新机械厂", "60%", ""))),
            "sample.xlsx");

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getSheetName()).isEqualTo("供货比例-SRM");
    assertThat(result.getHeaderRowNumber()).isEqualTo(1);
    assertThat(result.getHeaders()).containsExactly("物料代码", "物料名称", "型号", "单位", "物料形态属性", "供应商", "供货比例", "规则：取供货比例大的");
    assertThat(result.getRows()).hasSize(3);
    SupplierSupplyRatioExcelRow first = result.getRows().get(0);
    assertThat(first.getRowNo()).isEqualTo(2);
    assertThat(first.getMaterialCode()).isEqualTo("201800082");
    assertThat(first.getMaterialName()).isEqualTo("滑碗");
    assertThat(first.getSpecModel()).isEqualTo("SHF-000-042002");
    assertThat(first.getSupplierName()).isEqualTo("A");
    assertThat(first.getSupplierCode()).isNull();
    assertThat(first.getSupplyRatio()).isEqualByComparingTo("1");
    assertThat(result.getRows().get(2).getSupplyRatio()).isEqualByComparingTo("0.6");
  }

  @Test
  void parsesOptionalSupplierCodeColumnWhenPresent() {
    SupplierSupplyRatioWorkbookParseResult result =
        parser.parse(
            workbook(
                "供货比例-SRM",
                List.of(
                    List.of("物料代码", "物料名称", "型号", "单位", "物料形态属性", "供应商", "供应商代码", "供货比例", "规则：取供货比例大的"),
                    List.of("201503873", "管件", "SPEC-A", "只", "采购件", "公主岭市远达实业有限公司", " S000841 ", "60%", ""),
                    List.of("201503874", "管件", "SPEC-B", "只", "采购件", "吉林省合信汽配有限公司", "", "40%", ""))),
            "supplier-code.xlsx");

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getHeaders()).contains("供应商代码");
    assertThat(result.getRows()).hasSize(2);
    assertThat(result.getRows().get(0).getSupplierCode()).isEqualTo("S000841");
    assertThat(result.getRows().get(1).getSupplierCode()).isNull();
  }

  @Test
  void normalizesDedupeKeyByRemovingBlankCharacters() {
    String key =
        SupplierSupplyRatioNormalizeUtils.buildDedupeKey(
            " 203 240\t251 ",
            " 小　阀\n座 ",
            " 新昌县 大新机械厂 ",
            " SHF-000-036003\r\n（商用专用） ");

    assertThat(key).isEqualTo("203240251|新昌县大新机械厂");
  }

  @Test
  void blankSupplyRatioParsesAsZero() {
    SupplierSupplyRatioWorkbookParseResult result =
        parser.parse(
            workbook(
                "供货比例-SRM",
                List.of(
                    List.of("物料代码", "物料名称", "型号", "单位", "物料形态属性", "供应商", "供货比例", "规则：取供货比例大的"),
                    List.of("203240251", "小阀座", "SHF-000-036003", "只", "采购件", "供应商A", "", ""))),
            "sample.xlsx");

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getRows()).hasSize(1);
    assertThat(result.getRows().get(0).getSupplyRatio()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void parsesSrmHeaderAliasesFromCurrentWorkbook() {
    SupplierSupplyRatioWorkbookParseResult result =
        parser.parse(
            workbook(
                "供货比例-SRM",
                List.of(
                    List.of("物料代码*", "物料名称*", "物料规格", "物料型号", "物料图号", "主分类代码", "主分类名称",
                        "计量单位", "U9物料形态属性", "供应商", "供货比例", "规则：取供货比例大的"),
                    List.of("721250208", "套管", "MDF-A03-020001", "MDF-A03-020001", "MDF-A03-020001",
                        "121191304", "专用零部件", "只", "采购件", "丽水市丽凯制冷配件有限公司", "", ""))),
            "srm-current.xlsx");

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getRows()).hasSize(1);
    SupplierSupplyRatioExcelRow row = result.getRows().get(0);
    assertThat(row.getMaterialCode()).isEqualTo("721250208");
    assertThat(row.getSpecModel()).isEqualTo("MDF-A03-020001");
    assertThat(row.getUnit()).isEqualTo("只");
    assertThat(row.getMaterialShape()).isEqualTo("采购件");
    assertThat(row.getSupplierName()).isEqualTo("丽水市丽凯制冷配件有限公司");
    assertThat(row.getSupplyRatio()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void fullSizeSupplyRatioFixtureCanBeParsed() throws Exception {
    List<List<String>> rows = new ArrayList<>();
    rows.add(List.of(
        "物料代码", "物料名称", "型号", "单位", "物料形态属性", "供应商", "供货比例",
        "规则：取供货比例大的"));
    for (int i = 0; i < 51; i++) {
      rows.add(List.of(
          i == 0 ? "201800082" : "MAT-" + i,
          i == 0 ? "滑碗" : "物料" + i,
          "MODEL-" + i,
          "只",
          "采购件",
          "供应商" + i,
          i == 0 ? "1" : "60%",
          ""));
    }

    try (InputStream input = workbook("供货比例-SRM", rows)) {
      SupplierSupplyRatioWorkbookParseResult result = parser.parse(input, "supply-ratio-fixture.xlsx");

      assertThat(result.getErrors()).isEmpty();
      assertThat(result.getSheetName()).isEqualTo("供货比例-SRM");
      assertThat(result.getHeaders()).hasSize(8);
      assertThat(result.getRows()).hasSize(51);
      assertThat(result.getRows().get(0).getMaterialCode()).isEqualTo("201800082");
      assertThat(result.getRows().get(0).getSupplyRatio()).isEqualByComparingTo(BigDecimal.ONE);
    }
  }

  private InputStream workbook(String sheetName, List<List<String>> rows) {
    try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      Sheet sheet = workbook.createSheet(sheetName);
      for (int r = 0; r < rows.size(); r++) {
        Row row = sheet.createRow(r);
        List<String> values = rows.get(r);
        for (int c = 0; c < values.size(); c++) {
          row.createCell(c).setCellValue(values.get(c));
        }
      }
      workbook.write(output);
      return new ByteArrayInputStream(output.toByteArray());
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
