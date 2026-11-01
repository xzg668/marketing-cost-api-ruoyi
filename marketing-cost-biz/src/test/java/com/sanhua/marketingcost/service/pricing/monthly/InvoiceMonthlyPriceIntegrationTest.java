package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static com.sanhua.marketingcost.service.pricing.invoice.InvoiceWorkbookParserTest.*;
import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.pricing.invoice.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

@Tag("integration")
class InvoiceMonthlyPriceIntegrationTest {
  static InvoicePriceService imports;static MonthlyInvoicePriceSource source;static MonthlyMaterialPriceService operation;
  static MonthlyPriceDocumentCodec codec;
  static MonthlyPriceStorage storage;
  @BeforeAll static void start() throws Exception {
    MonthlyFixedPriceSourceIntegrationTest.database();var ds=MonthlyFixedPriceSourceIntegrationTest.jdbc.getDataSource();
    codec=new MonthlyPriceDocumentCodec(new ObjectMapper());
    imports=new InvoicePriceService(MonthlyFixedPriceSourceIntegrationTest.jdbc,new InvoiceWorkbookParser(),new ObjectMapper(),new DataSourceTransactionManager(ds));
    source=new MonthlyInvoicePriceSource(MonthlyFixedPriceSourceIntegrationTest.jdbc,codec);
    var config=new org.apache.ibatis.session.Configuration();config.setMapUnderscoreToCamelCase(true);
    config.addMapper(MonthlyPriceStorageMapper.class);config.addMapper(MonthlyPriceLinkMapper.class);
    var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);factory.setConfiguration(config);
    var sql=new SqlSessionTemplate(factory.getObject());
    storage=new MonthlyPriceStorage(sql.getMapper(MonthlyPriceStorageMapper.class),codec,sql.getMapper(MonthlyPriceLinkMapper.class));
    operation=new MonthlyMaterialPriceService(storage,new DataSourceTransactionManager(ds),codec,(context,requests,dependencies)->source.read(context,requests));
  }
  @AfterAll static void stop(){MonthlyFixedPriceSourceIntegrationTest.stop();}
  static Context context(){return new Context("COMMERCIAL","INV-TEST",1L,"TOP",YearMonth.of(2026,10),LocalDateTime.of(2026,10,31,0,0),"fixture","210");}
  static MonthlyPriceRequest request(String code){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,10),"COMMERCIAL",code,null,"千克","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);}
  static void publish(String[][] rows) throws Exception {imports.publish("COMMERCIAL","210","fixture",UUID.randomUUID().toString(),"发票.xlsx",workbook("随时可改名",rows));}
  static MonthlyAdoptionPlan plan(MonthlyPriceRequest request,Selection value){return new MonthlyAdoptionPlan(Map.of(request,value),List.of(new Use("top/part",request,BigDecimal.ONE)),Set.of());}
  static Selection adopt(MonthlyPriceRequest request){var value=operation.resolveCandidates(context(),Set.of(request)).values().get(request).selection();return operation.adopt(context(),plan(request,value),Duration.ofSeconds(2)).plan().selections().get(request);}

  @Test void sameMonthNewLowerInvoiceAppendsPriceAndOldHistoryRemainsExact() throws Exception {
    String code="INV-"+UUID.randomUUID();var r=request(code);
    publish(new String[][]{row(code,"D1","S1","85")});var first=adopt(r);
    publish(new String[][]{row(code,"D2","S1","80.38555556"),row(code,"D3","S1","81")});
    assertThat(operation.lookup(Set.of(r)).missing()).contains(r);
    var next=adopt(r);assertThat(next.fact().price()).isEqualByComparingTo("81");assertThat(next.monthlyPriceId()).isNotEqualTo(first.monthlyPriceId());
    assertThat(operation.readByIds("COMMERCIAL",Set.of(first.monthlyPriceId())).get(first.monthlyPriceId())).isEqualTo(first);
    assertThat(operation.lookup(Set.of(r)).values().get(r)).isEqualTo(next);
    assertThat(MonthlyFixedPriceSourceIntegrationTest.jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?",Integer.class,code)).isEqualTo(2);
    assertThat(next.fact().evidence().json()).contains("INVOICE","LATEST_IMPORT_THEN_HIGHEST","invoiceRevision","D3");
  }
  @Test void unrelatedMaterialImportDoesNotInvalidateAnAdoptedInvoice() throws Exception {
    String code="INV-"+UUID.randomUUID();var r=request(code);publish(new String[][]{row(code,"D1","S1","80")});var saved=adopt(r);
    publish(new String[][]{row(code+"B","D2","S1","99")});assertThat(adopt(r)).isEqualTo(saved);
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"ODS_ORDER","INQUIRY"})
  void newlyAvailableHigherPriorityPriceCanReplaceALowerSourceAfterInvoiceImport(String lowerSource) throws Exception {
    String code="INV-"+UUID.randomUUID();var r=request(code);
    var kind=new java.util.concurrent.atomic.AtomicReference<>(lowerSource);
    var chain=new MonthlyMaterialPriceService(storage,new DataSourceTransactionManager(MonthlyFixedPriceSourceIntegrationTest.jdbc.getDataSource()),codec,
        (context,requests,dependencies)->new Candidates(Map.of(r,new Candidate(new Selection(null,
            new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any()),
            PriceFact.normal(new BigDecimal("90"),SourceEvidence.ofJson("{\"sourceKind\":\""+kind.get()+"\",\"source\":{\"selected\":{\"id\":1}}}"),Set.of())),null)),Map.of()));
    var old=chain.adopt(context(),plan(r,chain.resolveCandidates(context(),Set.of(r)).values().get(r).selection())).plan().selections().get(r);
    publish(new String[][]{row(code,"D1","S1","80")});kind.set("FIXED");
    var fixed=chain.adopt(context(),plan(r,chain.resolveCandidates(context(),Set.of(r)).values().get(r).selection())).plan().selections().get(r);
    assertThat(fixed.monthlyPriceId()).isNotEqualTo(old.monthlyPriceId());
    assertThat(fixed.fact().evidence().json()).contains("FIXED").doesNotContain("invoiceRevision");
    publish(new String[][]{row(code,"D2","S1","70")});
    assertThat(chain.lookup(Set.of(r)).values().get(r)).isEqualTo(fixed);
    assertThat(chain.readByIds("COMMERCIAL",Set.of(old.monthlyPriceId())).get(old.monthlyPriceId())).isEqualTo(old);
  }
  @Test void staleCandidateCannotBeAdoptedAfterNewImport() throws Exception {
    String code="INV-"+UUID.randomUUID();var r=request(code);publish(new String[][]{row(code,"D1","S1","80")});
    var stale=operation.resolveCandidates(context(),Set.of(r)).values().get(r).selection();
    publish(new String[][]{row(code,"D2","S1","85")});
    assertThatThrownBy(()->operation.adopt(context(),plan(r,stale),Duration.ofSeconds(2))).isInstanceOf(MonthlyPriceProblem.class).hasMessageContaining("发票来源");
    assertThat(adopt(r).fact().price()).isEqualByComparingTo("85");
  }
  @Test void unitCurrencySupplierAndOrganizationAreAppliedBeforeHighestPrice() throws Exception {
    String code="INV-"+UUID.randomUUID();var dollars=row(code,"D3","S1","999");dollars[8]="USD";
    var pieces=row(code,"D4","S1","888");pieces[6]="件";
    publish(new String[][]{row(code,"D1","S1","80"),row(code,"D2","S2","85"),dollars,pieces});
    var r=request(code);assertThat(source.read(context(),Set.of(r)).values().get(r).selection().fact().price()).isEqualByComparingTo("85");
    var requested=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(r.family().month(),"COMMERCIAL",code,null,"千克","CNY",TaxBasis.EXCLUDED,"S1"),"210",NonCuConditions.empty(),BigDecimal.ONE);
    assertThat(source.read(context(),Set.of(requested)).values().get(requested).selection().fact().price()).isEqualByComparingTo("80");
    var otherOrg=new MonthlyPriceRequest(r.family(),"220",NonCuConditions.empty(),BigDecimal.ONE);
    assertThat(source.read(context(),Set.of(otherOrg)).problems().get(otherOrg).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.MISSING);
  }
  @Test void concurrentFirstUseAfterAnImportConvergesOnOneNewVersion() throws Exception {
    String code="INV-"+UUID.randomUUID();var r=request(code);publish(new String[][]{row(code,"D1","S1","80")});var before=adopt(r);
    publish(new String[][]{row(code,"D2","S1","85")});
    try(var workers=java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var futures=new ArrayList<java.util.concurrent.Future<Selection>>();
      for(int i=0;i<4;i++)futures.add(workers.submit(()->{
        for(int attempt=0;;attempt++)try{return adopt(r);}catch(MonthlyPriceProblem retry){if(attempt>=5)throw retry;}
      }));
      var ids=new HashSet<Long>();for(var future:futures)ids.add(future.get(15,java.util.concurrent.TimeUnit.SECONDS).monthlyPriceId());
      assertThat(ids).hasSize(1).doesNotContain(before.monthlyPriceId());
      assertThat(MonthlyFixedPriceSourceIntegrationTest.jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?",Integer.class,code)).isEqualTo(2);
    }
  }
}
