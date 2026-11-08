package com.sanhua.marketingcost.service.quotefinal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.*;
import com.sanhua.marketingcost.integration.oa.workflow.OaFinalCostSubmissionClient;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.QuotePricingPeriod;
import com.sanhua.marketingcost.service.QuotationMonthlyPriceInputs;
import com.sanhua.marketingcost.service.costing.*;
import com.sanhua.marketingcost.service.impl.CostInputRevisionServiceImpl;
import com.sanhua.marketingcost.service.technicaldata.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.LongStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

/** Exercises the whole-quotation reader with the real per-product revision calculator. */
class QuoteFinalSubmissionPerformanceTest {
  private final AtomicInteger publicScans = new AtomicInteger();
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final OaFormMapper forms = mock(OaFormMapper.class);
  private final OaFormItemMapper items = mock(OaFormItemMapper.class);
  private final QuoteFinalSubmissionRepository repository = mock(QuoteFinalSubmissionRepository.class);
  private final ProductCostingSuccessLookup successes = mock(ProductCostingSuccessLookup.class);
  private final QuotationMonthlyPriceInputs monthly = mock(QuotationMonthlyPriceInputs.class);
  private final TechnicalDataActor actor = new TechnicalDataActor(1L, "报价员", Set.of("*:*:*"));
  private final OaForm form = new OaForm();
  private QuoteFinalSubmissionService service;

  @BeforeEach void setup() {
    var auth = new UsernamePasswordAuthenticationToken("finance", "unused", List.of());
    auth.setDetails(Map.of("businessUnitType", "COMMERCIAL"));
    SecurityContextHolder.getContext().setAuthentication(auth);
    form.setId(9L); form.setOaNo("FI-SC-006-20261105-035"); form.setBusinessUnitType("COMMERCIAL");
    when(forms.selectOne(any())).thenReturn(form);
    when(jdbc.queryForList(startsWith("CHECKSUM TABLE "))).thenAnswer(call -> {
      publicScans.incrementAndGet();
      String sql = call.getArgument(0);
      return Arrays.stream(sql.substring("CHECKSUM TABLE ".length()).split(","))
          .map(table -> Map.<String, Object>of("Table", "test." + table.replace("`", ""), "Checksum", 100L)).toList();
    });
    when(monthly.read(any(), any(), anyString())).thenReturn(
        new QuotationMonthlyPriceInputs.Scope("adopted-price", Set.of(), Set.of(), true));
    QuotePricingPeriod periods = unit -> "2026-11";
    var revisions = new CostInputRevisionServiceImpl(jdbc, monthly);
    var contexts = new ProductCostingContextResolver(forms, items, revisions, periods);
    var json = new ObjectMapper();
    service = new QuoteFinalSubmissionService(repository, forms, items, contexts, successes,
        mock(OaMessageRepository.class), new OaMessageCodec(json), json, mock(TechnicalDataOaContext.class),
        mock(OaFinalCostSubmissionClient.class), mock(OaWorkflowAccessPolicy.class),
        mock(PlatformTransactionManager.class), periods);
  }

  @AfterEach void clearIdentity() { SecurityContextHolder.clearContext(); }

  private void products(int count, Set<Long> withoutCosts) {
    var rows = LongStream.rangeClosed(1, count).mapToObj(id -> {
      var item = new OaFormItem(); item.setId(id); item.setOaFormId(form.getId());
      item.setMaterialNo("P-" + id); item.setBusinessUnitType("COMMERCIAL"); item.setSeq((int) id);
      when(items.selectById(id)).thenReturn(item);
      return item;
    }).toList();
    when(items.selectList(any())).thenReturn(rows);
    when(successes.hasCurrentVersion(any())).thenAnswer(call ->
        !withoutCosts.contains(((ProductCostingContext) call.getArgument(0)).itemId()));
    when(successes.find(any())).thenAnswer(call -> {
      ProductCostingContext context = call.getArgument(0);
      if (withoutCosts.contains(context.itemId())) return Optional.empty();
      var version = new QuoteCostRunVersion(); version.setId(context.itemId() * 100);
      version.setVersionNo("V1"); version.setFinalQuoteAmount(new BigDecimal("196.800000"));
      version.setSourceRevision(context.sourceRevision());
      return Optional.of(new ProductCostingSuccessLookup.ReusableCost(version, "prepare", 0));
    });
  }

