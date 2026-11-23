package com.sanhua.marketingcost.service.workbook;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.settlement.SettlementWorkbookParser;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorWorkbookParser;
import com.sanhua.marketingcost.service.pricing.invoice.InvoiceWorkbookParser;
import com.sanhua.marketingcost.service.pricing.inquiry.InquiryWorkbookParser;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryWorkbookParser;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialWorkbookParser;
import com.sanhua.marketingcost.service.scrap.ScrapPriceWorkbookParser;
import com.sanhua.marketingcost.service.supplier.SupplierSupplyRatioWorkbook;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Runs against downloads made by the Vue workbook generator, never imports into a database. */
@EnabledIfSystemProperty(named="workbook.fixture.dir",matches=".+")
class DownloadedWorkbookContractTest {
  byte[] fixture(String kind) throws Exception {
    var bytes=Files.readAllBytes(Path.of(System.getProperty("workbook.fixture.dir"),kind+"-filled.xlsx"));
    // Excel recalculates/saves a filled template. POI performs the same step for this test.
    try(var book=WorkbookFactory.create(new ByteArrayInputStream(bytes));var out=new ByteArrayOutputStream()) {
      book.getCreationHelper().createFormulaEvaluator().evaluateAll(); book.write(out);return out.toByteArray();
    }
  }
  @Test void settlementPreservesFormulaWeightsAndLeadingZero() throws Exception {
    var result=new SettlementWorkbookParser().parse(fixture("settlement"),"结算.xlsx");
    assertThat(result.rules()).hasSize(1);var row=result.rules().getFirst();
    assertThat(row.materialCode()).isEqualTo("001234");assertThat(row.givenPrice()).isEqualByComparingTo("10.6022");
    assertThat(row.formula().markupRatio()).isEqualByComparingTo("1.05");
  }
  @Test void linkedRetainsExcelFormulaAndFinanceFactorBindings() throws Exception {
    var keys=new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl());
    var result=new OriginalLinkedWorkbookParser(keys).parse(fixture("linked"),"联动.xlsx","COMMERCIAL",Set.of("AVG|1#CU","AVG|美国柜装黄铜"));
    assertThat(result.errors()).isEmpty();assertThat(result.rules()).hasSize(1);
    assertThat(result.rules().getFirst().identity().materialCode()).isEqualTo("001234");
    assertThat(result.rules().getFirst().originalPrice()).isBetween(new java.math.BigDecimal("9.55"),new java.math.BigDecimal("9.56"));
  }
  @Test void rangeReadsBothSheetsAndTwoActualBands() throws Exception {
    var result=new OriginalRangeWorkbookParser().parse(fixture("range"),"区间.xlsx","COMMERCIAL");
    assertThat(result.errors()).isEmpty();assertThat(result.rules()).hasSize(1);assertThat(result.rules().getFirst().bands()).hasSize(2);
  }
  @Test void invoiceAndInquiryUseUnchangedParsers() throws Exception {
    var invoice=new InvoiceWorkbookParser().parse(fixture("invoice"));assertThat(invoice.rows()).hasSize(1);assertThat(invoice.rows().getFirst().materialCode()).isEqualTo("001234");
    var inquiry=new InquiryWorkbookParser().parse(fixture("inquiry"),"询价.xlsx",List.of());assertThat(inquiry.rows()).hasSize(1);assertThat(inquiry.rows().getFirst().originalPrice()).isEqualByComparingTo("2.25");
  }
  @Test void householdPreservesPercentUnitAndOriginalPrice() throws Exception {
    var result=new HouseholdInquiryWorkbookParser().parse(fixture("household"),"家用.xlsx");
    assertThat(result.errors()).isEmpty();assertThat(result.rows()).hasSize(1);assertThat(result.rows().getFirst().householdRate()).isEqualTo("0.085");
  }
  @Test void supplierAllowsActualPercentageWithoutLosingIdentity() throws Exception {
    var result=new SupplierSupplyRatioWorkbookParserImpl().parse(new ByteArrayInputStream(fixture("supplier")),"供货.xlsx",null);
    assertThat(result.getErrors()).isEmpty();assertThat(result.getRows()).hasSize(1);
    assertThat(result.getRows().getFirst().getMaterialCode()).isEqualTo("001234");
    assertThat(result.getRows().getFirst().getSpecModel()).isEqualTo("MODEL");
    assertThat(result.getRows().getFirst().getSupplyRatio()).isEqualByComparingTo("0.25");
  }
  @Test void supplierTemplateAndListExportShareBackendHeadersAndInstructionsAndRoundTrip() throws Exception {
    for (String suffix : List.of("blank", "filled", "data")) {
      var bytes=Files.readAllBytes(Path.of(System.getProperty("workbook.fixture.dir"),"supplier-"+suffix+".xlsx"));
      try(var book=WorkbookFactory.create(new ByteArrayInputStream(bytes));var expected=new org.apache.poi.xssf.usermodel.XSSFWorkbook()) {
        assertThat(book.getSheetAt(0).getSheetName()).isEqualTo(SupplierSupplyRatioWorkbook.SHEET_NAME);
        var header=book.getSheetAt(0).getRow(0);
        assertThat(java.util.stream.IntStream.range(0,header.getLastCellNum())
            .mapToObj(i->header.getCell(i).getStringCellValue()).toList()).isEqualTo(SupplierSupplyRatioWorkbook.HEADERS);
        SupplierSupplyRatioWorkbook.addInstructions(expected);
        var actualHelp=book.getSheet("填写说明");var expectedHelp=expected.getSheet("填写说明");
        assertThat(actualHelp.getLastRowNum()).isEqualTo(expectedHelp.getLastRowNum());
        var formatter=new org.apache.poi.ss.usermodel.DataFormatter();
        for(int r=0;r<=expectedHelp.getLastRowNum();r++)for(int c=0;c<3;c++)
          assertThat(formatter.formatCellValue(actualHelp.getRow(r).getCell(c)))
              .as("%s instructions row %s column %s",suffix,r,c)
              .isEqualTo(formatter.formatCellValue(expectedHelp.getRow(r).getCell(c)));
      }
      if(suffix.equals("data")) {
        var parsed=new SupplierSupplyRatioWorkbookParserImpl().parse(new ByteArrayInputStream(bytes),"列表.xlsx",null);
        assertThat(parsed.getErrors()).isEmpty();assertThat(parsed.getRows()).hasSize(3);
        assertThat(parsed.getRows().get(0).getSupplyRatio()).isZero();
        assertThat(parsed.getRows().get(1).getSupplyRatio()).isNull();
        assertThat(parsed.getRows().get(1).getSelectionRule()).isEqualTo("无供货比率，取最高价");
        assertThat(parsed.getRows().get(2).getSupplyRatio()).isEqualByComparingTo("0.000001");
      }
    }
  }
  @Test void plateAndScrapContractsRemainCompatible() throws Exception {
    var plate=new PlateMaterialWorkbookParser().parse(fixture("plate"),"板片.xlsx");assertThat(plate.errors()).isEmpty();assertThat(plate.rows()).hasSize(1);
    var scrap=new ScrapPriceWorkbookParser(new ObjectMapper()).parse(fixture("scrap"),"废料.xlsx");assertThat(scrap.errors()).isEmpty();assertThat(scrap.rows()).hasSize(1);
  }
  @Test void scrapTemplateAcceptsOriginalAndEditedFormulaReferences() throws Exception {
    var parser = new ScrapPriceWorkbookParser(new ObjectMapper());
    var linked = parser.parse(fixture("scrap-linked"), "废料联动.xlsx");
    assertThat(linked.errors()).isEmpty();
    assertThat(linked.rows()).hasSize(1);
    var original = linked.rows().getFirst().rule();
    assertThat(original.materialCode()).isEqualTo("001234");
    assertThat(original.sourceFormula()).isEqualTo("$B$1*0.8/1.13");
    assertThat(original.factorBindings()).containsExactlyEntriesOf(Map.of("Cu", "AVG|1#CU"));

    var edited = parser.parse(fixture("scrap-edited"), "废料联动改引用.xlsx");
    assertThat(edited.errors()).isEmpty();
    assertThat(edited.rows()).hasSize(1);
    assertThat(edited.rows().getFirst().rule().sourceFormula()).isEqualTo("$B$2*0.7/1.13");
    assertThat(edited.rows().getFirst().rule().factorBindings())
        .containsExactlyEntriesOf(Map.of("Brass", "AVG|美国柜装黄铜"));
  }
  @ParameterizedTest @ValueSource(strings={"cmsPlan","cmsWorkshop","cmsSubject","cmsSetting","cmsScrap"})
  void cmsUsesActualRequiredHeadersAndUnits(String kind) throws Exception {
    var parser=new CmsCostExcelParseServiceImpl();var input=new ByteArrayInputStream(fixture(kind));
    var result=switch(kind) {
      case "cmsPlan" -> parser.parsePlanCost(input);
      case "cmsWorkshop" -> parser.parseWorkshopLabor(input);
      case "cmsSubject" -> parser.parseProductSubjectCost(input);
      case "cmsSetting" -> parser.parseSubjectSetting(input);
      default -> parser.parseMaterialScrapRef(input);
    };
    assertThat(result.getErrors()).isEmpty();assertThat(result.getRows()).hasSize(1);
  }
  @ParameterizedTest @ValueSource(strings={"financeCommercial","financePlate"})
  void financeKeepsOrganizationAndSchemes(String kind) throws Exception {
    var keys=new FactorCanonicalKeyServiceImpl(new PriceLinkedType2TextNormalizerImpl());
    var result=new FinanceFactorWorkbookParser(keys).parse(fixture(kind),"财务.xlsx",kind.equals("financeCommercial")?"210":"220",null);
    assertThat(result.problems()).isEmpty();assertThat(result.values()).hasSize(kind.equals("financeCommercial")?1:2);
  }
  @Test void u9HeaderContractsKeepAllColumnsAndDuplicateDepartmentPositions() throws Exception {
    for(String kind:List.of("u9material","u9byproduct"))try(var book=WorkbookFactory.create(Files.newInputStream(Path.of(System.getProperty("workbook.fixture.dir"),kind+"-blank.xlsx")))){
      var header=book.getSheetAt(0).getRow(0);
      if(kind.equals("u9material"))for(var mapping:U9MaterialMasterFieldContract.fieldMappings())assertThat(header.getCell(mapping.excelColumn()-1).getStringCellValue()).isEqualTo(mapping.header());
      else { assertThat(header.getLastCellNum()).isEqualTo((short)18);assertThat(book.getSheetAt(0).getSheetName()).isEqualTo("BOM母项"); }
    }
  }
}
