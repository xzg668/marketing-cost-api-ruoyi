package com.sanhua.marketingcost.service.jjb;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.dto.jjb.*;
import java.util.*;
import org.apache.poi.ss.usermodel.CellType;
import org.junit.jupiter.api.Test;

class JjbCalculationExcelTest {
  @Test void substitutesOnlyWholeVariablesAndRetainsTaxAndNegativeValues() {
    assertThat(JjbPriceCalculation.substitute("[cu]+[cu2]*2+[cu]",Map.of("cu","0","cu2","-2.5"),true))
        .isEqualTo("((0)+(-2.5)*2+(0))/1.13");
    assertThat(JjbPriceCalculation.substitute("[unknown]+1",Map.of(),false)).isEqualTo("[unknown]+1");
  }
  @Test void addsReadableNumericDetailsWithoutChangingMainWorksheet() throws Exception {
    var base=JjbExcelRendererTest.model(1,0,0);
    var detail=new JjbCostExplanation(List.of(new JjbCostExplanation.Term("制造成本","10×1","10")),List.of(
        new JjbCostExplanation.Material("000123","铜件","2.5","件",List.of(new JjbCostExplanation.Price("000123","联动价 · 原稿.xlsx · 第8行","铜 100 元/公斤","铜 92 元/公斤","基准：100×0.1=10\n客户：92×0.1=9.2")),
            "基准：10×2.5=25\n客户：9.2×2.5=23\n差异：23−25=-2","25","23","-2")),"来自保存版本");
    var m=new JjbReadModel(base.identity(),base.header(),base.rows(),base.monthlyPrices(),base.totals(),base.warnings(),detail);
    try(var original=JjbExcelRendererTest.render(base);var book=JjbExcelRendererTest.render(m)) {
      assertThat(book.getSheetAt(0).getCTWorksheet().xmlText()).isEqualTo(original.getSheetAt(0).getCTWorksheet().xmlText());
      var sheet=book.getSheet("成本计算明细");
      var code=JjbExcelRendererTest.find(sheet,"000123\n铜件");assertThat(code.getCellType()).isEqualTo(CellType.STRING);
      var row=sheet.getRow(code.getRowIndex());assertThat(row.getCell(8).getNumericCellValue()).isEqualTo(-2);
      assertThat(row.getCell(5).getStringCellValue()).contains("100×0.1=10","92×0.1=9.2");
      assertThat(sheet.getRow(JjbExcelRendererTest.find(sheet,"材料成本差异").getRowIndex()).getCell(3).getStringCellValue()).contains("(-2)");
    }
  }
  @Test void historicalMissingEvidenceStaysExplicitWithoutInventingZeroInputs() throws Exception {
    try(var book=JjbExcelRendererTest.render(JjbExcelRendererTest.model(0,0,0))) {
      var sheet=book.getSheet("成本计算明细");
      assertThat(JjbExcelRendererTest.find(sheet,"此历史版本未保存逐项计算依据，以下为原保存总额。")).isNotNull();
      assertThat(sheet.getRow(JjbExcelRendererTest.find(sheet,"材料成本差异").getRowIndex()).getCell(8).getNumericCellValue()).isEqualTo(-8);
    }
  }
}
