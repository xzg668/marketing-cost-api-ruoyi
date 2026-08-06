package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
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

@DisplayName("QBA-07 无替代BOM回归")
class FormalBomReadNoAlternativeRegressionTest {

  @BeforeAll
  static void initTableInfo() {
    Qba07FormalBomTestSupport.initTableInfo();
  }

  @Test
  @DisplayName("无替代BOM的报价读取与原普通读取逐行完全一致")
  void quoteAndOrdinaryReadReturnIdenticalRows() {
    BomRawHierarchyMapper bomMapper = mock(BomRawHierarchyMapper.class);
    MaterialMasterRawMapper masterMapper = mock(MaterialMasterRawMapper.class);
    QuoteAwareBomAlternativeResolver resolver = mock(QuoteAwareBomAlternativeResolver.class);
    PlateCommercialMakeBomExpansionService expansion =
        mock(PlateCommercialMakeBomExpansionService.class);
    List<BomRawHierarchy> rows =
        List.of(
            Qba07FormalBomTestSupport.row(1L, "TOP", "TOP", 0, "/TOP/", null, null),
            Qba07FormalBomTestSupport.row(2L, "NORMAL", "TOP", 1, "/TOP/NORMAL/", null, null));
    when(bomMapper.selectList(any(Wrapper.class))).thenReturn(rows, rows);
    when(resolver.resolve(any(), any()))
        .thenReturn(
            new BomAlternativePruneResult(
                rows, rows.size(), rows.size(), 0, 0, 0, List.of(), List.of(), List.of()));
    when(expansion.expand(any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                new PlateCommercialMakeBomExpansionService.ExpansionResult(
                    invocation.getArgument(0), Map.of(), Map.of(), List.of()));
    when(masterMapper.selectByLatestBatchAndCodes(any(), isNull(), any()))
        .thenReturn(List.of());
    FormalBomReadServiceImpl service =
        new FormalBomReadServiceImpl(bomMapper, masterMapper, expansion, resolver);

    var ordinary =
        service.read(
            "TOP",
            "2026-07",
            "主制造",
            LocalDate.of(2026, 7, 30),
            new QuoteDataOrganization("210", "COMMERCIAL"));
    var quote = service.read(Qba07FormalBomTestSupport.context());

    assertThat(quote.lines()).containsExactlyElementsOf(ordinary.lines());
    verify(resolver, times(1)).resolve(any(), any());
  }
}
