package com.sanhua.marketingcost.service.pricing.household;

import static org.assertj.core.api.Assertions.*;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HouseholdInquiryWorkbookParserTest {
  static final String[] HEADERS={"料号","名称","规格","年用量\n（万）","报价总成本\n（含两项费用、不含运费）","铜重\n（克）","锌重\n（克）","不锈钢重\n（克）","铝重（克）","304不锈钢重\n（克）","316L不锈钢重\n（克）","碳钢重\n（克）","白银重\n（克）","两项费用比例\n%\t","制造费用比例\n%\t","价格类型","价格是否\n含模具费\t","模具费（元）","备注"};
  static HSSFWorkbook workbook() {
    var book=new HSSFWorkbook();var sheet=book.createSheet("10月线圈询价");
    sheet.createRow(0).createCell(1).setCellValue("明细表2");
    var header=sheet.createRow(1); for(int c=0;c<HEADERS.length;c++) header.createCell(c).setCellValue(HEADERS[c]);
    var row=sheet.createRow(2);row.createCell(0).setCellValue("001Ab");row.createCell(1).setCellValue("线圈");row.createCell(2).setCellValue("SPEC");
    row.createCell(4).setCellValue("20.9853000000000");row.createCell(13).setCellValue(8.5);row.createCell(17).setCellValue("999999");
    return book;
  }
  static byte[] bytes(Workbook book) throws Exception { try(book;var stream=new ByteArrayOutputStream()){book.write(stream);return stream.toByteArray();} }
  static HouseholdInquiryWorkbookParser.WorkbookData parse(Workbook book) throws Exception { return new HouseholdInquiryWorkbookParser().parse(bytes(book),"synthetic.xls"); }

  @Test void novemberWorkbookReadsOriginalInquiryAndRateByHeaderNotTheExampleCustomerColumn() throws Exception {
    byte[] source=getClass().getResourceAsStream("/household-inquiry/real-20261107.xlsx").readAllBytes();
    var parsed=new HouseholdInquiryWorkbookParser().parse(source,"家用询价单2026.11.7.xlsx");
    assertThat(parsed.errors()).isEmpty();
    assertThat(parsed.rows()).hasSize(125);assertThat(parsed.duplicates()).hasSize(1);
    assertThat(parsed.rows().stream().filter(value->value.materialCode()==null)).hasSize(8);
    var repeated=parsed.rows().stream().filter(value->value.materialCode()==null && "DPF-TS1010-053".equals(value.specification())).findFirst().orElseThrow();
    assertThat(repeated.sourceRows()).containsExactly(124,127);
    assertThat(repeated.cells()).anyMatch(cell->cell.cell().equals("D124") && cell.value().equals("3"));
    assertThat(repeated.cells()).anyMatch(cell->cell.cell().equals("D127") && cell.value().equals("1"));
    var row=parsed.rows().stream().filter(value->"208200575".equals(value.materialCode())).findFirst().orElseThrow();
    assertThat(row.unitPrice()).isEqualTo("18.9343");
    assertThat(row.householdRate()).isEqualTo("0.085");
    assertThat(row.financialReference().get("F").value()).isEqualTo("57.4");
    assertThat(row.financialReference().get("G").value()).isEqualTo("0.8");
    assertThat(row.financialReference().get("W").formula()).isEqualTo("E3+(($W$1-$V$1)*F3/1000+($W$2-V2)*G3/1000)/1.13");
    assertThat(new BigDecimal(row.financialReference().get("W").value())).isCloseTo(new BigDecimal("20.4631096903637"),within(new BigDecimal("0.000000000001")));
  }

  @Test void readsOnlyTheActualSeventeenRowsAgainstIndependentOracle() throws Exception {
    byte[] source=getClass().getResourceAsStream("/household-inquiry/real-20261023.xls").readAllBytes();
    var actual=new HouseholdInquiryWorkbookParser().parse(source,"original.xls");
    var expected=new ObjectMapper().readTree(getClass().getResourceAsStream("/household-inquiry/real-20261023-expected.json"));
    assertThat(actual.sha256()).isEqualTo("b2a5b10999dde0a22ed8d291916ac4c523f3f7f0199511f265397c155a953d22");
    assertThat(actual.errors()).isEmpty();assertThat(actual.rows()).hasSize(17);assertThat(actual.duplicates()).isEmpty();
    for(int i=0;i<17;i++) {
      var row=actual.rows().get(i);var oracle=expected.get(i);
      assertThat(row.materialCode()).isEqualTo(oracle.get("material").asText());
      assertThat(row.materialName()).isEqualTo(oracle.get("name").asText());
      assertThat(row.specification()).isEqualTo(oracle.get("specification").asText());
      assertThat(row.unitPrice()).isEqualTo(oracle.get("price").asText());
      assertThat(row.householdRate()).isEqualTo(oracle.get("rate").asText());
      assertThat(row.sourceRows()).containsExactly(oracle.get("row").asInt());
      assertThat(row.cells()).hasSize(19); assertThat(row.financialReference()).hasSize(14);
    }
  }
  @ParameterizedTest @CsvSource({"8.5,false,0.085","8.5%,false,0.085","0.085,true,0.085","0.085,false,0.00085","0,false,0","99999999.9999999999,false,999999.999999999999"})
  void normalizesPercentageEncodingOnceWithoutGuessingMagnitude(String value,boolean percentFormat,String expected) throws Exception {
    var book=workbook();var cell=book.getSheetAt(0).getRow(2).getCell(13);
    if(value.endsWith("%")) cell.setCellValue(value);else cell.setCellValue(Double.parseDouble(value));
    if(value.startsWith("999")) cell.setCellValue(value);
    if(percentFormat){var style=book.createCellStyle();style.setDataFormat(book.createDataFormat().getFormat("0.00%"));cell.setCellStyle(style);}
    var parsed=parse(book);assertThat(parsed.errors()).isEmpty();assertThat(parsed.rows().getFirst().householdRate()).isEqualTo(expected);
    assertThat(parsed.rows().getFirst().unitPrice()).isEqualTo("20.9853");
  }
  @ParameterizedTest @CsvSource({"4,-1","4,1.0000000000001","4,1000000000000","13,-8.5","13,8.5000000000001","13,100000000","4,NaN","13,abc"})
  void invalidFinancialInputsHaveExactRowAndColumnAndDoNotProduceAValidRow(int column,String input) throws Exception {
    var book=workbook();book.getSheetAt(0).getRow(2).getCell(column).setCellValue(input);
    var result=parse(book);assertThat(result.rows()).isEmpty();assertThat(result.errors()).singleElement().satisfies(error -> {
      assertThat(error.excelRow()).isEqualTo(3);assertThat(error.column()).isEqualTo(column==4?"E":"N");
      assertThat(error.message()).isNotBlank();
    });
  }
  @ParameterizedTest @CsvSource({"4,E","13,N"})
  void missingRequiredCellsAreErrorsEvenWithOtherReferencesPresent(int column,String name) throws Exception {
    var book=workbook();book.getSheetAt(0).getRow(2).removeCell(book.getSheetAt(0).getRow(2).getCell(column));
    var result=parse(book);assertThat(result.errors()).singleElement().satisfies(error -> assertThat(error.column()).isEqualTo(name));
  }
  @Test void missingCodeUsesSpecificationButOtherCellErrorsAndMissingIdentityStillReject() throws Exception {
    for(String value:List.of("", "#N/A", "NA")) {
      var book=workbook();book.getSheetAt(0).getRow(2).getCell(0).setCellValue(value);
      var parsed=parse(book);assertThat(parsed.errors()).isEmpty();assertThat(parsed.rows().getFirst().materialCode()).isNull();
    }
    var noIdentity=workbook();noIdentity.getSheetAt(0).getRow(2).getCell(0).setBlank();noIdentity.getSheetAt(0).getRow(2).getCell(2).setBlank();
    assertThat(parse(noIdentity).errors()).singleElement().satisfies(error->assertThat(error.column()).isEqualTo("A/C"));
    var bad=workbook();bad.getSheetAt(0).getRow(2).getCell(0).setCellErrorValue(FormulaError.VALUE.getCode());
    assertThat(parse(bad).errors()).isNotEmpty();
  }
  @Test void missingCodeDuplicateWithDifferentPriceOrMetalWeightCannotBeChosenArbitrarily() throws Exception {
    var book=workbook();var sheet=book.getSheetAt(0);sheet.getRow(2).getCell(0).setBlank();
    var next=sheet.createRow(3);next.createCell(0).setBlank();next.createCell(1).setCellValue("线圈");next.createCell(2).setCellValue("SPEC");
    next.createCell(4).setCellValue(99);next.createCell(13).setCellValue(8.5);
    assertThat(parse(book).errors()).singleElement().satisfies(error->assertThat(error.column()).isEqualTo("C"));
  }
  @ParameterizedTest @CsvSource({"4,1e-2147483647","13,1e-2147483647","4,1e2147483647","13,1e2147483647","4,100e2147483647","13,100e2147483647"})
  void extremeExponentsAreRowErrorsRatherThanAnUncaughtArithmeticFailure(int column,String value) throws Exception {
    var book=workbook();book.getSheetAt(0).getRow(2).getCell(column).setCellValue(value);
    assertThat(parse(book).errors()).singleElement().satisfies(e -> assertThat(e.column()).isEqualTo(column==4?"E":"N"));
  }

  @Test void paddedNumericMaterialsPreserveFormattingAndTextCase() throws Exception {
    var book=workbook();var row=book.getSheetAt(0).getRow(2);row.getCell(0).setCellValue(1234);
    var style=book.createCellStyle();style.setDataFormat(book.createDataFormat().getFormat("00000000"));row.getCell(0).setCellStyle(style);
    assertThat(parse(book).rows().getFirst().materialCode()).isEqualTo("00001234");
    assertThat(parse(workbook()).rows().getFirst().materialCode()).isEqualTo("001Ab");
  }
  @ParameterizedTest @CsvSource({"123.5","1234567890123456"})
  void ambiguousNumericMaterialsAreRejected(String number) throws Exception {
    var book=workbook();book.getSheetAt(0).getRow(2).getCell(0).setCellValue(Double.parseDouble(number));
    assertThat(parse(book).errors()).singleElement().satisfies(e -> assertThat(e.column()).isEqualTo("A"));
  }
  @Test void identicalDuplicatesMergeAllCoordinatesButReferenceConflictsRejectTheBatch() throws Exception {
    var book=workbook();var sheet=book.getSheetAt(0);var duplicate=sheet.createRow(3);for(var cell:sheet.getRow(2)){var c=duplicate.createCell(cell.getColumnIndex());if(cell.getCellType()==CellType.STRING)c.setCellValue(cell.getStringCellValue());else c.setCellValue(cell.getNumericCellValue());}
    var same=parse(book);assertThat(same.rows()).hasSize(1);assertThat(same.rows().getFirst().sourceRows()).containsExactly(3,4);assertThat(same.duplicates()).hasSize(1);
    book=workbook();sheet=book.getSheetAt(0);var second=sheet.createRow(3);
    for(var cell:sheet.getRow(2)){var c=second.createCell(cell.getColumnIndex());if(cell.getCellType()==CellType.STRING)c.setCellValue(cell.getStringCellValue());else c.setCellValue(cell.getNumericCellValue());}
    second.getCell(17).setCellValue("888");var conflict=parse(book);assertThat(conflict.errors()).hasSize(1);assertThat(conflict.errors().getFirst().column()).isEqualTo("A");
  }
  @Test void suppliedFormulaCacheIsUsedAndItsFormulaRemainsEvidence() throws Exception {
    var book=workbook();var cell=book.getSheetAt(0).getRow(2).getCell(4);cell.setCellFormula("1+1");cell.setCellValue(77.125);
    var row=parse(book).rows().getFirst();assertThat(row.unitPrice()).isEqualTo("77.125");
    assertThat(row.cells().get(4).formula()).isEqualTo("1+1");
  }
  @Test void missingFormulaCacheIsRejectedWithoutEvaluatingTheWorkbook() throws Exception {
    var book=new org.apache.poi.xssf.usermodel.XSSFWorkbook();var source=workbook();var sheet=book.createSheet("10月线圈询价");
    for(var row:source.getSheetAt(0)){var target=sheet.createRow(row.getRowNum());for(var cell:row){var c=target.createCell(cell.getColumnIndex());if(cell.getCellType()==CellType.STRING)c.setCellValue(cell.getStringCellValue());else c.setCellValue(cell.getNumericCellValue());}}
    source.close();sheet.getRow(2).getCell(4).setCellFormula("1+1");
    assertThat(parse(book).errors()).singleElement().satisfies(e -> assertThat(e.column()).isEqualTo("E"));
  }
  @Test void titleHeadersAndWorksheetMustAllMatchTheExplicitSchema() throws Exception {
    var renamed=workbook();renamed.setSheetName(0,"other");assertThat(parse(renamed).errors()).isEmpty();
    var ambiguous=workbook();ambiguous.cloneSheet(0);assertThatThrownBy(() -> parse(ambiguous)).hasMessageContaining("仅有一个");
    var title=workbook();title.getSheetAt(0).getRow(0).getCell(1).setCellValue("unrelated");assertThatThrownBy(() -> parse(title)).hasMessageContaining("标题");
    var header=workbook();header.getSheetAt(0).getRow(1).getCell(13).setCellValue("两项费用比例");assertThatThrownBy(() -> parse(header)).hasMessageContaining("N2");
  }
  @Test void blankRowsAreSkippedAndAnEmptyWorkbookCannotPublish() throws Exception {
    var book=workbook();book.getSheetAt(0).createRow(3);assertThat(parse(book).errors()).isEmpty();
    book=workbook();book.getSheetAt(0).removeRow(book.getSheetAt(0).getRow(2));assertThat(parse(book).errors()).isNotEmpty();
  }
}
