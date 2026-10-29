package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.settlement.BomSettlementNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MonthlyQuotationBomPricingTest {
  @Mock MonthlyMaterialPrices monthly;
  @Mock HouseholdInquiryStorage household;
  @Mock com.sanhua.marketingcost.service.MaterialPricePriorityService priorities;
  MonthlyQuotationPrices prices;
  @Mock QuotationPriceSessions sessions;
  @Mock QuotationPriceSession session;
  @Mock MaterialMasterRawMapper materials;
  MonthlyQuotationBomPricing pricing;

  @BeforeEach
  void setup() {
    prices =
        new MonthlyQuotationPrices(
            monthly, new HouseholdMonthlyPriceSource(household, null, priorities));
    pricing = new MonthlyQuotationBomPricing(prices, sessions, materials);
    when(sessions.open(any())).thenReturn(session);
  }

  @Test
  void finishedProductWithoutArchiveOrUnitKeepsItsChildrenInThePricingScope() {
    var root = node("1145100050625", null, 0, "/1145100050625/");
    var child = node("301300203", root.materialCode(), 1, root.path() + "A/");
    var context =
        new MonthlyMaterialPrices.Context(
            "COMMERCIAL",
            "OA-1",
            1L,
            root.materialCode(),
            YearMonth.of(2026, 10),
            LocalDateTime.of(2026, 10, 1, 0, 0),
            "admin",
            "210");
    when(session.context()).thenReturn(context);
    when(session.conditions("210")).thenReturn(NonCuConditions.empty());
    when(monthly.lookup(anyCollection()))
        .thenAnswer(
            call ->
                new MonthlyMaterialPrices.Lookup(
                    Map.of(), new HashSet<MonthlyPriceRequest>(call.getArgument(0))));

    var view =
        pricing.select(
            "OA-1",
            1L,
            "COMMERCIAL",
            "2026-10",
            "210",
            context.priceAsOfTime(),
            List.of(root, child),
            Map.of(child.path(), "千克"));

    assertThat(view.visible()).containsExactly(root, child);
    assertThat(view.scope().original().getFirst().request()).isNull();
    assertThat(view.scope().original().get(1).request().family().unit()).isEqualTo("千克");
    assertThat(view.units()).doesNotContainKey(MonthlyBomPricingScope.pathKey(root.path()));
    var captured = new MonthlyQuotationSession(context, null, prices,
        MonthlyScenarioTestSupport.create(null), mock(MonthlyPriceStorage.class),
        new MonthlyPriceDocumentCodec(new com.fasterxml.jackson.databind.ObjectMapper()));
    captured.bindScope(view.scope());
    assertThat(captured.plan().ordinaryScopeRequests()).allSatisfy(request ->
        assertThat(request.family().materialCode()).isEqualTo("301300203"));
    assertThat(captured.plan().ordinaryScopeRequests()).hasSize(1);
    verifyNoInteractions(materials);
    verify(monthly)
        .lookup(
            argThat(
                requests ->
                    !requests.isEmpty()
                        && requests.stream()
                            .allMatch(
                                request ->
                                    request.family().materialCode().equals("301300203")
                                        && request.family().unit().equals("千克"))));
    verify(monthly, never()).adopt(any(), any());
  }

  @Test
  void missingChildUnitStillBlocksInsteadOfGuessing() {
    var root = node("1145100050625", null, 0, "/1145100050625/");
    var child = node("301300203", root.materialCode(), 1, root.path() + "A/");
    when(materials.selectByLatestBatchAndCodes(anyCollection(), isNull(), eq("COMMERCIAL")))
        .thenReturn(List.of());
    assertThatThrownBy(
            () ->
                pricing.select(
                    "OA-1",
                    1L,
                    "COMMERCIAL",
                    "2026-10",
                    "210",
                    LocalDateTime.of(2026, 10, 1, 0, 0),
                    List.of(root, child),
                    Map.of()))
        .hasMessageContaining("301300203");
    verifyNoInteractions(monthly, household, priorities);
  }

  private BomSettlementNode node(String code, String parent, int level, String path) {
    return new BomSettlementNode(
        1L,
        "1145100050625",
        parent,
        code,
        level,
        path,
        BigDecimal.ONE,
        BigDecimal.ONE,
        code,
        null,
        level == 0 ? "制造件" : "采购件",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        level == 0 ? 0 : 1,
        null,
        null,
        null,
        "210",
        "COMMERCIAL",
        "COMMERCIAL",
        null);
  }
}
