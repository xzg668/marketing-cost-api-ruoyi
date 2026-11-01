package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.monthly.MonthlyMaterialPriceRow;
import com.sanhua.marketingcost.mapper.monthly.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlyPriceStorageEvidenceReuseTest {
  @Test void reusesOnlyExactEvidenceAndStillReadsAndValidatesEachDatabaseRow() {
    var mapper = mock(MonthlyPriceStorageMapper.class);
    var codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
    var storage = new MonthlyPriceStorage(mapper, codec, mock(MonthlyPriceLinkMapper.class));
    var family = new MonthlyPriceFamilyKey(YearMonth.of(2026, 10), "COMMERCIAL", "A", null, "KG", "CNY", TaxBasis.EXCLUDED, null);
    var applicability = MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of("210"), QuantityInterval.all(), ConditionDomain.any()));
    var evidence = MonthlyAdoptionPlan.SourceEvidence.ofJson("{\"sourceKind\":\"FIXED\",\"version\":1}");
    var row = new MonthlyMaterialPriceRow(); row.setId(1L); row.setPricingMonth("2026-10");
    row.setBusinessUnitType("COMMERCIAL"); row.setMaterialCode("A"); row.setPricingUnit("KG");
    row.setCurrency("CNY"); row.setTaxBasis("EXCLUDED"); row.setFamilyHash(MonthlyPriceDocumentCodec.sha256(family.canonical()));
    row.setApplicabilityJson(codec.encode(applicability)); row.setApplicabilityHash(MonthlyPriceDocumentCodec.sha256(applicability.canonical()));
    row.setOrganizationMask(1); row.setPricingType("NORMAL"); row.setUnitPrice(BigDecimal.ONE);
    row.setSourceEvidenceJson(codec.wrap(evidence.json())); row.setSourceEvidenceHash(evidence.sha256()); row.setFirstUseJson(codec.wrap("{\"fixture\":1}"));
    when(mapper.pricesByIds(eq("COMMERCIAL"), any())).thenAnswer(call -> List.of(row));
    var first = storage.readByIds("COMMERCIAL", List.of(1L)).getFirst();
    row.setUnitPrice(BigDecimal.TEN);
    var second = storage.readByIds("COMMERCIAL", List.of(1L)).getFirst();
    assertThat(second.evidence()).isSameAs(first.evidence());
    assertThat(second.unitPrice()).isEqualByComparingTo("10");
    verify(mapper, times(2)).pricesByIds(eq("COMMERCIAL"), any());
    row.setSourceEvidenceHash("corrupt");
    assertThatThrownBy(() -> storage.readByIds("COMMERCIAL", List.of(1L))).hasMessageContaining("conflict");
    row.setSourceEvidenceHash(evidence.sha256()); row.setSourceEvidenceJson(codec.wrap("{\"sourceKind\":\"CHANGED\"}"));
    assertThatThrownBy(() -> storage.readByIds("COMMERCIAL", List.of(1L))).hasMessageContaining("conflict");
  }
}
