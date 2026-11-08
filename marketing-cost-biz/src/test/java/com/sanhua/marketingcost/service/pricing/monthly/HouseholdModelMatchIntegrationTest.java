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
  @Test void modelAndSpecificationMatchWithoutInventingSourceCodesAndCodeAlwaysWins()throws Exception {
    String material=code(),spec="MODEL-"+UUID.randomUUID(),month="2070-11";finance(month,1,"90","22");
    long missing=inquiry(null,spec,month,"18.9343");master(material,"COMMERCIAL",spec,"OTHER");
    var request=requestFor(material,month,"210",null);var adopted=adopt(request);var old=adopted.selections().get(request);
    assertThat(jdbc.queryForObject("SELECT material_code FROM lp_household_inquiry_price WHERE id=?",String.class,missing)).isNull();
    assertThat(jdbc.queryForObject("SELECT material_code FROM lp_material_monthly_price WHERE id=?",String.class,old.monthlyPriceId())).isEqualTo(material);
    var snapshot=codec.decode(codec.wrap(old.fact().evidence().json()),HouseholdMonthlyPriceSource.Snapshot.class);
    assertThat(snapshot.match().matchedValue()).isEqualTo(spec);assertThat(snapshot.match().requestedMaterialCode()).isEqualTo(material);
    assertThat(old.applicability().fragments()).allMatch(fragment->fragment.organizations().equals(Set.of("210")));
    long exact=inquiry(material,"DIFFERENT SPEC",month,"21");
    var replaced=adopt(request).selections().get(request);assertThat(replaced.fact().price()).isEqualByComparingTo("21");
    assertThat(replaced.monthlyPriceId()).isNotEqualTo(old.monthlyPriceId());
    assertThat(priceStorage.readByIds("COMMERCIAL",Set.of(replaced.monthlyPriceId())).getFirst().householdSourceId()).isEqualTo(exact);
    assertThat(priceStorage.readByIds("COMMERCIAL",Set.of(old.monthlyPriceId())).getFirst().evidence()).isEqualTo(old.fact().evidence());
    String specOnly=code();master(specOnly,"COMMERCIAL","OTHER",spec);
    assertThat(candidate(requestFor(specOnly,month,"210",null)).fact().price()).isEqualByComparingTo("18.9343");
    assertThat(candidate(requestFor(code(),month,"210",spec)).fact().price()).isEqualByComparingTo("18.9343");
    assertThat(household.read(context("COMMERCIAL",month,"220"),Set.of(requestFor(specOnly,month,"220",null))).values()).isEmpty();
    String foreign=code(),onlyCodedSpec="EXPLICIT-"+UUID.randomUUID();inquiry(code(),onlyCodedSpec,month,"99");master(foreign,"COMMERCIAL",onlyCodedSpec,null);
    assertThat(household.read(context("COMMERCIAL",month,"210"),Set.of(requestFor(foreign,month,"210",null))).values()).isEmpty();
  }
  @Test void modelSourceMonthUpdatePreservesHistoryAndRejectsStaleAdoption()throws Exception {
    String material=code(),spec="SPEC-"+UUID.randomUUID(),nov="2071-11",dec="2071-12";finance(nov,1,"90","22");finance(dec,1,"100","24");
    long first=inquiry(null,spec,nov,"18.9343");var request=requestFor(material,dec,"210",spec);var old=adopt(request).selections().get(request);
    assertThat(codec.decode(codec.wrap(old.fact().evidence().json()),HouseholdMonthlyPriceSource.Snapshot.class).metalInputs().financial().months().sourceMonth()).isEqualTo(dec);
    long december=inquiry(null,spec,dec,"21");
    assertThatThrownBy(()->operation.adopt(context("COMMERCIAL",dec,"210"),planFor(request,old))).isInstanceOf(MonthlyPriceProblem.class);
    var current=adopt(request).selections().get(request);assertThat(current.fact().price()).isEqualByComparingTo("21");
    inquiry(null,spec,nov,"17");assertThat(adopt(request).selections().get(request).monthlyPriceId()).isEqualTo(current.monthlyPriceId());
    assertThat(inquiries.current("COMMERCIAL",spec,dec,1,20).rows().getFirst().id()).isEqualTo(december);
    assertThat(inquiries.historyById("COMMERCIAL",first,1,20).total()).isEqualTo(3);
    assertThat(priceStorage.readByIds("COMMERCIAL",Set.of(old.monthlyPriceId())).getFirst().unitPrice()).isEqualByComparingTo("18.9343");
    var stale=candidate(requestFor(code(),dec,"210",spec));String target=code();var targetRequest=requestFor(target,dec,"210",spec);
    inquiry(target,"EXACT",dec,"30");
    assertThatThrownBy(()->operation.adopt(context("COMMERCIAL",dec,"210"),planFor(targetRequest,stale))).isInstanceOf(MonthlyPriceProblem.class);
  }
  @Test void conflictingModelAndSpecificationMatchesAreReportedAndNeverPickedArbitrarily()throws Exception {
    String material=code(),model="MODEL-"+UUID.randomUUID(),spec="SPEC-"+UUID.randomUUID(),month="2072-11";
    finance(month,1,"90","22");inquiry(null,model,month,"20");inquiry(null,spec,month,"21");master(material,"COMMERCIAL",model,spec);
    var request=requestFor(material,month,"210",null);var response=household.read(context("COMMERCIAL",month,"210"),Set.of(request));
    assertThat(response.values()).isEmpty();assertThat(response.problems().get(request).message()).contains("多个来源");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?",Integer.class,material)).isZero();
  }
  @Test void modelMatchedCostSavesOriginalWAndCustomerDifferenceWithRealBomMaterial()throws Exception {
    String material=code(),spec="COST-"+UUID.randomUUID(),month="2073-11";finance(month,1,"90","22");inquiry(null,spec,month,"18.9343");master(material,"COMMERCIAL",spec,null);
    var request=requestFor(material,month,"210",null);var adopted=adopt(request);
    var customer=CustomerMaterialPrices.copperAndZinc(new BigDecimal("120000"),new BigDecimal("28000"));
    String financeNo="FIN-"+UUID.randomUUID(),customerNo="OA-"+UUID.randomUUID();
    var version=versionService.createRunning("MODEL-"+UUID.randomUUID(),1L,"TOP",month,month,financeNo,"COMMERCIAL");
    version.setFinancePricePrepareNo(financeNo);version.setOaPricePrepareNo(customerNo);versionMapper.updateById(version);
    long fin=prepare(version,request,financeNo,"root/HH",new BigDecimal("2.5"),new BigDecimal("18.9343"));
    long quote=prepare(version,request,customerNo,"root/HH",new BigDecimal("2.5"),new BigDecimal("20.462441592920353982"));
    var zero=BigDecimal.ZERO;var ordinary=new Ordinary(List.of(),zero,zero,zero,zero,zero,new BigDecimal("1.05"),zero,new BigDecimal(".20"),zero,zero);
    var saved=costing.calculateAndSaveMaterials("COMMERCIAL",version.getId(),adopted,ordinary,null,customer,Map.of("root/HH",new PreparationBinding(fin,quote)));
    assertThat(saved.version().getCuMaterialAdjustment()).isEqualByComparingTo("4.524567");
    var restored=costing.read("COMMERCIAL",version.getId());assertThat(restored.snapshot()).isEqualTo(saved.snapshot());
    var sheet=new com.sanhua.marketingcost.service.jjb.JjbProjection(new com.fasterxml.jackson.databind.ObjectMapper()).project(restored,new com.sanhua.marketingcost.entity.OaForm(),new com.sanhua.marketingcost.entity.OaFormItem(),List.of());
    var row=sheet.rows().stream().filter(value->value.kind().equals("HOUSEHOLD")).findFirst().orElseThrow();
    assertThat(row.code()).isEqualTo(material);assertThat(row.householdCalculation()).containsEntry("calculatedW","20.462441592920353982");
    assertThat(row.householdCalculation().get("originalWFormula")).contains("$W$1-$V$1");
  }
}
