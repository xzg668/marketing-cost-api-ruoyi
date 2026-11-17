package com.sanhua.marketingcost.service.pricing.inquiry;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.mapper.monthly.*;
import com.sanhua.marketingcost.service.MaterialPricePriorityService;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.utility.MountableFile;

@Tag("integration")
class MaterialSpecialPricePolicyIntegrationTest {
  static final InquiryDatabaseTestSupport db=new InquiryDatabaseTestSupport();
  static MonthlyPriceDocumentCodec codec;
  static MonthlyMaterialPriceService prices;
  static MaterialSpecialPricePolicies policies;
  static MonthlyInquiryPriceSource inquiry;
  @BeforeAll static void start() throws Exception {
    db.start();db.jdbc.execute("CREATE TABLE lp_quote_tech_data_version(id BIGINT PRIMARY KEY)");
    new ResourceDatabasePopulator(new ClassPathResource("monthly-pricing/fixed-source-schema.sql"),new ClassPathResource("monthly-pricing/pre-v342-schema.sql")).execute(db.jdbc.getDataSource());
    db.jdbc.execute("CREATE TABLE lp_material_master_raw(id BIGINT AUTO_INCREMENT PRIMARY KEY,organization_code VARCHAR(32),material_code VARCHAR(64),material_name VARCHAR(128),material_model VARCHAR(128),material_spec VARCHAR(128),active_flag INT DEFAULT 1)");
    db.jdbc.execute("""
        CREATE TABLE lp_material_price_type(id BIGINT AUTO_INCREMENT PRIMARY KEY,business_unit_type VARCHAR(32),period CHAR(7),
          price_org_code VARCHAR(32),material_code VARCHAR(64),material_name VARCHAR(128),material_spec VARCHAR(128),material_model VARCHAR(128),
          price_type VARCHAR(32),priority INT,source VARCHAR(32),source_system VARCHAR(16),manual_priority INT,
          priority_updated_by BIGINT,priority_updated_at DATETIME(6),created_at DATETIME(6),updated_at DATETIME(6))
        """);
    for(String migration:List.of("V342__monthly_material_price_storage.sql","V344__monthly_material_price_single_table.sql",
        "V354__invoice_price_import.sql","V360__finance_correction_monthly_price.sql","V364__household_inquiry_header_schema.sql",
        "V365__household_monthly_source_revision.sql","V372__monthly_price_disable.sql","V366__household_model_specification_match.sql",
        "V387__material_special_price_policy.sql")) {
      db.mysql.copyFileToContainer(MountableFile.forClasspathResource("db/"+migration),"/tmp/special-policy.sql");
      var result=db.mysql.execInContainer("sh","-c","MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql -uroot --default-character-set=utf8mb4 inquiry_pricing_test < /tmp/special-policy.sql");
      assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
    for(var mapper:List.of(MonthlyFixedSourceMapper.class,MonthlySupplierInputMapper.class,MonthlyPriceStorageMapper.class,MonthlyPriceLinkMapper.class))db.sql.getConfiguration().addMapper(mapper);
    codec=new MonthlyPriceDocumentCodec(db.json);policies=new MaterialSpecialPricePolicies(db.jdbc,codec);
    inquiry=new MonthlyInquiryPriceSource(db.jdbc,db.repository,db.sql.getMapper(MonthlySupplierInputMapper.class),new SupplierPriceSelectionService(null,null),codec);
    var sources=new MonthlyPriceSources(null,null,new MonthlyFixedPriceSource(db.sql.getMapper(MonthlyFixedSourceMapper.class),db.sql.getMapper(MonthlySupplierInputMapper.class),new SupplierPriceSelectionService(null,null),codec),null,null,null,null,null,null,null,null,null,null,null,inquiry,null);
    ReflectionTestUtils.setField(sources,"specialPolicies",policies);
    prices=new MonthlyMaterialPriceService(new MonthlyPriceStorage(db.sql.getMapper(MonthlyPriceStorageMapper.class),codec,db.sql.getMapper(MonthlyPriceLinkMapper.class)),
        db.manager,codec,sources).withSpecialPolicies(policies);
  }
  @AfterAll static void close(){db.close();}
  static Context context(String month){return new Context("COMMERCIAL","SPECIAL-PRICE-TEST",1L,"TOP",YearMonth.parse(month),YearMonth.parse(month).atEndOfMonth().atStartOfDay(),"test","210");}
  static MonthlyPriceRequest request(String code,String month){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(month),"COMMERCIAL",code,null,"只","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);}
  static Selection original(String amount){return new Selection(null,MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any())),PriceFact.normal(new BigDecimal(amount),SourceEvidence.ofJson("{\"sourceKind\":\"PURCHASE_FIXED\"}"),Set.of()));}
  static Selection adopt(MonthlyPriceRequest request,Selection selected){return prices.adopt(context(request.family().month().toString()),new MonthlyAdoptionPlan(Map.of(request,selected),List.of(new Use("root/part",request,BigDecimal.ONE)),Set.of())).plan().selections().get(request);}
  static void rule(String code,String mode){db.jdbc.update("INSERT INTO lp_material_price_type(business_unit_type,period,price_org_code,material_code,material_name,price_type,priority,special_rule,price_refresh_mode) VALUES('COMMERCIAL',NULL,'210',?,?,'INQUIRY',1,1,?)",code,"隔离测试物料",mode);}
  static void publish(String month,String code,String price)throws Exception{db.importFile(month,MonthlyInquiryPriceIntegrationTest.fixedRows(MonthlyInquiryPriceIntegrationTest.fixed(code,"测试规格","",price)));}

