package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-03 真实类型2 Excel 原始解析")
class PriceLinkedType2WorkbookParserRealExcelTest {

  private static final Path TYPE2_SAMPLE = Path.of(
      "/Users/xiexicheng/Desktop/price/采购价表二次开发导入模板-股份251115联动价格导入类型2.xls");

  private final PriceLinkedType2WorkbookParserImpl parser =
      new PriceLinkedType2WorkbookParserImpl(new PriceLinkedWorkbookTypeDetectorImpl());

  @Test
  @DisplayName("真实文件能够读取 Cu、Zn、产品、公式缓存和标准原始行")
  void parsesRealType2Workbook() throws Exception {
    assertThat(Files.exists(TYPE2_SAMPLE)).as("真实类型2样例存在").isTrue();
    byte[] bytes = Files.readAllBytes(TYPE2_SAMPLE);

    PriceLinkedType2WorkbookParseResult result = parser.parse(
        new ByteArrayInputStream(bytes), TYPE2_SAMPLE.getFileName().toString());

    assertThat(result.getBusinessSheetName()).isNotBlank();
    assertThat(result.getStandardSheetName()).isNotBlank();
    assertThat(result.getFactorRows()).anySatisfy(factor -> {
      assertThat(factor.getShortName()).isEqualToIgnoringCase("1#Cu");
      assertThat(factor.getPrice()).isEqualByComparingTo("90");
      assertThat(factor.getPriceCellRef()).isNotBlank();
    });
    assertThat(result.getFactorRows()).anySatisfy(factor -> {
      assertThat(factor.getShortName()).isEqualToIgnoringCase("1#Zn");
      assertThat(factor.getPrice()).isEqualByComparingTo("21.68");
      assertThat(factor.getPriceCellRef()).isNotBlank();
    });
    assertThat(result.getProductRows()).isNotEmpty();
    assertThat(result.getProductRows()).anySatisfy(row -> {
      assertThat(row.getMaterialCode()).isEqualTo("109910977");
      assertThat(row.getSupplierName()).isEqualTo("浙江华亿");
      assertThat(row.getTaxIncludedFormula()).isNotBlank();
      assertThat(row.getFormulaCellRef()).isNotBlank();
      assertThat(row.getTaxIncludedPrice()).isNotNull();
      assertThat(row.getReferencedCells()).isNotEmpty();
    });
    assertThat(result.getStandardRows()).isNotEmpty();
    assertThat(result.getStandardRows()).allSatisfy(row -> {
      assertThat(row.getMaterialCode()).isNotBlank().doesNotContainIgnoringCase("E");
      assertThat(row.getSupplierName()).isNotBlank();
      assertThat(row.getSupplierCode()).isNotBlank();
      assertThat(row.getCells()).isNotEmpty();
    });
    assertThat(result.getErrors()).isEmpty();
  }
}
