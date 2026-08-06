package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeBranchPrunerImpl;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroup;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolution;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolver;
import com.sanhua.marketingcost.service.bomalternative.BomChildType;
import com.sanhua.marketingcost.service.bomalternative.QuoteAwareBomAlternativeResolver;
import com.sanhua.marketingcost.service.bomalternative.QuoteAwareBomAlternativeResolverImpl;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionException;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QBA-07 报价正式BOM替代选择")
class FormalBomReadAlternativeSelectionTest {

  @Test
  @DisplayName("首次报价默认标准件，只保留标准件整棵分支")
  void defaultsToStandardBranch() {
    List<BomRawHierarchy> rows = Qba07FormalBomTestSupport.alternativeTree();
    BomAlternativeGroup group = Qba07FormalBomTestSupport.mainGroup(rows);
    QuoteBomAlternativeSelectionService selectionService =
        mock(QuoteBomAlternativeSelectionService.class);
    when(selectionService.ensureDefault(any(), any()))
        .thenReturn(
            Qba07FormalBomTestSupport.selection(
                group.alternativeGroupKey(), "STD", "STD", BomChildType.STANDARD));

    var result = resolver(group, selectionService).resolve(Qba07FormalBomTestSupport.context(), rows);

    assertThat(result.nodes())
        .extracting(BomRawHierarchy::getMaterialCode)
        .contains("STD", "STD-RAW")
        .doesNotContain("ALT", "ALT-RAW");
  }

  @Test
  @DisplayName("已有人工替代选择时只保留替代件整棵分支")
  void keepsManuallySelectedAlternativeBranch() {
    List<BomRawHierarchy> rows = Qba07FormalBomTestSupport.alternativeTree();
    BomAlternativeGroup group = Qba07FormalBomTestSupport.mainGroup(rows);
    QuoteBomAlternativeSelectionService selectionService =
        mock(QuoteBomAlternativeSelectionService.class);
    when(selectionService.ensureDefault(any(), any()))
        .thenReturn(
            Qba07FormalBomTestSupport.selection(
                group.alternativeGroupKey(), "STD", "ALT", BomChildType.ALTERNATIVE));

    var result = resolver(group, selectionService).resolve(Qba07FormalBomTestSupport.context(), rows);

    assertThat(result.nodes())
        .extracting(BomRawHierarchy::getMaterialCode)
        .contains("ALT", "ALT-RAW")
        .doesNotContain("STD", "STD-RAW");
  }

  @Test
  @DisplayName("父级未选中的替代分支内嵌套组不创建默认选择")
  void doesNotCreateSelectionForNestedGroupInRemovedBranch() {
    List<BomRawHierarchy> rows =
        new ArrayList<>(Qba07FormalBomTestSupport.alternativeTree());
    BomRawHierarchy nestedStandard =
        Qba07FormalBomTestSupport.row(
            7L,
            "NEST-STD",
            "ALT",
            4,
            "/TOP/PARENT/ALT/ALT-RAW/NEST-STD/",
            "GROUP-NEST",
            "STANDARD");
    BomRawHierarchy nestedAlternative =
        Qba07FormalBomTestSupport.row(
            8L,
            "NEST-ALT",
            "ALT",
            4,
            "/TOP/PARENT/ALT/ALT-RAW/NEST-ALT/",
            "GROUP-NEST",
            "ALTERNATIVE");
    rows.add(nestedStandard);
    rows.add(nestedAlternative);
    BomAlternativeGroup main = Qba07FormalBomTestSupport.mainGroup(rows);
    BomAlternativeGroup nested =
        Qba07FormalBomTestSupport.group(
            "GROUP-NEST", "ALT-RAW", nestedStandard, nestedAlternative);
    QuoteBomAlternativeSelectionService selectionService =
        mock(QuoteBomAlternativeSelectionService.class);
    when(selectionService.ensureDefault(any(), any()))
        .thenReturn(
            Qba07FormalBomTestSupport.selection(
                main.alternativeGroupKey(), "STD", "STD", BomChildType.STANDARD));

    var result =
        resolver(List.of(main, nested), selectionService)
            .resolve(Qba07FormalBomTestSupport.context(), rows);

    verify(selectionService, times(1)).ensureDefault(any(), any());
    assertThat(result.skippedGroupKeys()).containsExactly("GROUP-NEST");
  }

  @Test
  @DisplayName("历史选择失效时不静默回到标准件")
  void staleSelectionBlocksQuoteRead() {
    List<BomRawHierarchy> rows = Qba07FormalBomTestSupport.alternativeTree();
    BomAlternativeGroup group = Qba07FormalBomTestSupport.mainGroup(rows);
    QuoteBomAlternativeSelectionService selectionService =
        mock(QuoteBomAlternativeSelectionService.class);
    when(selectionService.ensureDefault(any(), any()))
        .thenReturn(
            new com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionResult(
                null,
                group.alternativeGroupKey(),
                "STD",
                "STD",
                BomChildType.STANDARD,
                "AUTO_STANDARD",
                1,
                "PREVIEW",
                false,
                true,
                false,
                "IMPORT-1",
                "BUILD-1"));

    assertThatThrownBy(
            () ->
                resolver(group, selectionService)
                    .resolve(Qba07FormalBomTestSupport.context(), rows))
        .isInstanceOf(QuoteBomAlternativeSelectionException.class)
        .hasMessageContaining("ALT_SOURCE_STALE");
  }

  private QuoteAwareBomAlternativeResolver resolver(
      BomAlternativeGroup group,
      QuoteBomAlternativeSelectionService selectionService) {
    return resolver(List.of(group), selectionService);
  }

  private QuoteAwareBomAlternativeResolver resolver(
      List<BomAlternativeGroup> groups,
      QuoteBomAlternativeSelectionService selectionService) {
    BomAlternativeGroupResolver groupResolver = mock(BomAlternativeGroupResolver.class);
    when(groupResolver.resolve(any())).thenReturn(new BomAlternativeGroupResolution(groups, List.of()));
    return new QuoteAwareBomAlternativeResolverImpl(
        groupResolver, selectionService, new BomAlternativeBranchPrunerImpl());
  }
}
