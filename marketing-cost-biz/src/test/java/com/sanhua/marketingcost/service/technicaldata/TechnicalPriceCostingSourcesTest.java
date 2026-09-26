package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPriceOwner;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TechnicalPriceCostingSourcesTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final TechnicalDataPriceOwnership claims = mock(TechnicalDataPriceOwnership.class);
  private final TechnicalDataSharedModuleRepository modules = mock(TechnicalDataSharedModuleRepository.class);
  private final TechnicalDataCostingSources approvals = mock(TechnicalDataCostingSources.class);
  private final TechnicalPriceCostingSources service = new TechnicalPriceCostingSources(jdbc, claims, modules, approvals);
  private final QuoteBomReadContext context = new QuoteBomReadContext(1L, 10L, "CURRENT-OA", "2026-09", "COMMERCIAL",
      "CURRENT-PRODUCT", "本产品", null, null, "210", "COMMERCIAL", LocalDate.of(2026,9,17), LocalDateTime.of(2026,9,17,12,0));
  private final TechnicalPriceCostingSources.Reference reference = new TechnicalPriceCostingSources.Reference("BUY", "FIXED", 80L, "TECH:39");
  private final TechnicalDataSharedModuleRepository.Owner owner = new TechnicalDataSharedModuleRepository.Owner(
      5L, 20L, 30L, 11L, "ORIGINAL-PRODUCT", "PRICE", "APPROVED", "APPROVED", 1L, "张工", 39L, "APPROVED", "COMMERCIAL", "210", "2026-08");

  @BeforeEach void source() {
    when(claims.find("BUY")).thenReturn(new TechnicalDataPriceOwner("BUY", 5L, 20L, 30L, "张工", "APPROVED", "APPROVED", 39L, "210", "COMMERCIAL", "只", "CNY"));
    when(modules.module(20L, "PRICE")).thenReturn(owner);
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
    var product = new QuoteTechProduct(); product.setId(20L);
    var version = new QuoteTechDataVersion(); version.setId(40L); version.setContentFingerprint("original-approved");
    when(approvals.requireSource(context, owner)).thenReturn(new TechnicalDataCostingSources.Source("PRICE", null, product, version, 39L));
  }

  @Test void sharedPriceKeepsOriginalProductAndBothApprovedVersionsAcrossMonths() {
    var result = service.require(context, reference).price();
    assertThat(result.productId()).isEqualTo(20L);
    assertThat(result.versionId()).isEqualTo(40L);
    assertThat(result.approvedModuleVersionId()).isEqualTo(39L);
    assertThat(result.priceRecordId()).isEqualTo(80L);
    verify(approvals).requireSource(context, owner);
  }

  @Test void availablePriceCannotBypassOriginalTaskFinanceGate() {
    when(approvals.requireSource(context, owner)).thenThrow(new EffectiveTechnicalDataException(
        "TECH_DATA_FINANCE_CONFIRMATION_REQUIRED", 10L, "2026-09", List.of("PRICE"), "原任务尚未确认"));
    assertThatThrownBy(() -> service.require(context, reference)).isInstanceOf(EffectiveTechnicalDataException.class)
        .hasMessageContaining("原任务尚未确认");
  }

  @Test void preparedOldPriceMustNotSilentlySwitchToNewApprovedVersion() {
    var old = new TechnicalPriceCostingSources.Reference("BUY", "FIXED", 80L, "TECH:38");
    assertThatThrownBy(() -> service.require(context, old)).isInstanceOf(EffectiveTechnicalDataException.class)
        .hasMessageContaining("已变化");
    verifyNoInteractions(approvals);
  }

  @Test void sourceRecordMustStillMatchOrganizationUnitAndPublication() {
    when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(0);
    assertThatThrownBy(() -> service.require(context, reference)).isInstanceOf(EffectiveTechnicalDataException.class)
        .hasMessageContaining("失效或适用组织、单位变化");
    verifyNoInteractions(approvals);
  }
}
