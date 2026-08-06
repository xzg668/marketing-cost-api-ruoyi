package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeBranchPrunerImpl;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolution;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolver;
import com.sanhua.marketingcost.service.bomalternative.BomChildType;
import com.sanhua.marketingcost.service.bomalternative.QuoteAwareBomAlternativeResolverImpl;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("QBA-07 替代分支跨组织展开")
class PlateCommercialAlternativeBranchExpansionTest {

  @BeforeAll
  static void initTableInfo() {
    Qba07FormalBomTestSupport.initTableInfo();
  }

  @Test
  @DisplayName("默认标准件时未选替代分支缺少跨组织结构不阻断")
  void missingStructureInUnselectedAlternativeDoesNotBlock() {
    List<BomRawHierarchy> all = Qba07FormalBomTestSupport.alternativeTree();
    var group = Qba07FormalBomTestSupport.mainGroup(all);
    BomAlternativeGroupResolver groupResolver = mock(BomAlternativeGroupResolver.class);
    QuoteBomAlternativeSelectionService selectionService =
        mock(QuoteBomAlternativeSelectionService.class);
    when(groupResolver.resolve(any()))
        .thenReturn(new BomAlternativeGroupResolution(List.of(group), List.of()));
    when(selectionService.ensureDefault(any(), any()))
        .thenReturn(
            Qba07FormalBomTestSupport.selection(
                group.alternativeGroupKey(), "STD", "STD", BomChildType.STANDARD));
    var resolver =
        new QuoteAwareBomAlternativeResolverImpl(
            groupResolver, selectionService, new BomAlternativeBranchPrunerImpl());
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper masterMapper = mock(MaterialMasterRawMapper.class);
    PlateCommercialMakeBomExpansionService expansion =
        mock(PlateCommercialMakeBomExpansionService.class);
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(all);
    ArgumentCaptor<List<BomRawHierarchy>> inputCaptor =
        ArgumentCaptor.forClass(List.class);
    when(expansion.expand(inputCaptor.capture(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                new PlateCommercialMakeBomExpansionService.ExpansionResult(
                    invocation.getArgument(0), Map.of(), Map.of(), List.of()));
    when(masterMapper.selectByLatestBatchAndCodes(any(), isNull(), any()))
        .thenReturn(List.of());

    var result =
        new FormalBomReadServiceImpl(bomMapper, masterMapper, expansion, resolver)
            .read(Qba07FormalBomTestSupport.context());

    assertThat(result.found()).isTrue();
    assertThat(inputCaptor.getValue())
        .extracting(BomRawHierarchy::getMaterialCode)
        .contains("STD", "STD-RAW")
        .doesNotContain("ALT", "ALT-RAW");
  }

  @Test
  @DisplayName("选择替代件后跨组织展开只接收替代分支")
  void expandsOnlySelectedAlternativeBranch() {
    List<BomRawHierarchy> all = Qba07FormalBomTestSupport.alternativeTree();
    var group = Qba07FormalBomTestSupport.mainGroup(all);
    BomAlternativeGroupResolver groupResolver = mock(BomAlternativeGroupResolver.class);
    QuoteBomAlternativeSelectionService selectionService =
        mock(QuoteBomAlternativeSelectionService.class);
    when(groupResolver.resolve(any()))
        .thenReturn(new BomAlternativeGroupResolution(List.of(group), List.of()));
    when(selectionService.ensureDefault(any(), any()))
        .thenReturn(
            Qba07FormalBomTestSupport.selection(
                group.alternativeGroupKey(), "STD", "ALT", BomChildType.ALTERNATIVE));
    var resolver =
        new QuoteAwareBomAlternativeResolverImpl(
            groupResolver, selectionService, new BomAlternativeBranchPrunerImpl());
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper masterMapper = mock(MaterialMasterRawMapper.class);
    PlateCommercialMakeBomExpansionService expansion =
        mock(PlateCommercialMakeBomExpansionService.class);
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(all);
    ArgumentCaptor<List<BomRawHierarchy>> inputCaptor =
        ArgumentCaptor.forClass(List.class);
    when(expansion.expand(inputCaptor.capture(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              List<BomRawHierarchy> input = invocation.getArgument(0);
              List<BomRawHierarchy> expanded = new ArrayList<>(input);
              expanded.add(
                  Qba07FormalBomTestSupport.row(
                      20L,
                      "ALT-COMMERCIAL-RAW",
                      "ALT",
                      4,
                      "/TOP/PARENT/ALT/ALT-RAW/ALT-COMMERCIAL-RAW/",
                      null,
                      null));
              return new PlateCommercialMakeBomExpansionService.ExpansionResult(
                  expanded, Map.of(), Map.of(), List.of());
            });
    when(masterMapper.selectByLatestBatchAndCodes(any(), isNull(), any()))
        .thenReturn(List.of());

    var result =
        new FormalBomReadServiceImpl(bomMapper, masterMapper, expansion, resolver)
            .read(Qba07FormalBomTestSupport.context());

    assertThat(inputCaptor.getValue())
        .extracting(BomRawHierarchy::getMaterialCode)
        .contains("ALT", "ALT-RAW")
        .doesNotContain("STD", "STD-RAW");
    assertThat(result.lines())
        .extracting(QuoteBomSourceLineDto::materialCode)
        .contains("ALT-COMMERCIAL-RAW");
  }

  @Test
  @DisplayName("选中分支跨组织制造BOM缺失时返回稳定阻断码")
  void selectedBranchMissingStructureBlocks() {
    List<BomRawHierarchy> rows =
        List.of(
            Qba07FormalBomTestSupport.row(1L, "TOP", "TOP", 0, "/TOP/", null, null),
            Qba07FormalBomTestSupport.row(2L, "STD", "TOP", 1, "/TOP/STD/", null, null));
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper masterMapper = mock(MaterialMasterRawMapper.class);
    PlateCommercialMakeBomExpansionService expansion =
        mock(PlateCommercialMakeBomExpansionService.class);
    com.sanhua.marketingcost.service.bomalternative.QuoteAwareBomAlternativeResolver resolver =
        mock(com.sanhua.marketingcost.service.bomalternative.QuoteAwareBomAlternativeResolver.class);
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(rows);
    when(resolver.resolve(any(), any()))
        .thenReturn(
            new com.sanhua.marketingcost.service.bomalternative.BomAlternativePruneResult(
                rows, 2, 2, 0, 0, 0, List.of(), List.of(), List.of()));
    when(expansion.expand(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new PlateCommercialMakeBomExpansionService.ExpansionResult(
                rows, Map.of(), Map.of(), List.of("STD缺少商用制造BOM")));

    var result =
        new FormalBomReadServiceImpl(bomMapper, masterMapper, expansion, resolver)
            .read(Qba07FormalBomTestSupport.context());

    assertThat(result.found()).isFalse();
    assertThat(result.gapMessage()).startsWith("ALT_BRANCH_STRUCTURE_MISSING");
  }
}
