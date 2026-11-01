package com.sanhua.marketingcost.service.pricing.invoice;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

public class InvoiceWorkbookParserTest {
  public static byte[] workbook(String sheet,String[][] values) throws Exception {
    try (var book=new XSSFWorkbook();var out=new ByteArrayOutputStream()) {
      var page=book.createSheet(sheet);var header=page.createRow(0);
      var names=List.of("单据编号","立账供应商\n.供应商编码","立账供应商","应付单表行\n.料品.料号",
          "应付单表行.料品\n.料品名称","应付单表行\n.料品.料品.规格","计量单位","应付单表行.\n未税单价","币种");
      for(int i=0;i<names.size();i++)header.createCell(i).setCellValue(names.get(i));
      for(int i=0;i<values.length;i++){var row=page.createRow(i+1);for(int j=0;j<values[i].length;j++)row.createCell(j).setCellValue(values[i][j]);}
      book.write(out);return out.toByteArray();
    }
  }
  public static String[] row(String code,String document,String supplier,String price) {
    return new String[]{document,supplier,"供应商",code,"料件","规格","千克",price,"人民币"};
  }
  @Test void arbitrarySheetAndExactIdentifiersAndPrecision() throws Exception {
    var parsed=new InvoiceWorkbookParser().parse(workbook("任意名字11月",new String[][]{row("001Aa","DOC-01","0007","80.38555556")}));
    assertThat(parsed.rows()).hasSize(1);var price=parsed.rows().getFirst();
    assertThat(price.materialCode()).isEqualTo("001Aa");assertThat(price.supplierCode()).isEqualTo("0007");
    assertThat(price.unitPrice()).isEqualByComparingTo("80.38555556");assertThat(price.currency()).isEqualTo("CNY");
  }
  @Test void deduplicatesOnlyTheThreePartIdentityAndKeepsMaximumInBatch() throws Exception {
    var parsed=new InvoiceWorkbookParser().parse(workbook("发票",new String[][]{
        row("A","D1","S1","80"),row("A","D1","S1","85"),row("A","D2","S1","90"),row("A","D1","S2","75")}));
    assertThat(parsed.rows()).hasSize(3);assertThat(parsed.duplicateCount()).isEqualTo(1);
    assertThat(parsed.rows().getFirst().unitPrice()).isEqualByComparingTo("85");
  }
  @Test void rejectsIncomparableDuplicateAndInvalidAmounts() throws Exception {
    var second=row("A","D1","S1","85");second[6]="件";
    var bytes=workbook("重复",new String[][]{row("A","D1","S1","80"),second});
    assertThatThrownBy(()->new InvoiceWorkbookParser().parse(bytes)).hasMessageContaining("不同单位或币种");
    for(String value:List.of("-1","NaN","0.0000000000000000001")) {
      var invalid=workbook("校验",new String[][]{row("A","D1","S1",value)});
      assertThatThrownBy(()->new InvoiceWorkbookParser().parse(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(new InvoiceWorkbookParser().parse(workbook("零价",new String[][]{row("A","D1","S1","0")})).rows().getFirst().unitPrice()).isZero();
  }
  @Test void readsAllMatchingSheetsAndIgnoresCoverSheet() throws Exception {
    try(var book=new XSSFWorkbook();var input=new XSSFWorkbook(new java.io.ByteArrayInputStream(workbook("原表",new String[][]{row("A","D1","S1","80")})));var out=new ByteArrayOutputStream()) {
      book.createSheet("说明").createRow(0).createCell(0).setCellValue("发票价格说明");
      for(String name:List.of("甲","乙")) {
        var target=book.createSheet(name);
        for(var row:input.getSheetAt(0)){var copy=target.createRow(row.getRowNum());for(var cell:row)copy.createCell(cell.getColumnIndex()).setCellValue(cell.getStringCellValue());}
        if(name.equals("乙"))target.getRow(1).getCell(0).setCellValue("D2");
      }
      book.write(out);var parsed=new InvoiceWorkbookParser().parse(out.toByteArray());
      assertThat(parsed.sheets()).containsExactly("甲","乙");assertThat(parsed.rows()).hasSize(2);
    }
  }
}