  @ParameterizedTest @ValueSource(ints = {107, 500})
  void uncostedQuoteDoesNotScanSourcesForEveryProduct(int count) {
    products(count, new HashSet<>(LongStream.rangeClosed(1, count).boxed().toList()));
    var status = service.status(form.getOaNo(), "2026-11", actor);
    assertThat(status.costs()).hasSize(count);
    assertThat(status.readyProducts()).isZero();
    assertThat(status.canConfirm()).isFalse();
    assertThat(publicScans.get()).as("No cost candidate exists; source scans must not grow with product count").isZero();
    verifyNoInteractions(monthly);
  }

  @ParameterizedTest @ValueSource(ints = {107, 500})
  void costedQuoteChecksPublicSourcesOnceAndEveryProductsOwnInputs(int count) {
    products(count, Set.of());
    var status = service.status(form.getOaNo(), "2026-11", actor);
    assertThat(status.readyProducts()).isEqualTo(count);
    assertThat(status.costs()).allSatisfy(row -> assertThat(row.totalCost()).isEqualTo("196.800000"));
    assertThat(publicScans.get()).as("One public source snapshot per quotation read").isEqualTo(1);
    verify(monthly, times(count)).read(any(), any(), eq("2026-11"));
    verify(successes, times(count)).find(any());
  }

  @Test void mixedQuoteSkipsOnlyUncostedProductsAndPreservesOrder() {
    products(107, Set.of(2L, 50L, 107L));
    var status = service.status(form.getOaNo(), "2026-11", actor);
    assertThat(status.readyProducts()).isEqualTo(104);
    assertThat(status.costs()).extracting(QuoteFinalSubmissionService.CostLine::itemId)
        .containsExactlyElementsOf(LongStream.rangeClosed(1, 107).boxed().toList());
    assertThat(status.costs().get(1).status()).isEqualTo("NOT_READY");
    assertThat(publicScans.get()).isEqualTo(1);
    verify(monthly, times(104)).read(any(), any(), anyString());
  }

  @Test void sourceChangesAreObservedOnTheNextReadRatherThanCachedAcrossRequests() {
    products(107, Set.of());
    var before = service.status(form.getOaNo(), "2026-11", actor);
    when(monthly.read(any(), any(), anyString())).thenReturn(
        new QuotationMonthlyPriceInputs.Scope("changed-adopted-price", Set.of(), Set.of(), true));
    var after = service.status(form.getOaNo(), "2026-11", actor);
    assertThat(after.fingerprint()).isNotEqualTo(before.fingerprint());
    assertThat(after.costs().getFirst().sourceRevision()).isNotEqualTo(before.costs().getFirst().sourceRevision());
    assertThat(publicScans.get()).isEqualTo(2);
  }

  @Test void oneInvalidProductDoesNotHideOtherCosts() {
    products(3, Set.of());
    when(monthly.read(any(), argThat(item -> item.getId() == 2L), anyString()))
        .thenThrow(new IllegalArgumentException("第二个产品的价格依据失效"));
    var status = service.status(form.getOaNo(), "2026-11", actor);
    assertThat(status.readyProducts()).isEqualTo(2);
    assertThat(status.costs().get(1).message()).isEqualTo("第二个产品的价格依据失效");
    assertThat(status.costs().getFirst().totalCost()).isEqualTo("196.800000");
    assertThat(status.costs().getLast().totalCost()).isEqualTo("196.800000");
    assertThat(status.canConfirm()).isFalse();
  }
}
