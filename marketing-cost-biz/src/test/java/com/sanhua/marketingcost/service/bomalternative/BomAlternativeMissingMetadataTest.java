package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Actual 79-node monthly BOM: an unmarked alternative must never double the base quantity. */
class BomAlternativeMissingMetadataTest {
  @Test void defaultsToStandardAndKeepsBothStandardTerminalsAtThree() throws Exception {
    var sample = sample();
    var before = quantitiesAndPaths(sample.rows());
    var groups = sample.resolver().resolve(sample.rows());
    var selected = groups.groups().stream().collect(Collectors.toMap(
        BomAlternativeGroup::alternativeGroupKey, group -> group.standardCandidate().materialCode()));
    var pruned = prune(sample.rows(), groups, selected);
    for (String material : List.of("7218501041211", "332010025", "201850117", "201850115", "721850071", "201850130")) {
      assertThat(quantity(pruned, material)).as("standard BOM quantity of %s", material).isEqualByComparingTo("1");
    }
    assertThat(groups.groups()).hasSize(1);
    assertThat(groups.issues()).isEmpty();
    assertThat(quantity(pruned, "332020616")).isEqualByComparingTo("3");
    assertThat(quantity(pruned, "332020627")).isEqualByComparingTo("3");
    assertThat(quantity(pruned, "201850348")).isZero();
    assertThat(quantity(pruned, "201850548")).isEqualByComparingTo("1");
    assertThat(quantitiesAndPaths(sample.rows())).isEqualTo(before);
    verify(sample.mapper(), times(1)).selectAlternativeMetadata(eq("210"), anyList());
  }

  @Test void financeCanSwitchWholeSubtreeAndRestoreStandard() throws Exception {
    var sample = sample();
    var groups = sample.resolver().resolve(sample.rows());
    String key = groups.groups().getFirst().alternativeGroupKey();
    var alternative = prune(sample.rows(), groups, Map.of(key, "201850528"));
    assertThat(quantity(alternative, "7218501041211")).isEqualByComparingTo("1");
    assertThat(quantity(alternative, "201850348")).isEqualByComparingTo("1");
    assertThat(quantity(alternative, "201850548")).isZero();
    var standard = prune(sample.rows(), groups, Map.of(key, "201850665"));
    assertThat(quantity(standard, "7218501041211")).isEqualByComparingTo("1");
    assertThat(quantity(standard, "201850348")).isZero();
    assertThat(quantity(standard, "301340017")).isEqualByComparingTo("0.000037");
    assertThat(quantity(alternative, "301340017")).isEqualByComparingTo("0.000040");
  }

  @Test void currentSourceVersionChangeRestoresTypeWithoutChangingTheFirstMonthlyBom() throws Exception {
    var sample = sample();
    var before=quantitiesAndPaths(sample.rows());
    var versions=sample.rows().stream().map(BomRawHierarchy::getBomVersion).toList();
    sample.sources().stream().filter(row -> "201850528".equals(row.getChildMaterialNo()))
        .forEach(row -> row.setBomVersion("F999"));
    var result=sample.resolver().resolve(sample.rows());
    assertThat(result.groups()).hasSize(1);
    assertThat(quantitiesAndPaths(sample.rows())).isEqualTo(before);
    assertThat(sample.rows().stream().map(BomRawHierarchy::getBomVersion).toList()).isEqualTo(versions);
  }

  @Test void commercialMakeExpansionKeepsTheOriginalPlateBomChoicePosition() throws Exception {
    var sample = sample();
    var candidates = sample.rows().stream()
        .filter(row -> List.of("201850665", "201850528").contains(row.getMaterialCode())).toList();
    for (var row : candidates) {
      row.setPriceOrgCode("220");
      row.setSourceLineKey(row.getSourceLineKey().replace("|210|", "|220|"));
    }
    sample.sources().stream()
        .filter(row -> "101850070".equals(row.getParentMaterialNo())
            && List.of("201850665", "201850528").contains(row.getChildMaterialNo()))
        .forEach(row -> row.setPriceOrgCode("220"));
    // PlateCommercialMakeBomExpansionService changes pricing organization, retaining sourceLineKey.
    candidates.stream().filter(row -> "201850665".equals(row.getMaterialCode()))
        .forEach(row -> row.setPriceOrgCode("210"));
    var groups = sample.resolver().resolve(sample.rows());
    assertThat(groups.issues()).isEmpty();
    assertThat(groups.groups()).hasSize(1);
    var group = groups.groups().getFirst();
    assertThat(group.identity().priceOrgCode()).isEqualTo("220");
    var standard = prune(sample.rows(), groups, Map.of(group.alternativeGroupKey(), "201850665"));
    assertThat(quantity(standard, "7218501041211")).isEqualByComparingTo("1");
    assertThat(candidates.stream().filter(row -> "201850665".equals(row.getMaterialCode())).findFirst()
        .orElseThrow().getPriceOrgCode()).isEqualTo("210");
    verify(sample.mapper()).selectAlternativeMetadata(eq("220"), anyList());
  }

