package com.sanhua.marketingcost.service.pricing.inquiry;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import java.io.ByteArrayOutputStream;

class InquiryRangePricingTest {
  static byte[] workbook(String spec, String first, String second) throws Exception {
    try(var book=new XSSFWorkbook();var bytes=new ByteArrayOutputStream()) {
      var sheet=book.createSheet("询价");
      var columns=List.of("料号","规格型号","供方名称","价格类型","批量价总价(元)","不含税价格","计量单位","供应商代码","行情因素","行情单位","区间下限","区间上限","区间单价(元)");
      var header=sheet.createRow(0);for(int i=0;i<columns.size();i++)header.createCell(i).setCellValue(columns.get(i));
      for(int i=0;i<2;i++) {
        var row=sheet.createRow(i+1);
        var values=List.of("",spec,"","区间价","","","件","","AVG|1#CU","元/吨",i==0?"90000":"100000",i==0?"100000":"",i==0?first:second);
        for(int j=0;j<values.size();j++)row.createCell(j).setCellValue(values.get(j));
      }
      book.write(bytes);return bytes.toByteArray();
    }
  }
  @Test void parseCompleteSupplierlessBandGroupAndPreserveBoundary() throws Exception {
    var rule=new InquiryWorkbookParser().parse(workbook("测试型号","2","3"),"区间.xlsx",List.of(new InquiryWorkbookParser.Factor("AVG|1#CU","1#电解铜","Cu"))).rows().getFirst();
    assertThat(rule.materialCode()).isEmpty();assertThat(rule.supplierName()).isEmpty();assertThat(rule.supplierCode()).isEmpty();
    assertThat(rule.range().bands()).hasSize(2);
    assertThat(rule.range().price(new BigDecimal("99999.999999"))).isEqualByComparingTo("2");
    assertThat(rule.range().price(new BigDecimal("100000"))).isEqualByComparingTo("3");
    assertThat(rule.range().price(new BigDecimal("120000"))).isEqualByComparingTo("3");
    assertThatThrownBy(()->rule.range().price(new BigDecimal("89999"))).hasMessageContaining("未命中");
  }
  @Test void rejectOverlapNegativeAndMissingBoundsRatherThanTakingHighestPrice() {
    assertThatThrownBy(()->new InquiryRangePricing("AVG|1#CU","元/吨",List.of(
      new InquiryRangePricing.Band(new BigDecimal("90"),new BigDecimal("101"),BigDecimal.ONE,2),
      new InquiryRangePricing.Band(new BigDecimal("100"),null,BigDecimal.TEN,3)))) .hasMessageContaining("重叠");
    assertThatThrownBy(()->new InquiryRangePricing.Band(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ONE,2)).hasMessageContaining("上限");
    assertThatThrownBy(()->new InquiryRangePricing.Band(BigDecimal.ZERO,null,new BigDecimal("-1"),2)).hasMessageContaining("非负");
  }
  @Test void actualFrontendTemplateAndExportRoundTrip() throws Exception {
    String directory=System.getProperty("inquiry.workbook.evidence");
    org.junit.jupiter.api.Assumptions.assumeTrue(directory!=null);
    for(String name:List.of("template.xlsx","export.xlsx")) {
      var parsed=new InquiryWorkbookParser().parse(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(directory,name)),name,
          List.of(new InquiryWorkbookParser.Factor("AVG|1#CU","1#电解铜","Cu")));
      assertThat(parsed.rows()).hasSize(3);
      assertThat(parsed.rows()).allSatisfy(rule->{assertThat(rule.materialCode()).isEmpty();assertThat(rule.supplierName()).isEmpty();assertThat(rule.supplierCode()).isEmpty();});
      assertThat(parsed.rows().get(1).compiled()).isNotNull();
      assertThat(parsed.rows().get(2).range().bands()).hasSize(3);
      assertThat(parsed.rows().get(2).range().price(new BigDecimal("100000"))).isEqualByComparingTo("2.4");
    }
  }
}
