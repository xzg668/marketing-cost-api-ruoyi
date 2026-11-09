package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.pricing.monthly.MonthlyCostVersionIntegrationTest.*;
import static com.sanhua.marketingcost.service.pricing.monthly.HouseholdMonthlyDifferenceIntegrationTest.finance;
import com.sanhua.marketingcost.service.pricing.CustomerMaterialPrices;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.PublishRequest;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.PreparationBinding;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost.Ordinary;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;

@Tag("integration")
class HouseholdModelMatchIntegrationTest {
  @BeforeAll static void start() throws Exception {
    database();
    jdbc.execute("CREATE TABLE IF NOT EXISTS lp_material_master_raw(id BIGINT PRIMARY KEY AUTO_INCREMENT,material_code VARCHAR(64),organization_code VARCHAR(32),active_flag INT,material_model VARCHAR(255),material_spec VARCHAR(512))");
  }
  @AfterAll static void finish(){stop();}
  static long inquiry(String material,String specification,String month,String price)throws Exception {
    try(var input=HouseholdModelMatchIntegrationTest.class.getResourceAsStream("/household-inquiry/real-20261107.xlsx");
        var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(input);var bytes=new java.io.ByteArrayOutputStream()) {
      var sheet=book.getSheetAt(0);for(int i=sheet.getLastRowNum();i>2;i--)if(sheet.getRow(i)!=null)sheet.removeRow(sheet.getRow(i));
      var row=sheet.getRow(2);if(material==null)row.getCell(0).setCellErrorValue(org.apache.poi.ss.usermodel.FormulaError.NA.getCode());else row.getCell(0).setCellValue(material);
      row.getCell(2).setCellValue(specification);row.getCell(4).setCellValue(price);book.write(bytes);
      var preview=inquiries.preview("COMMERCIAL","test",bytes.toByteArray(),"controlled-model-source.xlsx",month);
      assertThat(preview.content().workbook().errors()).isEmpty();
      return inquiries.publish("COMMERCIAL","test",new PublishRequest(preview.previewId(),preview.publishRequestId(),preview.previewFingerprint())).rows().getFirst().versionId();
    }
  }
  static void master(String code,String org,String model,String spec) {
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,organization_code,active_flag,material_model,material_spec) VALUES(?,?,1,?,?)",code,org,model,spec);
  }
  static MonthlyPriceRequest requestFor(String code,String month,String org,String specification) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(java.time.YearMonth.parse(month),"COMMERCIAL",code,specification,"件","CNY",TaxBasis.EXCLUDED,null),org,NonCuConditions.empty(),BigDecimal.ONE);
  }
  static Selection candidate(MonthlyPriceRequest request) {
    var values=household.read(context("COMMERCIAL",request.family().month().toString(),request.priceOrgCode()),Set.of(request));
    assertThat(values.problems()).isEmpty();assertThat(values.values()).containsKey(request);return values.values().get(request).selection();
  }
  static MonthlyAdoptionPlan planFor(MonthlyPriceRequest request,Selection selected) {
    return new MonthlyAdoptionPlan(Map.of(request,selected),List.of(new Use("root/HH",request,new BigDecimal("2.5"))),Set.of());
  }
  static MonthlyAdoptionPlan adopt(MonthlyPriceRequest request) {
    return operation.adopt(context("COMMERCIAL",request.family().month().toString(),request.priceOrgCode()),planFor(request,candidate(request))).plan();
  }
  static MonthlyPriceRequest specificationRequest(String month,String specification) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(java.time.YearMonth.parse(month),"COMMERCIAL","",
        specification,"支","CNY",TaxBasis.EXCLUDED,null,true),"210",NonCuConditions.empty(),BigDecimal.ONE);
  }
  @Test void aCodedBomNeverBorrowsASpecificationPriceAndItsOwnCodeIgnoresDisplayFields() throws Exception {
    String material=code(),spec="SPEC-"+UUID.randomUUID(),month="2070-11";finance(month,1,"90","22");
    inquiry(null,spec,month,"18.9343");master(material,"COMMERCIAL",spec,spec);
    var coded=requestFor(material,month,"210",spec);
    assertThat(household.read(context("COMMERCIAL",month,"210"),Set.of(coded)).values()).isEmpty();
    var anonymous=specificationRequest(month,spec);var old=adopt(anonymous).selections().get(anonymous);
    assertThat(old.fact().price()).isEqualByComparingTo("18.9343");
    assertThat(priceStorage.readByIds("COMMERCIAL",Set.of(old.monthlyPriceId())).getFirst().family().materialCode()).isEmpty();
    inquiry(material,"DIFFERENT SPEC",month,"21");
    assertThat(candidate(coded).fact().price()).isEqualByComparingTo("21");
    assertThat(adopt(anonymous).selections().get(anonymous).monthlyPriceId()).isEqualTo(old.monthlyPriceId());
  }
  @Test void specificationPriceUpdatesPreserveHistoryAndRejectStaleAdoption() throws Exception {
    String spec="SPEC-"+UUID.randomUUID(),nov="2071-11",dec="2071-12";finance(nov,1,"90","22");finance(dec,1,"100","24");
    long first=inquiry(null,spec,nov,"18.9343");var request=specificationRequest(dec,spec);var old=adopt(request).selections().get(request);
    long december=inquiry(null,spec,dec,"21");
    assertThatThrownBy(()->operation.adopt(context("COMMERCIAL",dec,"210"),planFor(request,old))).isInstanceOf(MonthlyPriceProblem.class);
    var current=adopt(request).selections().get(request);assertThat(current.fact().price()).isEqualByComparingTo("21");
    inquiry(null,spec,nov,"17");assertThat(adopt(request).selections().get(request).monthlyPriceId()).isEqualTo(current.monthlyPriceId());
    assertThat(inquiries.current("COMMERCIAL",spec,dec,1,20).rows().getFirst().id()).isEqualTo(december);
    assertThat(inquiries.historyById("COMMERCIAL",first,1,20).total()).isEqualTo(3);
    assertThat(priceStorage.readByIds("COMMERCIAL",Set.of(old.monthlyPriceId())).getFirst().unitPrice()).isEqualByComparingTo("18.9343");
  }
  @Test void noCodeMatchesTheBomSpecificationWithoutModelExpansion() throws Exception {
    String material=code(),model="MODEL-"+UUID.randomUUID(),spec="SPEC-"+UUID.randomUUID(),month="2072-11";
    finance(month,1,"90","22");inquiry(null,model,month,"20");inquiry(null,spec,month,"21");master(material,"COMMERCIAL",model,spec);
    assertThat(candidate(specificationRequest(month,spec)).fact().price()).isEqualByComparingTo("21");
    assertThat(household.read(context("COMMERCIAL",month,"210"),Set.of(requestFor(material,month,"210",null))).values()).isEmpty();
  }
  @Test void savedSpecificationPriceReplaysCustomerCopperAndZincDifference() throws Exception {
    String spec="COST-"+UUID.randomUUID(),month="2073-11";finance(month,1,"90","22");inquiry(null,spec,month,"18.9343");
    var request=specificationRequest(month,spec);var adopted=adopt(request);var selection=adopted.selections().get(request);
    var restored=operation.readByIds("COMMERCIAL",Set.of(selection.monthlyPriceId())).get(selection.monthlyPriceId());
    var pair=MonthlyScenarioTestSupport.create(null).calculateMaterials(Map.of(request,restored),CustomerMaterialPrices.copperAndZinc(new BigDecimal("120000"),new BigDecimal("28000")),Map.of()).get(selection.monthlyPriceId());
    assertThat(pair.financialPrice()).isEqualByComparingTo("18.9343");
    assertThat(pair.customerPrice()).isEqualByComparingTo("20.462441592920353982");
    assertThat(restored).isEqualTo(selection);
  }
}
