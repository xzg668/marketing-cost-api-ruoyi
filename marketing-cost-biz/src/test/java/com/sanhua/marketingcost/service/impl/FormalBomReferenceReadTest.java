package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.bomalternative.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FormalBomReferenceReadTest {
  @BeforeAll static void metadata() { Qba07FormalBomTestSupport.initTableInfo(); }

  @Test void referenceUsesStandardBranchAndRetainsCumulativeQuantityWithoutWritingQuoteSelections() {
    var rows = Qba07FormalBomTestSupport.alternativeTree();
    rows.stream().filter(row -> "STD-RAW".equals(row.getMaterialCode())).forEach(row -> {
      row.setQtyPerParent(new BigDecimal("0.0002")); row.setQtyPerTop(new BigDecimal("0.0006"));
    });
    var bom = mock(BomRawHierarchyMapper.class); var materials = mock(MaterialMasterRawMapper.class);
    var quotationSelection = mock(QuoteAwareBomAlternativeResolver.class);
    var expansion = mock(PlateCommercialMakeBomExpansionService.class);
    when(bom.selectList(any(Wrapper.class))).thenReturn(rows);
    when(expansion.expand(any(), any(), any(), any(), any(), any())).thenAnswer(invocation ->
        new PlateCommercialMakeBomExpansionService.ExpansionResult(invocation.getArgument(0), Map.of(), Map.of(), List.of()));
    var service = new FormalBomReadServiceImpl(bom, materials, expansion, quotationSelection,
        new BomAlternativeGroupResolverImpl(new BomAlternativeGroupKeyGeneratorImpl()), new BomAlternativeBranchPrunerImpl());
    var result = service.readReference("TOP", "2026-09", "主制造", LocalDate.of(2026, 9, 1), new QuoteDataOrganization("210", "COMMERCIAL"));
    assertThat(result.lines()).extracting(QuoteBomSourceLineDto::materialCode).containsExactly("TOP", "PARENT", "STD", "STD-RAW");
    assertThat(result.lines().getLast().qtyPerTop()).isEqualByComparingTo("0.0006");
    assertThat(rows).hasSize(6);
    verifyNoInteractions(quotationSelection);
    verify(bom, never()).insert(any(BomRawHierarchy.class));
  }
}
