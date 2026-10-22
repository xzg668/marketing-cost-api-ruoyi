package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.WorkbookFactory;

final class SettlementRuleTestSupport {
  private SettlementRuleTestSupport() {}

  /** 模拟升级前已保存的人工重量修订；不提供当前业务的维护入口。 */
  static SettlementPriceRecord legacyWeights(SettlementPriceRepository prices, SettlementRuleRevisionFactory revisions, SettlementPriceRecord prior,
      String copper, String zinc) {
    var row = prior.copy();
    row.setId(null);
    row.setRevisionNo(prior.getRevisionNo() + 1);
    row.setCopperWeightG(new BigDecimal(copper));
    row.setZincWeightG(new BigDecimal(zinc));
    row.setWeightsConfirmed(true);
    row.setWeightConfirmationNote("升级前历史重量测试夹具");
    row.setWeightsConfirmedBy("legacy-fixture");
    row.setWeightsConfirmedAt(LocalDateTime.of(2026, 10, 17, 0, 0));
    row.setCreatedBy("legacy-fixture");
    row.setCreatedAt(null);
    row.setContentHash(revisions.contentHash(row));
    return prices.insert(row);
  }

  /** 当前重量变更只走原稿导入，保留源单元格行号及公式引用。 */
  static void importWeights(SettlementPriceImportService imports, SettlementScope scope,
      String material, double copper, double zinc) throws Exception {
    try (var input = SettlementRuleTestSupport.class.getResourceAsStream("/settlement/original-20261017.xls");
         var original = WorkbookFactory.create(input); var workbook = new XSSFWorkbook();
         var output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("零部件业务");
      var formatter = new DataFormatter();
      boolean found = false;
      for (Row source : original.getSheetAt(0)) {
        boolean selected = material.equals(formatter.formatCellValue(source.getCell(0)));
        if (source.getRowNum() >= 3 && !selected) continue;
        var row = sheet.createRow(source.getRowNum());
        for (Cell cell : source) {
          var copy = row.createCell(cell.getColumnIndex());
          switch (cell.getCellType()) {
            case STRING -> copy.setCellValue(cell.getStringCellValue());
            case NUMERIC -> copy.setCellValue(cell.getNumericCellValue());
            case FORMULA -> copy.setCellFormula(cell.getCellFormula());
            default -> copy.setBlank();
          }
        }
        if (selected) {
          row.getCell(6, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(copper);
          row.getCell(7, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK).setCellValue(zinc);
          int r = row.getRowNum() + 1;
          row.getCell(11).setCellFormula("ROUND(F" + r + "+((($P$2-$O$2)*$G" + r
              + "+($P$3-$O$3)*$H" + r + ")/1000/1.13)*1.2,4)");
          found = true;
        }
      }
      if (!found) throw new IllegalArgumentException("原稿测试料号不存在：" + material);
      workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
      workbook.write(output);
      byte[] bytes = output.toByteArray();
      String name = "原稿重量修订.xlsx";
      imports.confirm(bytes, name, scope, imports.preview(bytes, name, scope).fingerprint(), "finance");
    }
  }
}
