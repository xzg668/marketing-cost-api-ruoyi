package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.enums.*;
import com.sanhua.marketingcost.mapper.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class FixedPriceResolverTest {
  private final PriceFixedItemMapper prices = mock(PriceFixedItemMapper.class);
  private final SupplierSupplyRatioMapper ratios = mock(SupplierSupplyRatioMapper.class);
  private final SupplierPriceDecisionMapper decisions = mock(SupplierPriceDecisionMapper.class);
  private final FixedPriceResolver resolver = new FixedPriceResolver(prices,
      new SupplierPriceSelectionService(ratios, decisions), mock(TechnicalPriceSourceResolver.class));
  private final LocalDate date = LocalDate.parse("2026-10-08");
  private CostRunContext context;

  @BeforeAll static void metadata() {
    var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PriceFixedItem.class);
    TableInfoHelper.initTableInfo(assistant, SupplierSupplyRatio.class);
  }
  @BeforeEach void setup() {
    context = new CostRunContext();
    context.setBusinessUnitType("COMMERCIAL"); context.setPricingMonth("2026-10");
    context.setPriceAsOfTime(date.atStartOfDay()); context.setPriceOrgCode("210");
    context.setOaFormItemId(17L); context.setSupplierPriceReviewContext(new SupplierPriceReviewContext(17L));
  }
  @Test void missingAccountingDateCannotUseRouteStartAsDate() {
    context.setPriceAsOfTime(null);
    assertThat(resolve(twoPrices()).failureCode()).isEqualTo("FIXED_PRICE_SCOPE_MISSING");
    verifyNoInteractions(ratios, decisions);
  }
  @Test void queriesOrganizationMaterialAndHalfOpenDates() {
    resolve(List.of(row("A","12","2026-10-01","2027-01-01")));
    ArgumentCaptor<Wrapper<PriceFixedItem>> query=ArgumentCaptor.forClass(Wrapper.class);
    verify(prices).selectList(query.capture());
    assertThat(query.getValue().getCustomSqlSegment()).contains("org_code", "material_code", "business_unit_type", "effective_from <=", "effective_to >");
    verifyNoInteractions(ratios,decisions);
  }
  @Test void effectiveStartIncludedAndEndExcluded() {
    var result=resolve(List.of(row("A","12","2026-10-08","2027-01-01"),row("B","99","2026-01-01","2026-10-08")));
    assertThat(result.unitPrice()).isEqualByComparingTo("12");
    verifyNoInteractions(ratios);
  }
  @Test void expiredPriceIsMissingWithoutCarryForward() {
    var result=resolve(List.of(row("A","12","2026-01-01","2026-10-08")));
    assertThat(result.unitPrice()).isNull(); assertThat(result.carriedForward()).isFalse();
  }
  @Test void oneSupplierUsesLatestStartNotHighestPrice() {
    var result=resolve(List.of(row("A","64.7","2026-04-01","2027-03-31"),row("A","62.9","2026-10-01","2027-09-30")));
    assertThat(result.unitPrice()).isEqualByComparingTo("62.9");
  }
  @Test void equalStartsUseLatestEnd() {
    var result=resolve(List.of(row("A","14.115","2026-05-01","2026-12-31"),row("A","14.2","2026-05-01","2027-04-30")));
    assertThat(result.unitPrice()).isEqualByComparingTo("14.2");
  }
  @Test void ambiguousSameVersionIsExplicitGap() {
    var result=resolve(List.of(row("A","12","2026-01-01","2027-01-01"),row("A","13","2026-01-01","2027-01-01")));
    assertThat(result.failureCode()).isEqualTo("FIXED_PRICE_VERSION_CONFLICT");
  }
  @Test void ratioWinsBeforePrice() {
    when(ratios.selectList(any(Wrapper.class))).thenReturn(List.of(ratio("A","0.7"),ratio("B","0.3")));
    assertThat(resolve(twoPrices()).unitPrice()).isEqualByComparingTo("12");
  }
  @Test void equalLargestRatiosChooseHighestPrice() {
    when(ratios.selectList(any(Wrapper.class))).thenReturn(List.of(ratio("A","0.5"),ratio("B","0.5")));
    assertThat(resolve(twoPrices()).unitPrice()).isEqualByComparingTo("18");
  }
  @Test void supplierWithoutValidPriceCannotBeChosen() {
    when(ratios.selectList(any(Wrapper.class))).thenReturn(List.of(ratio("A","0.1"),ratio("B","0.2"),ratio("C","0.7")));
    assertThat(resolve(twoPrices()).unitPrice()).isEqualByComparingTo("18");
  }
  @Test void missingRatiosRequireReviewAndNoWrites() {
    assertThat(resolve(twoPrices()).failureCode()).isEqualTo(SupplierPriceSelectionService.REVIEW_REQUIRED);
    assertThat(context.getSupplierPriceReviewContext().reviews()).singleElement().satisfies(r -> {
      assertThat(r.scope().oaFormItemId()).isEqualTo(17L); assertThat(r.status()).isEqualTo("PENDING");
      assertThat(r.candidates()).allSatisfy(c -> assertThat(c.supplyRatio()).isNull());
    });
    verify(decisions,never()).insert(any(SupplierPriceDecision.class));
  }
  @Test void confirmedNoChoosesHighestAndReentryKeepsDecision() {
    resolve(twoPrices()); var review=context.getSupplierPriceReviewContext().reviews().getFirst();
    var decision=new SupplierPriceDecision(); decision.setDecision("FALLBACK_HIGH"); decision.setFingerprint(review.fingerprint());
    when(decisions.latest(review.scopeKey())).thenReturn(decision);
    var result=resolve(twoPrices()); assertThat(result.unitPrice()).isEqualByComparingTo("18");
    assertThat(result.warningMessage()).contains("已确认按最高价");
    assertThat(resolve(twoPrices()).unitPrice()).isEqualByComparingTo("18");
  }
  @Test void changedPriceInvalidatesPreviousConfirmation() {
    resolve(twoPrices()); var review=context.getSupplierPriceReviewContext().reviews().getFirst();
    var decision=new SupplierPriceDecision(); decision.setDecision("FALLBACK_HIGH"); decision.setFingerprint(review.fingerprint());
    when(decisions.latest(review.scopeKey())).thenReturn(decision);
    var changed=twoPrices(); changed.getFirst().setFixedPrice(new BigDecimal("19"));
    assertThat(resolve(changed).unitPrice()).isNull();
  }
  @Test void waitingDecisionAutomaticallyResolvesAfterImport() {
    var decision=new SupplierPriceDecision(); decision.setDecision("WAIT_IMPORT");
    when(decisions.latest(any())).thenReturn(decision);
    assertThat(resolve(twoPrices()).unitPrice()).isNull();
    when(ratios.selectList(any(Wrapper.class))).thenReturn(List.of(ratio("A","1"),ratio("B",null)));
    assertThat(resolve(twoPrices()).unitPrice()).isEqualByComparingTo("12");
  }
  @Test void srmEvidenceDoesNotUseVolatileId() {
    var row=row("A","12","2026-01-01","2027-01-01");row.setId(123L);row.setSourceSystem("SRM");row.setSourceKind("PUBLIC");row.setSourceBatchNo("batch");
    var result=resolve(List.of(row));assertThat(result.resultRefId()).isNull();assertThat(result.evidence().sourceBatchNo()).isEqualTo("batch");
  }
  @Test void settlementPriceBehaviorIsUnchanged() {
    when(prices.selectList(any(Wrapper.class))).thenReturn(List.of(row("A","11","2026-01-01","2026-02-01")));
    var result=resolver.resolve("OA-TEST",part(),route("结算价"),context);
    assertThat(result.unitPrice()).isEqualByComparingTo("11");assertThat(result.priceSource()).isEqualTo("结算固定价");verifyNoInteractions(ratios);
  }
  private PriceResolveResult resolve(List<PriceFixedItem> rows) {
    when(prices.selectList(any(Wrapper.class))).thenReturn(rows);return resolver.resolve("OA-TEST",part(),route("固定采购价"),context);
  }
  private CostRunPartItemDto part() {var p=new CostRunPartItemDto();p.setPartCode("MAT-1");return p;}
  private PriceTypeRoute route(String type) {return new PriceTypeRoute("MAT-1",MaterialFormAttrEnum.PURCHASED,PriceTypeEnum.FIXED,1,date,null,"manual",type);}
  private List<PriceFixedItem> twoPrices() {return List.of(row("A","12","2026-01-01","2027-01-01"),row("B","18","2026-01-01","2027-01-01"));}
  private PriceFixedItem row(String supplier,String price,String start,String end) {
    var r=new PriceFixedItem();r.setSupplierCode(supplier);r.setSourceType("PURCHASE_FIXED");r.setFixedPrice(new BigDecimal(price));r.setEffectiveFrom(LocalDate.parse(start));r.setEffectiveTo(LocalDate.parse(end));return r;
  }
  private SupplierSupplyRatio ratio(String supplier,String value) {var r=new SupplierSupplyRatio();r.setSupplierCode(supplier);r.setSupplyRatio(value==null?null:new BigDecimal(value));return r;}
}
