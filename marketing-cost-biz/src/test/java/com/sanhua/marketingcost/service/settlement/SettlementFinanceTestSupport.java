package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPublicationService;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;

/** 仅用于隔离MySQL测试；通过正式发布服务创建真实财务输入，不模拟结算取价。 */
final class SettlementFinanceTestSupport {
  static void clear(JdbcTemplate jdbc) {
    jdbc.update("DELETE FROM lp_factor_row_ref WHERE factor_upload_batch_id IN (SELECT id FROM lp_factor_upload_batch WHERE import_type='FINANCE_BASE')");
    jdbc.update("DELETE FROM lp_factor_monthly_price WHERE source_tag='FINANCE_BASE'");
    jdbc.update("DELETE FROM lp_factor_upload_batch WHERE import_type='FINANCE_BASE'");
  }

  static FinanceFactorPublicationService.Publication publish(FinanceFactorPublicationService service,
      SettlementScope scope, String copper, String zinc) {
    try (var workbook = new XSSFWorkbook()) {
      var sheet = workbook.createSheet("商用财务基价测试");
      var header = sheet.createRow(0);
      String[] labels = {"基价名称", "基价代号", "计量单位", "上月均价", "联动基价调整"};
      for (int c = 0; c < labels.length; c++) header.createCell(c).setCellValue(labels[c]);
      String[][] values = {{"电解铜", "1#Cu", copper}, {"锌", "1#Zn", zinc}};
      for (int i = 0; i < values.length; i++) {
        var row = sheet.createRow(i + 1);
        row.createCell(0).setCellValue(values[i][0]); row.createCell(1).setCellValue(values[i][1]);
        row.createCell(2).setCellValue("元/公斤");
        for (int c : new int[]{3, 4}) row.createCell(c).setCellValue(values[i][2]);
      }
      var bytes = new ByteArrayOutputStream(); workbook.write(bytes);
      var context = new FinanceFactorImportContext(scope.businessUnitType(), "210", scope.priceMonth(), null,
          "CU_" + new BigDecimal(copper).movePointRight(3).stripTrailingZeros().toPlainString().replace('.', '_'));
      var preview = service.preview(bytes.toByteArray(), "商用测试基价.xlsx", context);
      return service.publish(bytes.toByteArray(), "商用测试基价.xlsx", context, preview.previewFingerprint(), "test-finance");
    } catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
  }
}
