package com.sanhua.marketingcost.service.pricing.monthly;
import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.pricing.monthly.MonthlyCostVersionIntegrationTest.*;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.PreparationBinding;
import com.sanhua.marketingcost.service.pricing.household.HouseholdInquiryModels.PublishRequest;
import com.sanhua.marketingcost.service.financefactor.FinanceMonthlyPriceRefreshService;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
@Tag("integration")
class HouseholdMonthlyDifferenceIntegrationTest {
  @BeforeAll static void start() throws Exception { database(); }
  @AfterAll static void finish(){stop();}
  static long finance(String month,int revision,String copper,String zinc) {
    String no=UUID.randomUUID().toString();
    jdbc.update("INSERT INTO lp_factor_upload_batch(batch_no,import_type,price_month,business_unit_type,file_name,file_sha256,content_hash,uploaded_by,status,price_org_code,publication_revision,default_scheme_code,published_at) VALUES(?,'FINANCE_BASE',?,'COMMERCIAL','household-base.xlsx',?,?,'fixture','PUBLISHED','210',?,'DEFAULT',NOW())",no,month,"a".repeat(64),"b".repeat(64),revision);
    long batch=jdbc.queryForObject("SELECT id FROM lp_factor_upload_batch WHERE batch_no=?",Long.class,no);
    for(var factor:Map.of(PriceFactorContext.COPPER,copper,PriceFactorContext.ZINC,zinc).entrySet()) {
      String seq=UUID.randomUUID().toString(),key=factor.getKey();
      jdbc.update("INSERT INTO lp_factor_identity(business_unit_type,factor_seq_no,factor_name,short_name,price_source,canonical_factor_key) VALUES('COMMERCIAL',?,?,?,'AVG',?)",seq,key,key,key);
      long identity=jdbc.queryForObject("SELECT id FROM lp_factor_identity WHERE factor_seq_no=?",Long.class,seq);
      jdbc.update("INSERT INTO lp_factor_monthly_price(factor_identity_id,price_month,price,tax_included,source_upload_batch_id,status,price_org_code,scheme_code,condition_code,revision_no,unit,published_at) VALUES(?,?,?,1,?,'PUBLISHED','210','DEFAULT','GENERAL',?,'CNY/KG',NOW())",identity,month,d(factor.getValue()),batch,revision);
      long price=jdbc.queryForObject("SELECT id FROM lp_factor_monthly_price WHERE factor_identity_id=?",Long.class,identity);
      jdbc.update("INSERT INTO lp_factor_row_ref(factor_upload_batch_id,source_sheet_name,source_row_number,factor_identity_id,factor_monthly_price_id,source_column_number,source_cell_ref,scheme_code,condition_code,source_value_text,source_unit,source_number_format,conversion_basis) VALUES(?,'商用基价',?,?,?,4,?,'DEFAULT','GENERAL',?,'CNY/KG','0.000000','IDENTITY')",batch,key.equals(PriceFactorContext.COPPER)?3:4,identity,price,key.equals(PriceFactorContext.COPPER)?"D3":"D4",factor.getValue());
    }
    return batch;
  }
  static long inquiry(String material,String month,String value) throws Exception {
    try(var stream=HouseholdMonthlyDifferenceIntegrationTest.class.getResourceAsStream("/household-inquiry/real-20261107.xlsx");
        var book=org.apache.poi.ss.usermodel.WorkbookFactory.create(stream);var bytes=new java.io.ByteArrayOutputStream()) {
      var sheet=book.getSheetAt(0);for(int index=sheet.getLastRowNum();index>2;index--)if(sheet.getRow(index)!=null)sheet.removeRow(sheet.getRow(index));
      sheet.getRow(2).getCell(0).setCellValue(material);sheet.getRow(2).getCell(4).setCellValue(value);book.write(bytes);
      var preview=inquiries.preview("COMMERCIAL","test",bytes.toByteArray(),"controlled-single-row.xlsx",month);
      assertThat(preview.content().workbook().errors()).isEmpty();
      return inquiries.publish("COMMERCIAL","test",new PublishRequest(preview.previewId(),preview.publishRequestId(),preview.previewFingerprint())).rows().getFirst().versionId();
    }
  }
  static MonthlyPriceRequest r(String material,String month){return request(material,"COMMERCIAL",month,"210",NonCuConditions.empty());}
  static Selection candidate(String material,String month){var candidates=operation.resolveCandidates(context("COMMERCIAL",month,"210"),Set.of(r(material,month)));assertThat(candidates.problems()).isEmpty();return candidates.values().get(r(material,month)).selection();}
  static MonthlyAdoptionPlan hhPlan(String material,String month,Selection value){return new MonthlyAdoptionPlan(Map.of(r(material,month),value),List.of(new Use("root/HH",r(material,month),d("2.5"))),Set.of());}
  static MonthlyAdoptionPlan adopt(String material,String month){return operation.adopt(context("COMMERCIAL",month,"210"),hhPlan(material,month,candidate(material,month))).plan();}
  @Test void decemberCarryOwnMonthAndBackdatedImportKeepOriginalInquiryAndHistoricalFacts() throws Exception {
    String material=code();finance("2065-11",1,"90","20.8310629161179");finance("2065-12",1,"100","24");
    long original=inquiry(material,"2065-11","18.9343");
    var nov=adopt(material,"2065-11");var dec=adopt(material,"2065-12");
    var np=nov.selections().get(r(material,"2065-11"));var dp=dec.selections().get(r(material,"2065-12"));
    assertThat(dp.fact().price()).isEqualByComparingTo(np.fact().price());assertThat(dp.monthlyPriceId()).isNotEqualTo(np.monthlyPriceId());
    var snapshot=codec.decode(codec.wrap(dp.fact().evidence().json()),HouseholdMonthlyPriceSource.Snapshot.class);
    assertThat(snapshot.householdSourceId()).isEqualTo(original);assertThat(snapshot.metalInputs().financial().months().sourceMonth()).isEqualTo("2065-12");
    var customer=CustomerMaterialPrices.copperAndZinc(d("120000"),d("28000"));
    assertThat(MonthlyScenarioTestSupport.create(null).calculateMaterials(dec.selections(),customer,Map.of()).get(dp.monthlyPriceId()).customerPrice()).isEqualByComparingTo("19.953061061946902655");
    long december=inquiry(material,"2065-12","21");
    var newDec=adopt(material,"2065-12").selections().get(r(material,"2065-12"));
    assertThat(newDec.monthlyPriceId()).isNotEqualTo(dp.monthlyPriceId());assertThat(newDec.fact().price()).isEqualByComparingTo("21");
    inquiry(material,"2065-11","17");
    assertThat(candidate(material,"2065-12").monthlyPriceId()).isEqualTo(newDec.monthlyPriceId());
    assertThat(inquiries.current("COMMERCIAL",material,"2065-12",1,20).rows().getFirst().id()).isEqualTo(december);
    assertThat(inquiries.current("COMMERCIAL",material,"2065-10",1,20).rows()).isEmpty();
    assertThat(priceStorage.readByIds("COMMERCIAL",Set.of(dp.monthlyPriceId())).getFirst().evidence()).isEqualTo(dp.fact().evidence());
  }
  @Test void financialRefreshReplacesBasisWithoutRewritingEAndRejectsStaleCandidates() throws Exception {
    String material=code(),month="2066-12";finance(month,1,"90","22");inquiry(material,"2066-11","18.9343");
    var old=adopt(material,month).selections().get(r(material,month));
    transaction.executeWithoutResult(tx->{long batch=finance(month,2,"100","24");new FinanceMonthlyPriceRefreshService(jdbc,codec).refresh(new FinanceFactorImportContext("COMMERCIAL","210",month,null,null),batch);});
    var updated=adopt(material,month).selections().get(r(material,month));
    assertThat(updated.monthlyPriceId()).isNotEqualTo(old.monthlyPriceId());assertThat(updated.fact().price()).isEqualByComparingTo("18.9343");
    assertThat(priceStorage.readByIds("COMMERCIAL",Set.of(old.monthlyPriceId())).getFirst().evidence()).isEqualTo(old.fact().evidence());
    String fresh=code();inquiry(fresh,"2066-11","18.9343");var stale=candidate(fresh,month);inquiry(fresh,month,"21");
    assertThatThrownBy(()->operation.adopt(context("COMMERCIAL",month,"210"),hhPlan(fresh,month,stale))).isInstanceOf(MonthlyPriceProblem.class);
    assertThat(adopt(fresh,month).selections().get(r(fresh,month)).fact().price()).isEqualByComparingTo("21");
  }
  @Test void customerWAndAllInputsSurvivePreparationFinalCostAndReadWithoutOverwritingSharedP0() throws Exception {
    String material=code(),month="2067-11";finance(month,1,"90","20.8310629161179");inquiry(material,month,"18.9343");
    var adopted=adopt(material,month);long id=adopted.selections().get(r(material,month)).monthlyPriceId();
    for(var data:List.of(new String[]{"120000","27774.7505548238","20.463109690363685593","4.702675"},new String[]{null,null,"18.9343","0"},new String[]{"80000",null,"18.426335398230088496","-1.562518"})) {
      var customer=CustomerMaterialPrices.copperAndZinc(data[0]==null?null:d(data[0]),data[1]==null?null:d(data[1]));
      String financeNo="FIN-"+UUID.randomUUID(),customerNo="OA-"+UUID.randomUUID();
      var version=versionService.createRunning("HH-"+UUID.randomUUID(),1L,"TOP",month,month,financeNo,"COMMERCIAL");
      version.setFinancePricePrepareNo(financeNo);version.setOaPricePrepareNo(customerNo);versionMapper.updateById(version);
      long fin=prepare(version,r(material,month),financeNo,"root/HH",d("2.5"),d("18.9343"));
      long quote=prepare(version,r(material,month),customerNo,"root/HH",d("2.5"),d(data[2]));
      var zero=BigDecimal.ZERO;
      var ordinary=new Ordinary(List.of(),zero,zero,zero,zero,zero,d("1.05"),zero,d(".25"),zero,zero);
      var saved=costing.calculateAndSaveMaterials("COMMERCIAL",version.getId(),adopted,ordinary,null,customer,Map.of("root/HH",new PreparationBinding(fin,quote)));
      assertThat(saved.version().getCuMaterialAdjustment()).isEqualByComparingTo(data[3]);
      assertThat(saved.version().getFinalQuoteAmount()).isEqualByComparingTo(saved.version().getTotalCost().add(d(data[3])));
      var reread=costing.read("COMMERCIAL",version.getId());assertThat(reread.snapshot()).isEqualTo(saved.snapshot());
      var pair=reread.snapshot().priceScenarios().getFirst();assertThat(pair.customerPrice()).isEqualByComparingTo(data[2]);
      var evidence=codec.decode(codec.wrap(pair.customerEvidenceJson()),com.fasterxml.jackson.databind.JsonNode.class);
      assertThat(evidence.path("householdCalculation").path("calculatedW").asText()).isNotBlank();
      assertThat(evidence.path("originalW").path("formula").asText()).contains("$W$1-$V$1");
      var sheet=new com.sanhua.marketingcost.service.jjb.JjbProjection(new com.fasterxml.jackson.databind.ObjectMapper())
          .project(reread,new com.sanhua.marketingcost.entity.OaForm(),new com.sanhua.marketingcost.entity.OaFormItem(),List.of());
      var audit=sheet.rows().stream().filter(row->row.kind().equals("HOUSEHOLD")).findFirst().orElseThrow().householdCalculation();
      assertThat(audit).containsEntry("baseCopper","90")
          .containsEntry("customerCopper",data[0]==null?"90":d(data[0]).movePointLeft(3).stripTrailingZeros().toPlainString())
          .containsEntry("calculatedW",d(data[2]).stripTrailingZeros().toPlainString());
      assertThat(priceStorage.readByIds("COMMERCIAL",Set.of(id)).getFirst().unitPrice()).isEqualByComparingTo("18.9343");
    }
  }

