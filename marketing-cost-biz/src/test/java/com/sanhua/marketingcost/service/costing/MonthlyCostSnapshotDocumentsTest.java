package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyCostSnapshotDocumentsTest {
  final MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
  final MonthlyMaterialPrices prices = mock(MonthlyMaterialPrices.class);
  final MonthlyCostSnapshotDocuments documents = new MonthlyCostSnapshotDocuments(codec, prices);
  final Selection selected = selection("12.123456789012345678", "historical original", Set.of(request("D1"), request("D2")));

  @Test void oldFullSnapshotsRemainIndependentOfPublicTablesAndRetainExactValues() {
    var original = snapshot(1);
    String stored = documents.write(original, false);
    assertThat(stored).isEqualTo(codec.unwrap(codec.encode(original)));
    assertThat(documents.read("COMMERCIAL", codec.wrap(stored))).isEqualTo(original);
    verifyNoInteractions(prices);
  }

  @Test void onePageRestoresPublicFactsOnceAndKeepsEveryCustomersOwnValues() {
    var encoded = new LinkedHashMap<Long, String>();
    for (long id = 1; id <= 100; id++) encoded.put(id, codec.wrap(documents.write(snapshot(id), true)));
    when(prices.readByIds("COMMERCIAL", List.of(17L))).thenReturn(Map.of(17L, selected));
    var result = documents.readBatch("COMMERCIAL", encoded);
    assertThat(result).hasSize(100);
    for (long id = 1; id <= 100; id++) assertThat(result.get(id)).isEqualTo(snapshot(id));
    verify(prices).readByIds("COMMERCIAL", List.of(17L));
    verifyNoMoreInteractions(prices);
    var compact = codec.decode(encoded.get(1L), ObjectNode.class);
    assertThat(compact.path("calculation").path("adoptedPrices").get(0).path("selection").path("monthlyPriceId").asLong()).isEqualTo(17);
    assertThat(compact.toString().length()).isLessThan(codec.encode(snapshot(1)).length() / 2);
    assertThat(compact.toString()).doesNotContain("historical original");
  }

  @Test void changedPriceCoverageDependenciesOrEvidenceCannotSilentlyAlterHistoricalCosts() {
    String stored = codec.wrap(documents.write(snapshot(1), true));
    for (var changed : List.of(selection("12.123456789012345679", "historical original", selected.fact().dependencies()),
        selection("12.123456789012345678", "amended", selected.fact().dependencies()),
        selection("12.123456789012345678", "historical original", Set.of(request("D1"))),
        new Selection(17L, new MonthlyPriceCoverage(Set.of("220"), QuantityInterval.all(), ConditionDomain.any()), selected.fact()))) {
      when(prices.readByIds("COMMERCIAL", List.of(17L))).thenReturn(Map.of(17L, changed));
      assertThatThrownBy(() -> documents.read("COMMERCIAL", stored)).hasMessageContaining("已被修改");
    }
    when(prices.readByIds("COMMERCIAL", List.of(17L))).thenReturn(Map.of());
    assertThatThrownBy(() -> documents.read("COMMERCIAL", stored)).hasMessageContaining("缺失");
  }

  @Test void organizationAndDependencyOrderDoNotChangeTheSavedFingerprint() {
    var restored = selection("12.1234567890123456780", "historical original", new LinkedHashSet<>(List.of(request("D2"), request("D1"))));
    when(prices.readByIds("COMMERCIAL", List.of(17L))).thenReturn(Map.of(17L, restored));
    assertThat(documents.read("COMMERCIAL", codec.wrap(documents.write(snapshot(1), true)))).isEqualTo(snapshot(1));
  }

  @Test void crossBusinessUnitAndUnsupportedReferenceVersionFailBeforeReadingFacts() {
    var raw = documents.write(snapshot(1), true);
    assertThatThrownBy(() -> documents.read("HOUSEHOLD", codec.wrap(raw))).hasMessageContaining("业务单元");
    assertThatThrownBy(() -> documents.read("COMMERCIAL", codec.wrap(raw.replace("\"monthlyPriceReferences\":1", "\"monthlyPriceReferences\":4294967297"))))
        .hasMessageContaining("格式");
    verifyNoInteractions(prices);
  }

  MonthlyCostVersionService.Snapshot snapshot(long id) {
    var value = new BigDecimal("12345678901.123456");
    var result = new MonthlyProductCost.Result("MONTHLY_PRODUCT_COST_V1", "policy", null, null,
        List.of(new MonthlyProductCost.AdoptedPrice(request("M"), selected)),
        List.of(new Use("own-" + id + "/M", request("M"), BigDecimal.valueOf(id))), Set.of(), List.of(), List.of(),
        value, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, value, BigDecimal.ZERO, value, null);
    return new MonthlyCostVersionService.Snapshot(1, "COMMERCIAL", id, "COST-" + id, "OA-" + id, id,
        "P", "2026-11", result, List.of(), Map.of("own-" + id + "/M", new MonthlyCostVersionService.PreparationBinding(id * 2, id * 2 + 1)));
  }

  Selection selection(String price, String source, Set<MonthlyPriceRequest> dependencies) {
    var fact = PriceFact.normal(new BigDecimal(price), SourceEvidence.ofJson("{\"source\":\"" + source.repeat(200) + "\"}"), dependencies);
    return new Selection(17L, new MonthlyPriceCoverage(Set.of("220", "210"), QuantityInterval.all(), ConditionDomain.any()), fact);
  }

  MonthlyPriceRequest request(String code) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026, 11), "COMMERCIAL", code,
        null, "件", "CNY", TaxBasis.EXCLUDED, null), "210", NonCuConditions.empty(), BigDecimal.ONE);
  }
}
