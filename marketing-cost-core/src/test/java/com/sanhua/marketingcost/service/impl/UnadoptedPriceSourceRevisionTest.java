package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class UnadoptedPriceSourceRevisionTest {
  @Test void odsRefreshTracksScopedRowsAndPartitionChangesWithoutUsingOldPartitions() {
    var jdbc=mock(JdbcTemplate.class);var source=new UnadoptedPriceSourceRevision(jdbc);
    var partition=new AtomicReference<>("2026-10-24");var price=new AtomicReference<>("10");
    when(jdbc.queryForObject(anyString(),org.mockito.ArgumentMatchers.eq(String.class))).thenAnswer(call->partition.get());
    when(jdbc.queryForList(anyString(),any(Object[].class))).thenAnswer(call->{
      String sql=call.getArgument(0);assertThat(sql).contains("WHERE dt=? AND material_code IN (?)");
      assertThat(Arrays.asList(call.getArguments())).contains(partition.get(),"001A");
      return List.of(Map.of("material_code","001A","price",price.get()));
    });
    var before=source.ods("COMMERCIAL",Set.of("001A"));price.set("20");
    assertThat(source.ods("COMMERCIAL",Set.of("001A"))).isNotEqualTo(before);
    var changed=source.ods("COMMERCIAL",Set.of("001A"));partition.set("2026-10-25");
    assertThat(source.ods("COMMERCIAL",Set.of("001A"))).isNotEqualTo(changed);
    verify(jdbc,never()).queryForList(startsWith("CHECKSUM"));
    clearInvocations(jdbc);assertThat(source.ods("HOUSEHOLD",Set.of("001A"))).isEqualTo("NOT_APPLICABLE");
    verifyNoInteractions(jdbc);
  }

  @Test void approvalPointerAndOriginalFinanceBasisChangesAffectOnlyTheRequestedTechnicalOwner() {
    var jdbc = mock(JdbcTemplate.class); var source = new UnadoptedPriceSourceRevision(jdbc);
    var effective = new AtomicReference<>(7L); var approval = new AtomicReference<>("confirmed");
    var submission = new AtomicReference<>("old");
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(call -> {
      String sql = call.getArgument(0);
      if (sql.startsWith("SELECT * FROM lp_quote_tech_price_claim")) {
        assertThat(Arrays.asList(call.getArguments())).contains("A", "COMMERCIAL");
        return List.of(Map.of("material_code", "A", "owner_module_id", 1L));
      }
      if (sql.startsWith("SELECT * FROM lp_quote_tech_module"))
        return List.of(Map.of("id", 1L, "product_id", 2L, "current_version_id", 6L));
      if (sql.startsWith("SELECT * FROM lp_quote_tech_product"))
        return List.of(Map.of("id", 2L, "task_id", 3L, "effective_version_id", effective.get()));
      if (sql.startsWith("SELECT * FROM lp_quote_tech_task")) return List.of(Map.of("id", 3L, "oa_flow_id", 4L));
      if (sql.startsWith("SELECT * FROM lp_oa_technical_flow"))
        return List.of(Map.of("id", 4L, "finance_confirmed_fingerprint", approval.get()));
      if (sql.startsWith("SELECT t.oa_flow_id")) return List.of(Map.of("content_fingerprint", submission.get()));
      return List.of();
    });
    String original = source.technical("COMMERCIAL", Set.of("A"));
    effective.set(8L); assertThat(source.technical("COMMERCIAL", Set.of("A"))).isNotEqualTo(original);
    effective.set(7L); approval.set("returned"); assertThat(source.technical("COMMERCIAL", Set.of("A"))).isNotEqualTo(original);
    approval.set("confirmed"); submission.set("changed-other-recipient-in-the-same-flow");
    assertThat(source.technical("COMMERCIAL", Set.of("A"))).isNotEqualTo(original);
    verify(jdbc, never()).queryForList(startsWith("CHECKSUM"));
  }

  @Test void legacyCatalogPrivateFactorAndScrapMappingAreObservedWithoutCurrentPriceEvaluation() {
    var jdbc = mock(JdbcTemplate.class); var source = new UnadoptedPriceSourceRevision(jdbc);
    var price = new AtomicReference<>(new BigDecimal("7")); var mapped = new AtomicReference<>("SCRAP-A");
    when(jdbc.queryForList(anyString(), any(Object[].class))).thenAnswer(call -> {
      String sql = call.getArgument(0);
      if (sql.startsWith("SELECT * FROM lp_price_variable WHERE"))
        return List.of(Map.of("id", 1L, "resolver_params", "{\"factorIdentityId\":7}"));
      if (sql.startsWith("SELECT * FROM lp_factor_monthly_price")) {
        assertThat(sql).contains("price_month<=?", "scheme_code='LEGACY'");
        return List.of(Map.of("id", 9L, "price", price.get()));
      }
      if (sql.startsWith("SELECT * FROM lp_material_scrap_ref"))
        return List.of(Map.of("id", 10L, "material_code", "A", "scrap_code", mapped.get()));
      return List.of();
    });
    String before = source.legacyFormula("COMMERCIAL", "2026-10", Set.of(12L));
    price.set(BigDecimal.TEN); assertThat(source.legacyFormula("COMMERCIAL", "2026-10", Set.of(12L))).isNotEqualTo(before);
    var scrap = source.scrapDependencies("COMMERCIAL", Set.of("A"));
    assertThat(scrap.materialCodes()).containsExactly("SCRAP-A");
    mapped.set("SCRAP-B"); assertThat(source.scrapDependencies("COMMERCIAL", Set.of("A")).revision()).isNotEqualTo(scrap.revision());
  }
}
