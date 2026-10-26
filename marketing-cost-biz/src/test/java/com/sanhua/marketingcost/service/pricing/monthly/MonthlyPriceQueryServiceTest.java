package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.monthly.MonthlyMaterialPriceRow;
import com.sanhua.marketingcost.mapper.monthly.MonthlyPriceQueryMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.*;

class MonthlyPriceQueryServiceTest {
  MonthlyPriceQueryMapper mapper = mock(MonthlyPriceQueryMapper.class);
  MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
  MonthlyPriceQueryService service = new MonthlyPriceQueryService(mapper, codec);
  MonthlyMaterialPriceRow row;

  @BeforeEach
  void setup() {
    row = new MonthlyMaterialPriceRow();
    row.setId(1L);
    row.setBusinessUnitType("COMMERCIAL");
    row.setPricingMonth("2026-10");
    row.setMaterialCode("001a");
    row.setSourceType("PURCHASE_FIXED");
    row.setPricingType("NORMAL");
    row.setUnitPrice(new BigDecimal("999999999900.123456789012345678"));
    row.setApplicabilityJson(codec.wrap("{\"fragments\":[]}"));
    row.setFirstUseJson(codec.wrap("{\"origin\":{\"oaNo\":\"Q\"}}"));
    evidence(
        "{\"sourceKind\":\"PURCHASE_FIXED\",\"source\":{\"selected\":{\"id\":2,\"fixedPrice\":999999999900.123456789012345678,\"ruleRevision\":3,\"sourceBatchNo\":\"batch-v1\"}}}");
    when(mapper.detail("COMMERCIAL", 1)).thenReturn(row);
  }

  void evidence(String text) {
    row.setSourceEvidenceJson(codec.wrap(text));
    row.setSourceEvidenceHash(MonthlyPriceDocumentCodec.sha256(text));
  }

  @Test
  void exactAmountsAndSourceNumbersNeverPassThroughJsonDoubles() throws Exception {
    var detail = service.detail("COMMERCIAL", 1);
    assertThat(detail.price().price()).isEqualTo("999999999900.123456789012345678");
    assertThat(
            detail.savedEvidence().path("source").path("selected").path("fixedPrice").textValue())
        .isEqualTo("999999999900.123456789012345678");
    assertThat(detail.price().source().version()).isEqualTo("3");
    assertThat(detail.price().source().batch()).isEqualTo("batch-v1");
    assertThat(detail.price().householdRate()).isNull();
  }

  @Test
  void explicitZeroPriceAndRateRemainDistinctFromNull() {
    row.setUnitPrice(BigDecimal.ZERO);
    row.setHouseholdRate(BigDecimal.ZERO);
    row.setPricingType("HOUSEHOLD_INQUIRY");
    assertThat(service.detail("COMMERCIAL", 1).price().price()).isEqualTo("0");
    assertThat(service.detail("COMMERCIAL", 1).price().householdRate()).isEqualTo("0");
  }

  @Test
  void monthlyDetailDoesNotBypassFinancialReferenceOrFilePathPermissions() {
    row.setSourceType("HOUSEHOLD_INQUIRY");
    row.setHouseholdSourceId(9L);
    evidence(
        "{\"source\":{\"versionId\":9,\"fileKey\":\"private-path\",\"evidence\":{\"source\":{\"materialName\":\"线圈\",\"unitPrice\":\"7\",\"cells\":[{\"value\":\"financial-only\"}],\"financialReference\":{\"D\":\"financial-only\"}}}}}");
    var detail = service.detail("COMMERCIAL", 1);
    assertThat(detail.price().materialName()).isEqualTo("线圈");
    assertThat(detail.savedEvidence().toString())
        .doesNotContain("private-path", "financial-only", "fileKey", "financialReference", "cells");
    assertThat(detail.price().source().householdSourceId()).isEqualTo(9L);
  }

  @Test
  void corruptedSavedEvidenceIsRejected() {
    row.setSourceEvidenceHash("0".repeat(64));
    assertThatThrownBy(() -> service.detail("COMMERCIAL", 1))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("校验失败");
  }
}