  @Test void crossOrganizationSourceKeyMustMatchTheCompleteBusinessLine() throws Exception {
    var sample = sample();
    var row = sample.rows().stream().filter(node -> "201850665".equals(node.getMaterialCode()))
        .findFirst().orElseThrow();
    row.setSourceLineKey(row.getSourceLineKey().replace("|210|", "|220|").replace("|F009|", "|F999|"));
    assertThatThrownBy(() -> sample.resolver().resolve(sample.rows()))
        .isInstanceOf(QuoteBomAlternativeSelectionException.class).hasMessageContaining("来源行标识与BOM身份不一致");
    verifyNoInteractions(sample.mapper());
  }

  @Test void cannotUseAnotherOrganizationOrNotYetEffectiveSources() throws Exception {
    for (boolean changeOrganization : List.of(true, false)) {
      var sample = sample();
      sample.sources().stream().filter(row -> "201850528".equals(row.getChildMaterialNo()))
          .forEach(row -> {
            if (changeOrganization) row.setPriceOrgCode("220");
            else row.setEffectiveFrom(com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingDate().plusDays(1));
          });
      assertThatThrownBy(() -> sample.resolver().resolve(sample.rows()))
          .isInstanceOf(QuoteBomAlternativeSelectionException.class).hasMessageContaining("有效来源");
    }
  }

  @Test void duplicateOrUntypedSourcesCannotSilentlyBecomeOrdinaryParts() throws Exception {
    var sample = sample();
    var source = sample.sources().stream().filter(row -> "201850528".equals(row.getChildMaterialNo())
        && "主制造".equals(row.getBomPurpose())).findFirst().orElseThrow();
    sample.sources().add(source);
    assertThatThrownBy(() -> sample.resolver().resolve(sample.rows())).hasMessageContaining("来源不唯一");
    sample.sources().removeLast();
    source.setChildType(null);
    assertThatThrownBy(() -> sample.resolver().resolve(sample.rows())).hasMessageContaining("来源没有明确");
  }

  @Test void capturedMetadataSurvivesSourceRefreshWithoutReadingTheCurrentSource() throws Exception {
    var sample = sample();
    var first = sample.resolver().resolve(sample.rows());
    reset(sample.mapper());
    var second = sample.resolver().resolve(sample.rows());
    assertThat(second).isEqualTo(first);
    verifyNoInteractions(sample.mapper());
  }

  @Test void generatingMissingKeysDoesNotOverwriteAnExistingSelectionIdentity() throws Exception {
    var sample = sample();
    sample.rows().stream().filter(row -> "201850665".equals(row.getMaterialCode()))
        .forEach(row -> row.setAlternativeGroupKey("EXISTING-CHOICE"));
    var groups = sample.resolver().resolve(sample.rows());
    assertThat(groups.groups().getFirst().alternativeGroupKey()).isEqualTo("EXISTING-CHOICE");
    assertThat(groups.groups().getFirst().standardCandidate().materialCode()).isEqualTo("201850665");
  }

  @Test void drawingNodesDoNotQueryU9OrAcquireInventedAlternatives() throws Exception {
    var sample = sample();
    sample.rows().forEach(row -> row.setSourceType("ELECTRONIC_DRAWING_BOM"));
    var result = sample.resolver().resolve(sample.rows());
    assertThat(result.groups()).isEmpty();
    verifyNoInteractions(sample.mapper());
  }

  private Sample sample() throws Exception {
    var json = new ObjectMapper().findAndRegisterModules();
    var fixture = json.readTree(getClass().getResourceAsStream("/bom/alternative-missing-metadata.json"));
    List<BomRawHierarchy> rows = Arrays.asList(json.treeToValue(fixture.get("nodes"), BomRawHierarchy[].class));
    var sources = new ArrayList<>(Arrays.asList(json.treeToValue(fixture.get("sources"), BomU9Source[].class)));
    var mapper = mock(BomU9SourceMapper.class);
    when(mapper.selectAlternativeMetadata(anyString(), anyList())).thenAnswer(invocation -> {
      String organization = invocation.getArgument(0);
      List<String> parents = invocation.getArgument(1);
      return sources.stream().filter(row -> organization.equals(row.getPriceOrgCode())
          && parents.contains(row.getParentMaterialNo())).toList();
    });
    var keys = new BomAlternativeGroupKeyGeneratorImpl();
    return new Sample(rows, sources, mapper, new BomAlternativeGroupResolverImpl(keys, new U9BomAlternativeMetadata(mapper, keys)));
  }

  private List<String> quantitiesAndPaths(List<BomRawHierarchy> rows) {
    return rows.stream().map(row -> row.getPath() + "|" + row.getQtyPerParent() + "|" + row.getQtyPerTop()).toList();
  }

  private List<BomRawHierarchy> prune(List<BomRawHierarchy> rows, BomAlternativeGroupResolution groups, Map<String,String> selected) {
    return new BomAlternativeBranchPrunerImpl().prune(new BomAlternativePruneRequest(rows, groups.groups(), selected)).nodes();
  }

  private BigDecimal quantity(List<BomRawHierarchy> rows, String material) {
    return rows.stream().filter(row -> material.equals(row.getMaterialCode()))
        .map(BomRawHierarchy::getQtyPerTop).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private record Sample(List<BomRawHierarchy> rows, List<BomU9Source> sources, BomU9SourceMapper mapper,
      BomAlternativeGroupResolver resolver) {}
}