  record SharedFixture(com.sanhua.marketingcost.entity.QuoteCostRunVersion version,
      com.sanhua.marketingcost.service.costing.MonthlyQuotationCosting.MaterialInputs prepared,
      Map<String,PreparationBinding> bindings) {}
  SharedFixture sharedFixture(String material,String month,Selection selected,String path,CustomerMaterialPrices customer) {
    String financeNo="FIN-"+UUID.randomUUID(),customerNo="OA-"+UUID.randomUUID();
    var version=versionService.createRunning("HH-SHARED-"+UUID.randomUUID(),1L,"TOP",month,month,financeNo,"COMMERCIAL");
    version.setFinancePricePrepareNo(financeNo);version.setOaPricePrepareNo(customerNo);versionMapper.updateById(version);
    var plan=new MonthlyAdoptionPlan(Map.of(r(material,month),selected),List.of(new Use(path,r(material,month),d("2.5"))),Set.of());
    var pair=MonthlyScenarioTestSupport.create(null).calculateMaterials(plan.selections(),customer,Map.of()).get(selected.monthlyPriceId());
    var value=new QuotationPriceSession.Value(r(material,month),selected,pair.financialPrice(),pair.customerPrice());
    var snapshot=codec.compact(codec.encode(MonthlyPreparationPrice.of(value,path,d("2.5"))
        .withInputs(MonthlyPreparationInputs.captureDirect(plan,path))));
    long fin=prepare(version,r(material,month),financeNo,path,d("2.5"),pair.financialPrice());
    long quote=prepare(version,r(material,month),customerNo,path,d("2.5"),pair.customerPrice());
    for(long id:List.of(fin,quote)) {
      var row=prepareMapper.selectById(id);row.setResultRefType(MonthlyPreparationPrice.KIND);
      row.setInputSnapshotJson(snapshot);prepareMapper.updateById(row);
    }
    var facade=new com.sanhua.marketingcost.service.costing.MonthlyQuotationCosting(prepareMapper,costing,codec,
        new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules());
    return new SharedFixture(version,facade.materials(version),Map.of(path,new PreparationBinding(fin,quote)));
  }
  @Test void sharedHouseholdCostUsesExactWAndSavesEachConsumersOwnSourceWhileRejectingDifferentW() throws Exception {
    String material=code(),month="2068-11";finance(month,1,"90","20.8310629161179");inquiry(material,month,"18.9343");
    var selection=adopt(material,month).selections().get(r(material,month));
    var customer=CustomerMaterialPrices.copperAndZinc(d("120000"),d("27774.7505548238"));
    var a=sharedFixture(material,month,selection,"root/A",customer);
    var b=sharedFixture(material,month,selection,"root/B",customer);
    var zero=BigDecimal.ZERO;var ordinary=new Ordinary(List.of(),zero,zero,zero,zero,zero,d("1.05"),zero,d(".25"),zero,zero);
    var resolved=com.sanhua.marketingcost.service.costing.MonthlyProductCost.resolve(a.prepared().plan(),ordinary,null,a.prepared().householdCustomerPrices());
    var numbers=com.sanhua.marketingcost.service.costing.MonthlyProductCost.calculate(resolved.inputs());
    for(var fixture:List.of(a,b)) {
      var saved=costing.saveCalculatedMaterials("COMMERCIAL",fixture.version().getId(),fixture.prepared().plan(),ordinary,
          null,customer,fixture.bindings(),numbers);
      assertThat(saved.version().getOaNo()).isEqualTo(fixture.version().getOaNo());
      assertThat(saved.snapshot().calculation().household().getFirst().paths()).containsExactlyElementsOf(fixture.bindings().keySet());
      assertThat(saved.snapshot().calculation().household().getFirst().effectiveCustomerPrice()).isEqualByComparingTo("20.463109690363685593");
      assertThat(saved.version().getCuMaterialAdjustment()).isEqualByComparingTo("4.702675");
      assertThat(costing.read("COMMERCIAL",fixture.version().getId()).snapshot()).isEqualTo(saved.snapshot());
    }
    var changedCustomer=CustomerMaterialPrices.copperAndZinc(d("80000"),null);
    var changed=sharedFixture(material,month,selection,"root/C",changedCustomer);
    assertThatThrownBy(()->costing.saveCalculatedMaterials("COMMERCIAL",changed.version().getId(),changed.prepared().plan(),ordinary,
        null,changedCustomer,changed.bindings(),numbers)).hasMessageContaining("inputs differ");
    assertThat(versionMapper.selectById(changed.version().getId()).getStatus()).isEqualTo("RUNNING");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_part_item WHERE cost_run_version_id=?",Long.class,changed.version().getId())).isZero();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_cost_run_cost_item WHERE cost_run_version_id=?",Long.class,changed.version().getId())).isZero();
  }
}