  @Test void oldFixedMonthlyPriceCannotBypassTheRuleAndImportAdoptsNewInquiryWithoutRewritingHistory()throws Exception {
    String code="201851022";var request=request(code,"2026-11");var old=adopt(request,original("100"));
    rule(code,"CURRENT_MONTH");
    assertThat(prices.lookup(List.of(request)).missing()).contains(request);
    var missing=prices.resolveCandidates(context("2026-11"),Set.of(request));
    assertThat(missing.problems().get(request).message()).contains("2026-11","请财务导入","询价");
    assertThat(db.jdbc.queryForObject("SELECT disabled FROM lp_material_monthly_price WHERE id=?",Integer.class,old.monthlyPriceId())).isZero();
    publish("2026-11",code,"12.34");
    var candidate=prices.resolveCandidates(context("2026-11"),Set.of(request)).values().get(request);
    assertThat(candidate.selection().fact().price()).isEqualByComparingTo("12.34");
    var adopted=adopt(request,candidate.selection());
    assertThat(adopted.monthlyPriceId()).isNotEqualTo(old.monthlyPriceId());
    for(int i=0;i<3;i++)assertThat(prices.lookup(List.of(request)).values().get(request)).isEqualTo(adopted);
    assertThat(prices.readByIds("COMMERCIAL",Set.of(old.monthlyPriceId())).get(old.monthlyPriceId())).isEqualTo(old);
    assertThat(prices.resolveCandidates(context("2026-12"),Set.of(request(code,"2026-12"))).problems().values()).anySatisfy(problem->assertThat(problem.message()).contains("2026-12"));
  }
  @Test void currentMonthMustBeImportedIndependentlyForEachMaterial()throws Exception {
    String code="339950198";rule(code,"CURRENT_MONTH");publish("2026-10",code,"9");
    var request=request(code,"2026-11");
    assertThat(prices.resolveCandidates(context("2026-11"),Set.of(request)).problems()).containsKey(request);
    publish("2026-11",code,"23.45");
    assertThat(prices.resolveCandidates(context("2026-11"),Set.of(request)).values().get(request).selection().fact().price()).isEqualByComparingTo("23.45");
  }
  @Test void carryForwardUsesCurrentMonthThenLatestHistoricalMonthAndNeverAFutureMonth()throws Exception {
    String code="SPECIAL-CARRY";rule(code,"LATEST_VALID");publish("2026-10",code,"10");publish("2026-11",code,"11");publish("2026-09",code,"99");publish("2027-01",code,"999");
    for(var month:List.of("2026-11","2026-12")) {
      var request=request(code,month);
      assertThat(prices.resolveCandidates(context(month),Set.of(request)).values().get(request).selection().fact().price()).isEqualByComparingTo("11");
    }
    var early=request(code,"2026-08");assertThat(prices.resolveCandidates(context("2026-08"),Set.of(early)).problems()).containsKey(early);
  }
  @Test void ordinaryMonthlyPriceAndOtherOrganizationsKeepTheirOriginalValues() {
    var plain=request("ORDINARY","2026-11");var old=adopt(plain,original("55"));
    assertThat(prices.lookup(List.of(plain)).values().get(plain)).isEqualTo(old);
    var foreign=new MonthlyPriceRequest(plain.family(),"220",plain.conditions(),BigDecimal.ONE);
    assertThat(policies.read(Set.of(foreign),false)).isEmpty();
  }
  @Test void configurationChangeBetweenPreparationAndAdoptionRejectsTheObsoleteCandidate()throws Exception {
    String code="SPECIAL-RACE";rule(code,"LATEST_VALID");publish("2026-10",code,"7");var request=request(code,"2026-11");
    var selected=prices.resolveCandidates(context("2026-11"),Set.of(request)).values().get(request).selection();
    db.jdbc.update("UPDATE lp_material_price_type SET price_refresh_mode='CURRENT_MONTH',rule_version=rule_version+1 WHERE material_code=?",code);
    assertThatThrownBy(()->adopt(request,selected)).isInstanceOf(MonthlyPriceProblem.class).hasMessageContaining("配置已变化");
  }
  @Test void sharedOldPriceSurvivesForOtherOrganizationAndDisableRestoresOrdinarySelection()throws Exception {
    String code="SPECIAL-SHARED";var req=request(code,"2026-11");
    var original=original("60");var shared=new Selection(null,MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210","220"),QuantityInterval.all(),ConditionDomain.any())),original.fact());
    var old=adopt(req,shared);rule(code,"CURRENT_MONTH");publish("2026-11",code,"20");
    var current=adopt(req,prices.resolveCandidates(context("2026-11"),Set.of(req)).values().get(req).selection());
    var foreign=new MonthlyPriceRequest(req.family(),"220",req.conditions(),BigDecimal.ONE);
    assertThat(prices.lookup(Set.of(foreign)).values().get(foreign)).isEqualTo(old);
    assertThat(prices.lookup(Set.of(req)).values().get(req)).isEqualTo(current);
    assertThat(db.jdbc.queryForObject("SELECT disabled FROM lp_material_monthly_price WHERE id=?",Integer.class,old.monthlyPriceId())).isZero();
    db.jdbc.update("UPDATE lp_material_price_type SET rule_enabled=0,rule_version=rule_version+1 WHERE material_code=?",code);
    assertThat(prices.lookup(Set.of(req)).values().get(req)).isEqualTo(old);
  }
  @Test void fixedTypeAlsoUsesPerMaterialSourceMonthBeforeExistingEffectiveDateRules() {
    String code="SPECIAL-FIXED";rule(code,"LATEST_VALID");
    db.jdbc.update("UPDATE lp_material_price_type SET price_type='FIXED' WHERE material_code=?",code);
    for(var entry:Map.of("2026-09","99","2026-10","10","2026-11","11","2027-01","999").entrySet())
      db.jdbc.update("INSERT INTO lp_price_fixed_item(material_code,business_unit_type,org_code,source_type,supplier_code,supplier_name,fixed_price,tax_included,pricing_month,created_at,updated_at) VALUES(?,'COMMERCIAL','210','PURCHASE','TEST','测试供应商',?,0,?,NOW(),NOW())",code,new BigDecimal(entry.getValue()),entry.getKey());
    var req=request(code,"2026-12");
    assertThat(prices.resolveCandidates(context("2026-12"),Set.of(req)).values().get(req).selection().fact().price()).isEqualByComparingTo("11");
    db.jdbc.update("UPDATE lp_material_price_type SET price_refresh_mode='CURRENT_MONTH',rule_version=rule_version+1 WHERE material_code=?",code);
    assertThat(prices.resolveCandidates(context("2026-12"),Set.of(req)).problems().get(req).message()).contains("2026-12","固定价");
  }

  @Test void oldParentDependingOnChangedSpecialChildIsRebuiltAndHistoricalDependencyIdentitySurvives()throws Exception {
    var child=request("SPECIAL-CHILD","2026-11");var parent=request("SPECIAL-PARENT","2026-11");
    var oldChild=adopt(child,original("70"));
    var parentFact=original("80");
    var parentSelection=new Selection(null,parentFact.applicability(),PriceFact.normal(new BigDecimal("80"),parentFact.fact().evidence(),Set.of(child)));
    var oldPlan=prices.adopt(context("2026-11"),new MonthlyAdoptionPlan(Map.of(child,oldChild,parent,parentSelection),List.of(new Use("root",parent,BigDecimal.ONE)),Set.of())).plan();
    rule(child.materialCode(),"CURRENT_MONTH");publish("2026-11",child.materialCode(),"10");
    assertThat(prices.lookup(Set.of(parent)).missing()).contains(parent);
    MonthlyPriceCandidateResolver resolver=(context,requests,dependencies)->{
      var selected=prices.resolveCandidates(context,Set.of(child));var values=new LinkedHashMap<>(selected.values());
      var amount=selected.values().get(child).selection().fact().price().add(BigDecimal.TEN);
      values.put(parent,new Candidate(new Selection(null,parentFact.applicability(),PriceFact.normal(amount,parentFact.fact().evidence(),Set.of(child))),null));
      return new Candidates(values,selected.problems());
    };
    var service=new MonthlyMaterialPriceService(new MonthlyPriceStorage(db.sql.getMapper(MonthlyPriceStorageMapper.class),codec,db.sql.getMapper(MonthlyPriceLinkMapper.class)),db.manager,codec,resolver).withSpecialPolicies(policies);
    var candidates=service.resolveCandidates(context("2026-11"),Set.of(parent));var selections=new LinkedHashMap<MonthlyPriceRequest,Selection>();candidates.values().forEach((k,v)->selections.put(k,v.selection()));
    var adopted=service.adopt(context("2026-11"),new MonthlyAdoptionPlan(selections,List.of(new Use("root",parent,BigDecimal.ONE)),Set.of())).plan();
    assertThat(adopted.selections().get(parent).fact().price()).isEqualByComparingTo("20");
    assertThat(adopted.selections().get(parent).monthlyPriceId()).isNotEqualTo(oldPlan.selections().get(parent).monthlyPriceId());
    assertThat(service.lookup(Set.of(parent)).values()).containsAllEntriesOf(adopted.selections());
    assertThat(service.readByIds("COMMERCIAL",Set.of(oldPlan.selections().get(parent).monthlyPriceId()))).containsValue(oldChild);
  }

  @Test void persistentConfigurationKeepsMasterMetadataAllOrganizationsAndBusinessUnitIsolation() {
    var service=new MaterialSpecialPriceConfiguration(db.jdbc);
    var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("finance",null,List.of());
    auth.setDetails(Map.of("businessUnitType","COMMERCIAL"));org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    try {
      db.jdbc.update("INSERT INTO lp_material_master_raw(organization_code,material_code,material_name,material_spec,material_model) VALUES('COMMERCIAL','SPECIAL-CONFIG','信号调理芯片','SNU501','')");
      assertThat(service.material("SPECIAL-CONFIG").getFirst()).containsEntry("materialSpec","SNU501").containsEntry("materialModel","");
      service.save(null,new MaterialSpecialPriceConfiguration.Input("SPECIAL-CONFIG","信号调理芯片","SNU501","","ALL","INQUIRY","CURRENT_MONTH",true,"年度采购量价",null),"finance");
      var row=service.list("SPECIAL-CONFIG").getFirst();long id=((Number)row.get("id")).longValue();
      var req=request("SPECIAL-CONFIG","2026-11");var foreign=new MonthlyPriceRequest(req.family(),"220",req.conditions(),BigDecimal.ONE);
      assertThat(policies.read(Set.of(req,foreign),false)).containsKeys(req,foreign);
      var update=new MaterialSpecialPriceConfiguration.Input("SPECIAL-CONFIG","信号调理芯片","SNU501","","ALL","INQUIRY","LATEST_VALID",true,null,1L);
      service.save(id,update,"finance2");assertThat(service.list("SPECIAL-CONFIG").getFirst()).containsEntry("updatedBy","finance2");
      assertThatThrownBy(()->service.save(id,update,"stale")).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
      auth.setDetails(Map.of("businessUnitType","HOUSEHOLD"));assertThat(service.list("SPECIAL-CONFIG")).isEmpty();assertThat(service.material("SPECIAL-CONFIG")).isEmpty();
      assertThatThrownBy(()->service.save(id,update,"foreign")).isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class);
    } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
  }

}
