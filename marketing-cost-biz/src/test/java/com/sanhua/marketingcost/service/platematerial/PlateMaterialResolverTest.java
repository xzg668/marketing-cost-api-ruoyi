package com.sanhua.marketingcost.service.platematerial;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.PlateMaterialMapping;
import com.sanhua.marketingcost.mapper.PlateMaterialMappingMapper;
import com.sanhua.marketingcost.mapper.PlateMaterialMasterQuery;
import com.sanhua.marketingcost.mapper.PlateMaterialMasterQuery.Match;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialResolver.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class PlateMaterialResolverTest {
  final PlateMaterialMappingMapper mappings = mock(PlateMaterialMappingMapper.class);
  final PlateMaterialMasterQuery master = mock(PlateMaterialMasterQuery.class);
  final PlateMaterialResolver resolver = new PlateMaterialResolver(mappings, master, new ObjectMapper());

  @Test void configuredModelNeverConsultsTheMaterialMasterEvenIfItWouldFail() {
    var mapping = mapping("B12-8H-V20", "水水316L", 3);
    when(mappings.findModels("COMMERCIAL", List.of("B12-8H-V20"), false)).thenReturn(List.of(mapping));
    when(master.findActiveModels(any(), any())).thenThrow(new DataAccessResourceFailureException("offline"));
    var decision = resolver.resolve(context(" B12-8H-V20 "));
    assertThat(decision.mode()).isEqualTo(PlateMaterialMode.CONFIG_LOCKED);
    assertThat(decision.value()).isEqualTo(PlateMaterial.WATER_WATER_316L);
    assertThat(decision.source().mappingId()).isEqualTo(123);
    assertThat(decision.source().mappingVersion()).isEqualTo(3);
    assertThat(decision.masterMatches()).isEmpty();
    verifyNoInteractions(master);
  }

  @Test void existenceOfPlateAmongMultipleDivisionsRequiresManualSelectionAndRetainsEvidence() {
    when(master.findActiveModels("PLATE", List.of("M"))).thenReturn(List.of(
        new Match(3L, "M", "板换事业部"), new Match(2L, "M", "电子事业部"), new Match(1L, "M", "板换事业部")));
    var decision = resolver.resolve(context("M"));
    assertThat(decision.mode()).isEqualTo(PlateMaterialMode.MANUAL_REQUIRED);
    assertThat(decision.value()).isNull();
    assertThat(decision.source().materialMasterIds()).containsExactly(1L, 3L);
    assertThat(decision.masterMatches()).extracting(Match::productionDivision)
        .containsExactly("板换事业部", "电子事业部", "板换事业部");
  }

  @Test void absentEmptyOrOtherDivisionIsHiddenAndNoModelDoesNotQueryEitherTable() {
    for (List<Match> rows : List.of(List.<Match>of(), List.of(new Match(1L, "M", null)),
        List.of(new Match(1L, "M", "")), List.of(new Match(1L, "M", "电子事业部")))) {
      when(master.findActiveModels("PLATE", List.of("M"))).thenReturn(rows);
      var decision = resolver.resolve(context("M"));
      assertThat(decision.mode()).isEqualTo(PlateMaterialMode.HIDDEN);
      assertThat(decision.value()).isNull();
    }
    clearInvocations(mappings, master);
    assertThat(resolver.resolve(context("  ")).mode()).isEqualTo(PlateMaterialMode.HIDDEN);
    assertThat(resolver.resolve(context(null)).source().normalizedModel()).isNull();
    verifyNoInteractions(mappings, master);
  }

  @Test void invalidContextAndDatabaseFailuresAreErrorsRatherThanHidden() {
    assertThatThrownBy(() -> new Context(null, "M", "PLATE")).hasMessageContaining("业务单元");
    assertThatThrownBy(() -> new Context("COMMERCIAL", "M", null)).hasMessageContaining("料品组织");
    assertThatThrownBy(() -> new Context("COMMERCIAL", "M", "220")).hasMessageContaining("料品组织");
    assertThatThrownBy(() -> new Context("COMMERCIAL", null, "UNKNOWN")).hasMessageContaining("料品组织");
    when(master.findActiveModels(any(), any())).thenThrow(new DataAccessResourceFailureException("master offline"));
    assertThatThrownBy(() -> resolver.resolve(context("M"))).isInstanceOf(DataAccessResourceFailureException.class);
    when(mappings.findModels(any(), any(), eq(false))).thenThrow(new DataAccessResourceFailureException("mapping offline"));
    assertThatThrownBy(() -> resolver.resolve(context("M"))).hasMessageContaining("mapping offline");
  }

  @Test void revisionIgnoresQueryOrderButChangesWhenMaterialSourceChanges() {
    when(master.findActiveModels("PLATE", List.of("M")))
        .thenReturn(List.of(new Match(2L, "M", "其他"), new Match(1L, "M", "板换事业部")))
        .thenReturn(List.of(new Match(1L, "M", "板换事业部"), new Match(2L, "M", "其他")))
        .thenReturn(List.of(new Match(1L, "M", "电子事业部"), new Match(2L, "M", "其他")));
    String first = resolver.resolve(context("M")).revision();
    assertThat(resolver.resolve(context("M")).revision()).isEqualTo(first);
    assertThat(resolver.resolve(context("M")).revision()).isNotEqualTo(first);
    when(mappings.findModels("COMMERCIAL", List.of("M"), false)).thenReturn(List.of(mapping("M", "水氟316L", 0)));
    String mapped = resolver.resolve(context("M")).revision();
    assertThat(mapped).isNotEqualTo(first);
    when(mappings.findModels("COMMERCIAL", List.of("M"), false)).thenReturn(List.of(mapping("M", "水氟316L", 1)));
    assertThat(resolver.resolve(context("M")).revision()).isNotEqualTo(mapped);
  }

  @Test void batchDeduplicatesProductsAndSharesConfigurationAcrossCurrentOrganizations() {
    when(mappings.findModels(eq("COMMERCIAL"), any(), eq(false))).thenReturn(List.of(mapping("LOCK", "水水316L", 0)));
    List<Context> contexts = new ArrayList<>();
    for (int i = 0; i < 1000; i++) {
      contexts.add(context("LOCK"));
      contexts.add(context("M"));
      contexts.add(new Context("COMMERCIAL", "M", "COMMERCIAL"));
    }
    var results = resolver.resolveAll(contexts);
    assertThat(results).hasSize(3);
    verify(mappings).findModels("COMMERCIAL", List.of("LOCK", "M"), false);
    verify(master).findActiveModels("PLATE", List.of("M"));
    verify(master).findActiveModels("COMMERCIAL", List.of("M"));
    verifyNoMoreInteractions(mappings, master);
  }

  @Test void largeBatchesAreBoundedAndNeverUseFuzzyMatching() {
    var contexts = IntStream.range(0, 1201).mapToObj(i -> context("Exact-" + i)).toList();
    assertThat(resolver.resolveAll(contexts)).hasSize(1201);
    verify(mappings, times(3)).findModels(eq("COMMERCIAL"), argThat(models -> models.size() <= 500), eq(false));
    verify(master, times(3)).findActiveModels(eq("PLATE"), argThat(models -> models.size() <= 500));
  }

  static Context context(String model) { return new Context("COMMERCIAL", model, "PLATE"); }

  private static PlateMaterialMapping mapping(String model, String value, int version) {
    var row = new PlateMaterialMapping();
    row.setId(123L);
    row.setBusinessUnitType("COMMERCIAL");
    row.setNormalizedModel(model);
    row.setPlateMaterial(value);
    row.setRowVersion(version);
    return row;
  }
}
