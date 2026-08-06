package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeBranchPruner;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeCandidate;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroup;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupIdentity;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolution;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolver;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativePruneResult;
import com.sanhua.marketingcost.service.bomalternative.BomChildType;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeConfirmationGuardImpl;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionScope;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteBomAlternativeConfirmationGuardTest {

  private BomRawHierarchyMapper rawMapper;
  private BomAlternativeGroupResolver groupResolver;
  private BomAlternativeBranchPruner branchPruner;
  private QuoteBomAlternativeSelectionService selectionService;
  private QuoteBomAlternativeConfirmationGuardImpl guard;
  private List<BomRawHierarchy> rows;
  private List<BomAlternativeGroup> groups;

  @BeforeEach
  void setUp() {
    rawMapper = mock(BomRawHierarchyMapper.class);
    groupResolver = mock(BomAlternativeGroupResolver.class);
    branchPruner = mock(BomAlternativeBranchPruner.class);
    selectionService = mock(QuoteBomAlternativeSelectionService.class);
    guard =
        new QuoteBomAlternativeConfirmationGuardImpl(
            rawMapper, groupResolver, branchPruner, selectionService);
    rows =
        List.of(
            raw("SOURCE-TOP", "/SOURCE-TOP/"),
            raw("PARENT-1", "/SOURCE-TOP/PARENT-1@10/"),
            raw(
                "STD-1",
                "/SOURCE-TOP/PARENT-1@10/STD-1@20/"));
    groups = List.of(group("GROUP-1", "PARENT-1", "STD-1", "ALT-1"));
    when(rawMapper.selectList(any())).thenReturn(rows);
    when(groupResolver.resolve(rows))
        .thenReturn(new BomAlternativeGroupResolution(groups, List.of()));
    when(selectionService.findCurrent(any(), any())).thenReturn(null);
    when(branchPruner.prune(any()))
        .thenReturn(new BomAlternativePruneResult(rows, 3, 3, 0, 1, 0,
            List.of("GROUP-1"), List.of(), List.of()));
  }

  @Test
  void countsOnlyCurrentManualAlternativeGroups() {
    when(selectionService.synchronize(any(), any()))
        .thenReturn(
            List.of(
                selection(
                    "GROUP-1",
                    "ALT-1",
                    "MANUAL_ALTERNATIVE",
                    false,
                    true)));

    int count =
        guard.validateAndCountManualAlternatives(
            scope(), LocalDate.of(2026, 7, 30), "主制造");

    assertThat(count).isEqualTo(1);
  }

  @Test
  void defaultOrManuallyRestoredStandardDoesNotCountAsReplacement() {
    when(selectionService.synchronize(any(), any()))
        .thenReturn(
            List.of(
                selection(
                    "GROUP-1",
                    "STD-1",
                    "MANUAL_STANDARD",
                    false,
                    true)));

    int count =
        guard.validateAndCountManualAlternatives(
            scope(), LocalDate.of(2026, 7, 30), "主制造");

    assertThat(count).isZero();
  }

  @Test
  void staleSelectionBlocksBomConfirmation() {
    when(selectionService.synchronize(any(), any()))
        .thenReturn(
            List.of(
                selection(
                    "GROUP-1",
                    "STD-1",
                    "AUTO_STANDARD",
                    true,
                    false)));

    assertThatThrownBy(
            () ->
                guard.validateAndCountManualAlternatives(
                    scope(), LocalDate.of(2026, 7, 30), "主制造"))
        .isInstanceOf(QuoteIngestException.class)
        .hasMessageContaining("ALT_SOURCE_STALE")
        .hasMessageContaining("重新确认");
  }

  @Test
  void bomWithoutAlternativeGroupRemainsCompatible() {
    when(groupResolver.resolve(rows))
        .thenReturn(new BomAlternativeGroupResolution(List.of(), List.of()));

    int count =
        guard.validateAndCountManualAlternatives(
            scope(), LocalDate.of(2026, 7, 30), "主制造");

    assertThat(count).isZero();
  }

  private QuoteBomAlternativeSelectionScope scope() {
    return new QuoteBomAlternativeSelectionScope(
        "OA-QBA-10",
        10L,
        "SOURCE-TOP",
        "2026-07",
        "210",
        "COMMERCIAL");
  }

  private BomAlternativeGroup group(
      String key,
      String parent,
      String standard,
      String alternative) {
    return new BomAlternativeGroup(
        new BomAlternativeGroupIdentity(
            "210",
            "SOURCE-TOP",
            "/SOURCE-TOP/" + parent + "/",
            parent,
            "主制造",
            "V1",
            LocalDate.of(2026, 1, 1),
            null,
            20,
            "010"),
        key,
        List.of(
            candidate(1L, standard, BomChildType.STANDARD),
            candidate(2L, alternative, BomChildType.ALTERNATIVE)));
  }

  private BomAlternativeCandidate candidate(
      Long id, String materialCode, BomChildType childType) {
    return new BomAlternativeCandidate(
        id,
        materialCode,
        materialCode,
        null,
        childType,
        BigDecimal.ONE,
        "/SOURCE-TOP/PARENT-1@10/" + materialCode + "@20/",
        "IMPORT-1",
        "BUILD-1");
  }

  private QuoteBomAlternativeSelectionResult selection(
      String key,
      String selected,
      String source,
      boolean reviewRequired,
      boolean persisted) {
    return new QuoteBomAlternativeSelectionResult(
        persisted ? "SEL-1" : null,
        key,
        "STD-1",
        selected,
        selected.startsWith("ALT")
            ? BomChildType.ALTERNATIVE
            : BomChildType.STANDARD,
        source,
        1,
        persisted ? "ACTIVE" : "PREVIEW",
        false,
        reviewRequired,
        persisted,
        "IMPORT-1",
        "BUILD-1");
  }

  private BomRawHierarchy raw(String materialCode, String path) {
    BomRawHierarchy row = new BomRawHierarchy();
    row.setTopProductCode("SOURCE-TOP");
    row.setMaterialCode(materialCode);
    row.setPath(path);
    row.setLevel(
        "/SOURCE-TOP/".equals(path)
            ? 0
            : path.contains("STD-1")
                ? 2
                : 1);
    return row;
  }
}
