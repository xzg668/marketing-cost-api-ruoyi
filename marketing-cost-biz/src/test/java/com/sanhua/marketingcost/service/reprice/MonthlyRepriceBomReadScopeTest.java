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
  private final MonthlyRepriceBomReadScope scope = new MonthlyRepriceBomReadScope(versions, mock(com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper.class));
  private final QuoteBomReadContext context = new QuoteBomReadContext(1L,2L,"OA","2026-11",
      "COMMERCIAL","PRODUCT",null,null,null,"210","COMMERCIAL",LocalDate.of(2026,11,1),LocalDateTime.of(2026,11,4,1,0));

  @Test void availableU9IsReusedWithoutAnotherLookupOrDrawingQuery() {
    scope.use(calculation("2026-11",false), () -> {
      var result=scope.original(context, () -> { throw new AssertionError("must not query U9 twice"); });
      assertThat(result.status()).isEqualTo(CurrentU9BomResult.Status.AVAILABLE);
      assertThat(result.monthlySnapshotId()).isEqualTo(51L);
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
      return null;
    });
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
