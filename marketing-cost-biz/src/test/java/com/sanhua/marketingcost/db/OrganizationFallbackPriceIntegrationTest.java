package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.service.MakePartMaterialPriceResolveService;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.impl.OriginalLinkedRuleSnapshot;
import com.sanhua.marketingcost.service.pricing.FinanceEffectivePriceService;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Tag("integration")
@SpringBootTest(properties={"srm.fixed-price.enabled=false","oa.person-directory.enabled=false",
    "oa.person-directory.run-at-startup=false","monthly-reprice.enabled=false",
    "cms.sync.publish.enabled=false","quote.drawing-verification.enabled=false"})
class OrganizationFallbackPriceIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Autowired MakePartMaterialPriceResolveService manufacturingPrices;
  @Autowired MonthlyMaterialPrices monthly;
  @Autowired MonthlyPriceDocumentCodec codec;
  @Autowired PriceLinkedItemMapper linkedRules;
  @Autowired OriginalLinkedRuleSnapshot linkedSnapshots;
  @Autowired FinanceEffectivePriceService effectivePrices;
  @Autowired PlatformTransactionManager transactions;

  @ParameterizedTest @ValueSource(booleans={false,true})
  void plateManufacturingUsesItsOwnSourcesFirstThenTheCommercialLinkedPriceAndLocksIt(boolean localPrice) throws Exception {
    String oa="ORG-"+UUID.randomUUID().toString().substring(0,12),raw=oa+"-301070077";
    String month=com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    var date=YearMonth.parse(month).atDay(15).atTime(12,0);
    jdbc.update("INSERT INTO oa_form(oa_no,business_unit_type,copper_price) VALUES(?,'COMMERCIAL',80000)",oa);
    long form=jdbc.queryForObject("SELECT id FROM oa_form WHERE oa_no=?",Long.class,oa);
    jdbc.update("INSERT INTO oa_form_item(oa_form_id,material_no,business_unit_type) VALUES(?,?,'COMMERCIAL')",form,oa);
    long item=jdbc.queryForObject("SELECT id FROM oa_form_item WHERE oa_form_id=?",Long.class,form);
    jdbc.update("INSERT INTO lp_material_master_raw(material_code,material_name,unit,organization_code,import_batch_id,active_flag) VALUES(?,'紫铜带','KG','PLATE',?,1)",raw,oa);
    MonthlyQuotationSourceFixture.copperLinked(jdbc,linkedRules,linkedSnapshots,effectivePrices,transactions,raw,month,"KG");
    if(localPrice)jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES('220',?,'本组织固定价','KG',7,0,'LOCAL',?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",raw,YearMonth.parse(month).atDay(1));
    var before=jdbc.queryForList("SELECT * FROM lp_price_linked_item WHERE material_code=?",raw);
    var scenario=PricePrepareScenarioContext.oaLocked(item,"220");
    var result=manufacturingPrices.calculateMaterialUnitPrice(raw,month,date.toLocalDate(),date,oa,"COMMERCIAL",scenario,MakePartMaterialPriceResolveService.Purpose.MATERIAL);
    assertThat(result.getUnitPrice()).as(result.getRemark()).isEqualByComparingTo(localPrice?"7":"8");
    var captured=codec.decode(result.getEvidence().inputSnapshotJson(),MonthlyPreparationPrice.class);
    assertThat(captured.request().priceOrgCode()).isEqualTo("220");
    assertThat(captured.financialPrice()).isEqualByComparingTo(localPrice?"7":"10");
    var source=new ObjectMapper().readTree(captured.selection().fact().evidence().json()).path("source").path("selected");
    assertThat(source.path("orgCode").asText()).isEqualTo(localPrice?"220":"210");
    var session=scenario.monthlyPrices().get();
    session.consume("root/raw",captured.value(),new BigDecimal("3"));
    var adopted=monthly.adopt(session.context(),session.plan()).plan();
    var id=adopted.selections().get(captured.request()).monthlyPriceId();
    assertThat(id).isPositive();
    // Once adopted, later original/local prices do not replace this month's locked unit price.
    jdbc.update("INSERT INTO lp_price_fixed_item(org_code,material_code,material_name,unit,fixed_price,tax_included,supplier_code,effective_from,effective_to,business_unit_type,source_kind,source_type,source_system,created_at,updated_at) VALUES('220',?,'后到价格','KG',999,0,'LATER',?,'2099-01-01','COMMERCIAL','PUBLIC','PURCHASE_FIXED','SRM',NOW(),NOW())",raw,YearMonth.parse(month).atDay(1));
    var repeated=manufacturingPrices.calculateMaterialUnitPrice(raw,month,date.toLocalDate(),date,oa,"COMMERCIAL",PricePrepareScenarioContext.oaLocked(item,"220"),MakePartMaterialPriceResolveService.Purpose.MATERIAL);
    var saved=codec.decode(repeated.getEvidence().inputSnapshotJson(),MonthlyPreparationPrice.class);
    assertThat(saved.selection().monthlyPriceId()).isEqualTo(id);
    assertThat(saved.selection().fact()).isEqualTo(captured.selection().fact());
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lp_material_monthly_price WHERE material_code=?",Integer.class,raw)).isEqualTo(1);
    assertThat(jdbc.queryForList("SELECT * FROM lp_price_linked_item WHERE material_code=?",raw)).isEqualTo(before);
  }
}
