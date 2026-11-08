package com.sanhua.marketingcost.service.reprice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.ingest.*;
import com.sanhua.marketingcost.service.quotebom.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MonthlyRepriceBomReadScopeTest {
  private final QuoteBomSupplementVersionMapper versions = mock(QuoteBomSupplementVersionMapper.class);
  private final MonthlyRepriceBomReadScope scope = new MonthlyRepriceBomReadScope(versions, mock(com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper.class), mock(com.sanhua.marketingcost.service.quotebom.ApprovedElectronicBomRawSnapshotPublisher.class));
  private final QuoteBomReadContext context = new QuoteBomReadContext(1L,2L,"OA","2026-11",
      "COMMERCIAL","PRODUCT",null,null,null,"210","COMMERCIAL",LocalDate.of(2026,11,1),LocalDateTime.of(2026,11,4,1,0));

  @Test void historicalPriceContextCannotLeakOutsideItsSelectedRepriceConsumer() {
    var calculation=calculation("2026-11",false);
    var price=new com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.Context(
        "COMMERCIAL","OA",2L,"PRODUCT",java.time.YearMonth.of(2026,11),calculation.priceAsOfTime(),"test","210");
    assertThat(scope.priceContext(price)).isFalse();
    scope.use(calculation,()->{
      assertThat(scope.priceContext(price)).isTrue();
      var wrong=new com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.Context(
          "COMMERCIAL","OTHER-OA",2L,"PRODUCT",java.time.YearMonth.of(2026,11),calculation.priceAsOfTime(),"test","210");
      assertThatThrownBy(()->scope.priceContext(wrong)).hasMessageContaining("不能跨");
      return null;
    });
    assertThat(scope.priceContext(price)).isFalse();
  }
  @Test void availableU9IsReusedWithoutAnotherLookupOrDrawingQuery() {
    scope.use(calculation("2026-11",false), () -> {
      var result=scope.original(context, () -> { throw new AssertionError("must not query U9 twice"); });
      assertThat(result.status()).isEqualTo(CurrentU9BomResult.Status.AVAILABLE);
      assertThat(result.monthlySnapshotId()).isEqualTo(51L);
      assertThat(scope.drawingHierarchy(null)).isNull();
      assertThat(scope.facts(context,List::of)).isEmpty();
      assertThat(scope.facts(context,() -> { throw new AssertionError("must not repeat checks"); })).isEmpty();
      return null;
    });
    verifyNoInteractions(versions);
  }

  @Test void exceptionReleasesScopeAndNextAttemptReadsAgain() {
    var count=new AtomicInteger();
    assertThatThrownBy(() -> scope.use(calculation("2026-11",false), () -> {
      scope.facts(context, () -> { count.incrementAndGet(); return List.of(); });
      throw new IllegalStateException("cost failed");
    })).hasMessage("cost failed");
    scope.use(calculation("2026-11",false), () -> {
      scope.facts(context, () -> { count.incrementAndGet(); return List.of(); }); return null;
    });
    assertThat(count.get()).isEqualTo(2);
    assertThat(scope.original(context, () -> CurrentU9BomResult.timeout("ordinary lookup" )).status())
        .isEqualTo(CurrentU9BomResult.Status.TIMEOUT);
  }

  @Test void anotherMonthCannotBorrowTheCurrentAttempt() {
    scope.use(calculation("2026-10",false), () -> {
      assertThatThrownBy(() -> scope.original(context,() -> null))
          .hasMessageContaining("不能跨报价产品、月份或组织");
      return null;
    });
  }

  @Test void concurrentThreadDoesNotInheritThisProductsBom() throws Exception {
    try(var executor=Executors.newSingleThreadExecutor()) {
      scope.use(calculation("2026-11",false), () -> {
        try {
          assertThat(executor.submit(() -> scope.original(context,
              () -> CurrentU9BomResult.notFound("other product"))).get().status())
              .isEqualTo(CurrentU9BomResult.Status.NOT_FOUND);
        } catch (Exception e) { throw new AssertionError(e); }
        return null;
      });
    }
  }

  @Test void exactChosenVersionIsKeptAndExpiredOrWrongProductIsRejected() {
    var source = new QuoteBomSupplementVersion(); source.setId(41L);source.setOaFormItemId(2L);
    source.setQuoteProductCode("PRODUCT");source.setMaterialOrgCode("COMMERCIAL");
    source.setActiveFlag(1);source.setVersionStatus("APPROVED");source.setCompositionFingerprint("composed");
    source.setPeriodMonth("2026-10");source.setReuseValidUntil(LocalDate.of(2027,3,31));
    when(versions.selectById(41L)).thenReturn(source);
    scope.use(calculation("2026-11",true), () -> {
      var selected=scope.drawing(context,() -> {throw new AssertionError("month lookup");});
      assertThat(selected.sourceVersionId()).isEqualTo(41L);
      assertThat(selected.accountingMonth()).isEqualTo("2026-10");
      assertThat(scope.costingDrawing(2L, "2026-11", () -> { throw new AssertionError("must use selected BOM"); }))
          .isSameAs(selected);
      assertThatThrownBy(() -> scope.costingDrawing(3L, "2026-11", () -> null)).hasMessageContaining("不能跨");
      assertThatThrownBy(() -> scope.costingDrawing(2L, "2026-10", () -> null)).hasMessageContaining("不能跨");
      List<java.util.function.Consumer<QuoteBomMonthlySnapshot>> mismatches = List.of(
          snapshot -> snapshot.setId(52L),
          snapshot -> snapshot.setBomBatchId("SUPPLEMENT_VERSION:42"),
          snapshot -> snapshot.setBomSource("U9"),
          snapshot -> snapshot.setSourceOaFormItemId(3L),
          snapshot -> snapshot.setProductCode("OTHER"),
          snapshot -> snapshot.setCostPeriodMonth("2026-10"),
          snapshot -> snapshot.setPriceOrgCode("220"));
      for (var mismatch : mismatches) {
        var snapshot = new QuoteBomMonthlySnapshot();
        snapshot.setId(51L);snapshot.setBomBatchId("SUPPLEMENT_VERSION:41");
        snapshot.setBomSource("ELECTRONIC_DRAWING_BOM");snapshot.setSourceOaFormItemId(2L);
        snapshot.setProductCode("PRODUCT");snapshot.setCostPeriodMonth("2026-11");
        snapshot.setPriceOrgCode("210");
        mismatch.accept(snapshot);
        assertThatThrownBy(() -> scope.drawingHierarchy(snapshot)).hasMessageContaining("来源不一致");
      }
      return null;
    });
    assertThat(scope.drawingHierarchy(null)).isNull();
    source.setReuseValidUntil(LocalDate.of(2026,10,31));
    assertThatThrownBy(() -> scope.use(calculation("2026-11",true),() -> null)).hasMessageContaining("已失效");
    source.setReuseValidUntil(LocalDate.of(2027,3,31));source.setOaFormItemId(3L);
    assertThatThrownBy(() -> scope.use(calculation("2026-11",true),() -> null)).hasMessageContaining("不属于");
    verify(versions,never()).selectList(any());
  }

  private MonthlyRepriceCalculation calculation(String month,boolean drawing) {
    var form=new OaForm();form.setId(1L);form.setOaNo("OA");form.setBusinessUnitType("COMMERCIAL");
    var item=new OaFormItem();item.setId(2L);item.setOaFormId(1L);
    var context=new QuoteBomContext(month,"PRODUCT",new ResolvedCustomerKey("CUSTOMER",
        ResolvedCustomerKey.Source.OA_HEADER_CUSTOMER,null),"",new QuoteDataOrganization("210","COMMERCIAL"));
    var bom=new QuoteBomStatus();bom.setCostPeriodMonth(month);bom.setSyncRecordId(51L);
    bom.setBomSource(drawing?"ELECTRONIC_DRAWING_BOM":"U9");
    bom.setSyncBatchId(drawing?"SUPPLEMENT_VERSION:41":"U9-SAVED");
    return new MonthlyRepriceCalculation("MRP1130-test","a".repeat(64),
        new MonthlyRepriceBomService.Input(form,item,context),bom,YearMonth.parse(month).atDay(1).atStartOfDay());
  }
}
