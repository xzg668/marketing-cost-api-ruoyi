package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.dto.FormulaFactorRef;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PriceLinkedRealExcelRegressionTest {

  private static final Path REAL_SAMPLE =
      Path.of("/Users/xiexicheng/Documents/sales_cost/产品成本计算表（3.29- 提供）.xls");
  private static final Path DECRYPTED_LINKED_SAMPLE =
      Path.of("/Users/xiexicheng/Documents/sales_cost/decrypted-sheets/联动价-部品.xlsx");
  private static final Path DECRYPTED_FACTOR_SAMPLE =
      Path.of("/Users/xiexicheng/Documents/sales_cost/decrypted-sheets/影响因素.xlsx");

  @Test
  @DisplayName("真实 Excel：示例联动价单价公式能解析到影响因素10 E64/E44")
  void realWorkbookFormulaCanParseFactorRefs() throws Exception {
    Assumptions.assumeTrue(Files.exists(REAL_SAMPLE), "真实 Excel 不存在，跳过本地回归");
    PriceLinkedFormulaFactorRefParserImpl parser = new PriceLinkedFormulaFactorRefParserImpl();

    List<FormulaHit> hits;
    try {
      hits = findFormulaHits(REAL_SAMPLE, parser);
    } catch (EncryptedDocumentException e) {
      Assumptions.assumeTrue(false, "真实原始 Excel 已加密，POI 无密码无法读取公式：" + e.getMessage());
      return;
    }

    assertThat(hits)
        .as("真实 Excel 中应存在引用 影响因素10!E64 和 影响因素10!E44 的联动价公式")
        .anySatisfy(hit -> {
          assertThat(hit.formula()).contains("影响因素10");
          assertThat(hit.refs()).extracting(FormulaFactorRef::getSheetName)
              .contains("影响因素10");
          assertThat(hit.refs()).extracting(FormulaFactorRef::getRowNumber)
              .contains(64, 44);
        });
  }

  @Test
  @DisplayName("真实 Excel：影响因素 sheet 能解析出 E64/E44 对应行")
  void realWorkbookFactorRowsCanBeParsed() throws Exception {
    Assumptions.assumeTrue(Files.exists(REAL_SAMPLE), "真实 Excel 不存在，跳过本地回归");
    PriceLinkedFactorWorkbookParserImpl parser = new PriceLinkedFactorWorkbookParserImpl();

    try (InputStream input = Files.newInputStream(REAL_SAMPLE);
        Workbook ignored = WorkbookFactory.create(input)) {
      // only verifies that POI can open the original workbook before running parser assertions
    } catch (EncryptedDocumentException e) {
      Assumptions.assumeTrue(false, "真实原始 Excel 已加密，POI 无密码无法读取影响因素 sheet：" + e.getMessage());
      return;
    }

    FactorWorkbookParseResult result;
    try (InputStream input = Files.newInputStream(REAL_SAMPLE)) {
      result = parser.parse(input, REAL_SAMPLE.getFileName().toString());
    }

    assertThat(result.getValidRowCount()).isGreaterThan(0);
    assertThat(result.getSheets())
        .anySatisfy(sheet -> assertThat(sheet.getRows())
            .anySatisfy(row -> {
              assertThat(row.getSourceSheetName()).contains("影响因素");
              assertThat(row.getSourceRowNumber()).isEqualTo(64);
              assertThat(row.getFactorSeqNo()).isNotBlank();
              assertThat(row.getShortName()).isNotBlank();
              assertThat(row.getPriceSource()).isNotBlank();
              assertThat(row.getPrice()).isNotNull();
            }));
    assertThat(result.getSheets())
        .anySatisfy(sheet -> assertThat(sheet.getRows())
            .anySatisfy(row -> {
              assertThat(row.getSourceSheetName()).contains("影响因素");
              assertThat(row.getSourceRowNumber()).isEqualTo(44);
              assertThat(row.getShortName()).isNotBlank();
              assertThat(row.getPrice()).isNotNull();
            }));
  }

  @Test
  @DisplayName("已解密真实 sheet：示例联动价公式能解析到影响因素10 E64/E44")
  void decryptedLinkedSheetFormulaCanParseFactorRefs() throws Exception {
    Assumptions.assumeTrue(Files.exists(DECRYPTED_LINKED_SAMPLE), "已解密联动价 sheet 不存在");
    PriceLinkedFormulaFactorRefParserImpl parser = new PriceLinkedFormulaFactorRefParserImpl();

    List<FormulaHit> hits = findFormulaHits(DECRYPTED_LINKED_SAMPLE, parser);
    Assumptions.assumeFalse(
        hits.isEmpty(),
        "已解密联动价 sheet 是纯值版，未保留单价列公式，不能作为真实公式回归样本");

    assertThat(hits)
        .as("已解密联动价 sheet 中应存在引用 影响因素10!E64 和 影响因素10!E44 的公式")
        .anySatisfy(hit -> {
          assertThat(hit.formula()).contains("影响因素10");
          assertThat(hit.refs()).extracting(FormulaFactorRef::getRowNumber)
              .contains(64, 44);
        });
  }

  @Test
  @DisplayName("用户提供公式文本：能解析到影响因素10 E64/E44")
  void userProvidedFormulaTextCanParseFactorRefs() {
    PriceLinkedFormulaFactorRefParserImpl parser = new PriceLinkedFormulaFactorRefParserImpl();
    String formula = "=ROUND($I$2*影响因素10!$E$64/1000-(I2-J2)*影响因素10!$E$44/1000+K2,4)/1.13";

    List<FormulaFactorRef> refs = parser.parse(formula);

    assertThat(refs).hasSize(2);
    assertThat(refs.get(0).getSheetName()).isEqualTo("影响因素10");
    assertThat(refs.get(0).getColumnName()).isEqualTo("E");
    assertThat(refs.get(0).getRowNumber()).isEqualTo(64);
    assertThat(refs.get(1).getSheetName()).isEqualTo("影响因素10");
    assertThat(refs.get(1).getColumnName()).isEqualTo("E");
    assertThat(refs.get(1).getRowNumber()).isEqualTo(44);
  }

  @Test
  @DisplayName("已解密真实 sheet：影响因素 E64/E44 行能被汇总解析")
  void decryptedFactorSheetRowsCanBeParsed() throws Exception {
    Assumptions.assumeTrue(Files.exists(DECRYPTED_FACTOR_SAMPLE), "已解密影响因素 sheet 不存在");
    PriceLinkedFactorWorkbookParserImpl parser = new PriceLinkedFactorWorkbookParserImpl();

    FactorWorkbookParseResult result;
    try (InputStream input = Files.newInputStream(DECRYPTED_FACTOR_SAMPLE)) {
      result = parser.parse(input, DECRYPTED_FACTOR_SAMPLE.getFileName().toString());
    }

    assertThat(result.getValidRowCount()).isGreaterThan(0);
    assertThat(result.getSheets())
        .anySatisfy(sheet -> assertThat(sheet.getRows())
            .anySatisfy(row -> {
              assertThat(row.getSourceRowNumber()).isEqualTo(64);
              assertThat(row.getShortName()).isNotBlank();
              assertThat(row.getPrice()).isNotNull();
            }));
    assertThat(result.getSheets())
        .anySatisfy(sheet -> assertThat(sheet.getRows())
            .anySatisfy(row -> {
              assertThat(row.getSourceRowNumber()).isEqualTo(44);
              assertThat(row.getShortName()).isNotBlank();
              assertThat(row.getPrice()).isNotNull();
            }));
  }

  private List<FormulaHit> findFormulaHits(
      Path workbookPath,
      PriceLinkedFormulaFactorRefParserImpl parser)
      throws Exception {
    List<FormulaHit> hits = new ArrayList<>();
    try (InputStream input = Files.newInputStream(workbookPath);
        Workbook workbook = WorkbookFactory.create(input)) {
      for (int s = 0; s < workbook.getNumberOfSheets(); s++) {
        Sheet sheet = workbook.getSheetAt(s);
        for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
          Row row = sheet.getRow(r);
          if (row == null) {
            continue;
          }
          for (int c = row.getFirstCellNum(); c < row.getLastCellNum(); c++) {
            if (c < 0) {
              continue;
            }
            Cell cell = row.getCell(c);
            if (cell == null || cell.getCellType() != org.apache.poi.ss.usermodel.CellType.FORMULA) {
              continue;
            }
            String formula = cell.getCellFormula();
            List<FormulaFactorRef> refs = parser.parse(formula);
            if (refs.stream().anyMatch(ref -> "影响因素10".equals(ref.getSheetName())
                && Integer.valueOf(64).equals(ref.getRowNumber()))
                && refs.stream().anyMatch(ref -> "影响因素10".equals(ref.getSheetName())
                    && Integer.valueOf(44).equals(ref.getRowNumber()))) {
              hits.add(new FormulaHit(sheet.getSheetName(), r + 1, c + 1, formula, refs));
            }
          }
        }
      }
    }
    return hits;
  }

  private record FormulaHit(
      String sheetName,
      int rowNumber,
      int columnNumber,
      String formula,
      List<FormulaFactorRef> refs) {
  }
}
