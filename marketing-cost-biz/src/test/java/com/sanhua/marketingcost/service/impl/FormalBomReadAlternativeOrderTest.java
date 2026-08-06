package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativePruneResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteAwareBomAlternativeResolver;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

@DisplayName("QBA-07 正式BOM读取执行顺序")
class FormalBomReadAlternativeOrderTest {

  @BeforeAll
  static void initTableInfo() {
    Qba07FormalBomTestSupport.initTableInfo();
  }

  @Test
  @DisplayName("报价读取严格先裁剪替代分支，再执行跨组织展开")
  void prunesBeforeCrossOrganizationExpansion() {
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper masterMapper = mock(MaterialMasterRawMapper.class);
    QuoteAwareBomAlternativeResolver resolver = mock(QuoteAwareBomAlternativeResolver.class);
    PlateCommercialMakeBomExpansionService expansion =
        mock(PlateCommercialMakeBomExpansionService.class);
    List<BomRawHierarchy> all = Qba07FormalBomTestSupport.alternativeTree();
    List<BomRawHierarchy> selected =
        all.stream().filter(row -> !row.getPath().contains("/ALT/")).toList();
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(all);
    when(resolver.resolve(any(), any()))
        .thenReturn(
            new BomAlternativePruneResult(
                selected, all.size(), selected.size(), 2, 1, 0, List.of("GROUP-MAIN"), List.of(), List.of()));
    when(expansion.expand(any(), any(), any(), any(), any(), any()))
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
    assertThat(result.lines())
        .extracting(QuoteBomSourceLineDto::materialCode)
        .doesNotContain("ALT", "ALT-RAW");
    InOrder order = inOrder(resolver, expansion);
    order.verify(resolver).resolve(any(), any());
    order.verify(expansion).expand(any(), any(), any(), any(), any(), any());

    ArgumentCaptor<Wrapper<BomRawHierarchy>> queryCaptor =
        ArgumentCaptor.forClass(Wrapper.class);
    org.mockito.Mockito.verify(bomMapper).selectList(queryCaptor.capture());
    AbstractWrapper<?, ?, ?> wrapper =
        (AbstractWrapper<?, ?, ?>) queryCaptor.getValue();
    assertThat(wrapper.getSqlSegment()).contains("bom_purpose");
    assertThat(wrapper.getParamNameValuePairs()).containsValue("主制造");
  }

  @Test
  @DisplayName("普通BOM树查询保留全部标准和替代候选且不读写报价选择")
  void ordinaryTreeKeepsAllCandidates() {
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper masterMapper = mock(MaterialMasterRawMapper.class);
    QuoteAwareBomAlternativeResolver resolver = mock(QuoteAwareBomAlternativeResolver.class);
    PlateCommercialMakeBomExpansionService expansion =
        mock(PlateCommercialMakeBomExpansionService.class);
    List<BomRawHierarchy> all = Qba07FormalBomTestSupport.alternativeTree();
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(all);
    when(expansion.expand(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new PlateCommercialMakeBomExpansionService.ExpansionResult(
                all, Map.of(), Map.of(), List.of()));
    when(masterMapper.selectByLatestBatchAndCodes(any(), isNull(), any()))
        .thenReturn(List.of());

    var result =
        new FormalBomReadServiceImpl(bomMapper, masterMapper, expansion, resolver)
            .read(
                "TOP",
                "2026-07",
                "主制造",
                LocalDate.of(2026, 7, 30),
                new QuoteDataOrganization("210", "COMMERCIAL"));

    assertThat(result.lines())
        .filteredOn(line -> line.alternativeGroupKey() != null)
        .extracting(QuoteBomSourceLineDto::materialCode)
        .containsExactly("ALT", "STD");
    assertThat(result.lines())
        .filteredOn(line -> line.alternativeGroupKey() != null)
        .extracting(
            QuoteBomSourceLineDto::childType,
            QuoteBomSourceLineDto::alternativeGroupKey)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("STANDARD", "GROUP-MAIN"),
            org.assertj.core.groups.Tuple.tuple("ALTERNATIVE", "GROUP-MAIN"));
    verifyNoInteractions(resolver);
  }
}
