package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.ingest.*;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext;
import com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher;
import com.sanhua.marketingcost.service.reprice.*;
import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;

class MonthlyQuotationSelectedInputTest {
  final OaFormMapper forms = mock(OaFormMapper.class);
  final OaFormItemMapper items = mock(OaFormItemMapper.class);
  final OaFormItemExtraFieldMapper extras = mock(OaFormItemExtraFieldMapper.class);
  final MonthlyRepriceBomReadScope scope = new MonthlyRepriceBomReadScope(mock(QuoteBomSupplementVersionMapper.class),
      mock(QuoteBomSupplementDetailMapper.class), mock(ApprovedElectronicBomRawSnapshotPublisher.class));
  final MonthlyQuotationSessions sessions = new MonthlyQuotationSessions(forms, items, new MonthlyQuotationPrices(mock(MonthlyMaterialPrices.class), new HouseholdMonthlyPriceSource(mock(HouseholdInquiryStorage.class), null, mock(com.sanhua.marketingcost.service.MaterialPricePriorityService.class), mock(com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader.class))),
      new MonthlyPriceScenarios(new MonthlyPriceDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper()), null, null, null, null, null, null, null), mock(MonthlyPriceStorage.class), new MonthlyPriceDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper()), extras, scope);

  @Test void selectedWholeOaSuppliesBoardTermsAndAnnualUsageWithoutReadingLiveOa() {
    var old = calculation();
    var calculation = new MonthlyRepriceCalculation(old.repriceNo(), "CUSTOMER-" + java.util.UUID.randomUUID(),
        new MonthlyRepriceBomService.Input(old.input().form(),old.input().item(),old.input().context(),
            new AnnualUsageInput(new BigDecimal("100"),"TEN_THOUSAND_PIECES")),old.bom(),old.priceAsOfTime());
    calculation.input().form().setSus304Price(new BigDecimal("12345"));
    calculation.input().form().setBoardTender(true);
    calculation.input().item().setAnnualVolume(new BigDecimal("100"));
    when(extras.selectAnnualVolumeUnit(2L)).thenReturn("PIECES");
    scope.use(calculation, () -> {
      var session = sessions.open(request("OA", "P", "2026-11", 2L));
      assertThat(session.context().annualUsage()).isEqualTo(new AnnualUsageInput(new BigDecimal("100"), "TEN_THOUSAND_PIECES"));
      assertThat(session.conditions("220")).isEqualTo(new NonCuConditions(new BoardTerms(null, null, true)));
      assertThat(session.context().oaFormItemId()).isEqualTo(2L);
      verifyNoInteractions(forms, items, extras);
      return null;
    });
  }

  @Test void explicitlyMissingCapturedUnitDoesNotBorrowLaterLiveUnit() {
    var old=calculation();
    old.input().item().setAnnualVolume(new BigDecimal("100"));
    var selected=new MonthlyRepriceCalculation(old.repriceNo(),old.objectKey(),
        new MonthlyRepriceBomService.Input(old.input().form(),old.input().item(),old.input().context(),
            new AnnualUsageInput(new BigDecimal("100"),null)),old.bom(),old.priceAsOfTime());
    when(extras.selectAnnualVolumeUnit(2L)).thenReturn("PIECES");
    scope.use(selected, () -> {
      assertThat(sessions.open(request("OA","P","2026-11",2L)).context().annualUsage())
          .isEqualTo(new AnnualUsageInput(new BigDecimal("100"),null));
      verifyNoInteractions(extras);return null;
    });
  }

  @Test void ordinaryQuotationStillReadsItsOwnOaAfterRepriceScopeIsReleased() {
    var selected = calculation();
    scope.use(selected, () -> sessions.open(request("OA", "P", "2026-11", 2L)));
    selected.input().form().setSus304Price(new BigDecimal("23456"));
    when(items.selectById(2L)).thenReturn(selected.input().item());
    when(forms.selectById(1L)).thenReturn(selected.input().form());
    var ordinary = sessions.open(request("OA", "P", "2026-11", 2L));
    assertThat(ordinary.conditions("220")).isEqualTo(NonCuConditions.empty());
    verify(items).selectById(2L); verify(forms).selectById(1L);
  }

  @Test void anotherCustomerProductOrMonthCannotUseTheSelectedSource() {
    scope.use(calculation(), () -> {
      for (var request : java.util.List.of(request("OTHER", "P", "2026-11", 2L),
          request("OA", "OTHER", "2026-11", 2L), request("OA", "P", "2026-10", 2L),
          request("OA", "P", "2026-11", 3L)))
        assertThatThrownBy(() -> sessions.open(request)).hasMessageContaining("不能跨选定");
      verifyNoInteractions(forms, items, extras); return null;
    });
  }

  private QuotationPriceSessions.Request request(String oa, String product, String month, Long item) {
    return new QuotationPriceSessions.Request(oa, "COMMERCIAL", product, month,
        YearMonth.parse(month).atDay(1).atStartOfDay(), new SupplierPriceReviewContext(item).forOrganization("210"),
        new QuotationPriceSession.Scope());
  }
  private MonthlyRepriceCalculation calculation() {
    var form = new OaForm(); form.setId(1L); form.setOaNo("OA"); form.setBusinessUnitType("COMMERCIAL");
    var item = new OaFormItem(); item.setId(2L); item.setOaFormId(1L); item.setMaterialNo("P"); item.setBusinessUnitType("COMMERCIAL");
    var input = new MonthlyRepriceBomService.Input(form, item, new QuoteBomContext("2026-11", "P",
        new ResolvedCustomerKey("C", ResolvedCustomerKey.Source.OA_HEADER_CUSTOMER, null), "", new QuoteDataOrganization("210", "COMMERCIAL")));
    var bom = new QuoteBomStatus(); bom.setSyncRecordId(1L); bom.setCostPeriodMonth("2026-11"); bom.setBomSource("U9");
    return new MonthlyRepriceCalculation("MRP1130-test", "a".repeat(64), input, bom, LocalDateTime.of(2026, 11, 1, 0, 0));
  }
}
