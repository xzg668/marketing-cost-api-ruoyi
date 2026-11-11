package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.service.impl.*;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;

/** Reuse the complete costing/browser cases with actual approval-workbook imports. */
class LinkedImportRefreshBrowserSession extends CuBandMaterialBrowserSession {
  @Autowired OriginalLinkedImportService imports;
  @Autowired OriginalLinkedWorkbookParser parser;
  int imported;

  @Override long rule(String code,String month,String org,String expression) {
    try(var book=new HSSFWorkbook();var output=new ByteArrayOutputStream()) {
      var sheet=book.createSheet("加工费审批");var header=sheet.createRow(0);
      String[] names={"物料代码","物料名称","规格","型号","税率","现联动公式","现不含税加工费","现含税加工费","现供方名称","价格","价格单位","供应商代码"};
      for(int i=0;i<names.length;i++)header.createCell(i).setCellValue(names[i]);
      var row=sheet.createRow(1);row.createCell(0).setCellValue(code);row.createCell(1).setCellValue("材料因素导入验收");
      row.createCell(4).setCellValue(.13);row.createCell(5).setCellValue(expression);row.createCell(6).setCellValue(0);row.createCell(7).setCellValue(0);
      row.createCell(8).setCellValue("杭州华光焊接新材料股份有限公司");row.createCell(10).setCellValue("件");
      // Both explicit codes and missing-code fallback must traverse the actual import path.
      row.createCell(11).setCellValue(imported++==0?"S000332":"");
      String[] labels={"CU","Zn","美国柜装黄铜","H65黄铜边料","Ag","Sn"};
      String[] variables={"cu","zn","brass","h65","ag","sn"};
      for(int i=0;i<labels.length;i++) {
        var factor=sheet.createRow(i+2);factor.createCell(12).setCellValue(labels[i]);
        factor.createCell(13).setCellValue(200+i);
        expression=expression.replaceAll("\\b"+variables[i]+"\\b",java.util.regex.Matcher.quoteReplacement("$N$"+(i+3)));
      }
      row.createCell(9).setCellFormula("("+expression+")*1.13/1.13");
      book.getCreationHelper().createFormulaEvaluator().evaluateAll();book.write(output);
      byte[] bytes=output.toByteArray();
      var command=new PriceLinkedImportCommand(bytes,"加工费审批.xls",month,"COMMERCIAL",false,null,null,null,null).withPriceOrgCode(org);
      var preview=imports.preview(command);assertThat(preview.getErrors()).isEmpty();
      var confirmed=new PriceLinkedImportCommand(bytes,command.getSourceFileName(),month,"COMMERCIAL",false,null,null,null,preview.getFileSha256())
          .withPriceOrgCode(org).withPreviewFingerprint(preview.getPreviewFingerprint());
      imports.confirm(confirmed);
      jdbc.update("INSERT INTO lp_supplier_supply_ratio(business_unit_type,material_code,material_name,spec_model,supplier_name,supplier_code,supply_ratio,is_active,deleted) VALUES('COMMERCIAL',?,'fixture','SPEC','杭州华光焊接新材料股份有限公司','S000332',1,1,0)",code);
      var rows=rules.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(com.sanhua.marketingcost.entity.PriceLinkedItem.class)
          .eq(com.sanhua.marketingcost.entity.PriceLinkedItem::getMaterialCode,code));
      assertThat(rows).hasSize(1);return rows.getFirst().getId();
    } catch(java.io.IOException error) {throw new IllegalStateException(error);}
  }
}
