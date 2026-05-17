package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.FormulaFactorRef;
import com.sanhua.marketingcost.dto.ResolvedFactorRef;
import com.sanhua.marketingcost.entity.FactorRowRef;
import com.sanhua.marketingcost.mapper.FactorRowRefMapper;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PriceLinkedFormulaFactorRefResolverImplTest {

  private FactorRowRefMapper factorRowRefMapper;
  private PriceLinkedFormulaFactorRefResolverImpl resolver;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, FactorRowRef.class);
  }

  @BeforeEach
  void setUp() {
    factorRowRefMapper = mock(FactorRowRefMapper.class);
    resolver = new PriceLinkedFormulaFactorRefResolverImpl(factorRowRefMapper);
  }

  @Test
  @DisplayName("resolve：影响因素!E64 能通过 batch + sheet + row 找到身份")
  void resolveExactSheetAndRow() {
    when(factorRowRefMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(rowRef("影响因素", 64, 1001L, 2001L)));

    List<ResolvedFactorRef> result =
        resolver.resolve(10L, List.of(ref("影响因素", 64)));

    assertThat(result).hasSize(1);
    ResolvedFactorRef resolved = result.getFirst();
    assertThat(resolved.isResolved()).isTrue();
    assertThat(resolved.getSheetName()).isEqualTo("影响因素");
    assertThat(resolved.getRowNumber()).isEqualTo(64);
    assertThat(resolved.getFactorIdentityId()).isEqualTo(1001L);
    assertThat(resolved.getFactorMonthlyPriceId()).isEqualTo(2001L);
    assertThat(resolved.getFactorSeqNo()).isEqualTo("64");
    assertThat(resolved.getShortName()).isEqualTo("SUS304/2B");
    assertThat(resolved.getPriceSource()).isEqualTo("出厂价");
    assertThat(resolved.getPrice()).isEqualByComparingTo(new BigDecimal("16.4"));
    assertThat(resolved.getWarning()).isNull();
    assertThat(resolved.getErrorMessage()).isNull();
  }

  @Test
  @DisplayName("resolve：找不到行号时返回错误，不生成错误绑定身份")
  void resolveMissingRowReturnsError() {
    when(factorRowRefMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(rowRef("影响因素", 64, 1001L, 2001L)));

    ResolvedFactorRef resolved = resolver.resolve(10L, List.of(ref("影响因素", 44))).getFirst();

    assertThat(resolved.isResolved()).isFalse();
    assertThat(resolved.getFactorIdentityId()).isNull();
    assertThat(resolved.getFactorMonthlyPriceId()).isNull();
    assertThat(resolved.getErrorMessage()).contains("找不到影响因素引用");
  }

  @Test
  @DisplayName("resolve：sheet 名不匹配且批次有多个影响因素 sheet 时不盲猜")
  void resolveSheetMismatchWithMultipleSheetsDoesNotGuess() {
    when(factorRowRefMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(
            rowRef("影响因素A", 64, 1001L, 2001L),
            rowRef("影响因素B", 64, 1002L, 2002L)));

    ResolvedFactorRef resolved = resolver.resolve(10L, List.of(ref("影响因素X", 64))).getFirst();

    assertThat(resolved.isResolved()).isFalse();
    assertThat(resolved.getFactorIdentityId()).isNull();
    assertThat(resolved.getErrorMessage()).contains("多个影响因素 sheet");
    assertThat(resolved.getWarning()).isNull();
  }

  @Test
  @DisplayName("resolve：sheet 名不匹配但批次只有一个影响因素 sheet 时按行号匹配并记录 warning")
  void resolveSheetMismatchWithSingleSheetFallsBackWithWarning() {
    when(factorRowRefMapper.selectList(any(Wrapper.class)))
        .thenReturn(List.of(rowRef("本月影响因素", 64, 1001L, 2001L)));

    ResolvedFactorRef resolved = resolver.resolve(10L, List.of(ref("影响因素10", 64))).getFirst();

    assertThat(resolved.isResolved()).isTrue();
    assertThat(resolved.getFactorIdentityId()).isEqualTo(1001L);
    assertThat(resolved.getWarning()).contains("本批次只有一个影响因素 sheet");
    assertThat(resolved.getWarning()).contains("本月影响因素");
    assertThat(resolved.getErrorMessage()).isNull();
  }

  @Test
  @DisplayName("resolve：batchId 为空时直接返回错误且不查询数据库")
  void resolveMissingBatchIdReturnsErrorWithoutQuery() {
    ResolvedFactorRef resolved = resolver.resolve(null, List.of(ref("影响因素", 64))).getFirst();

    assertThat(resolved.isResolved()).isFalse();
    assertThat(resolved.getErrorMessage()).contains("factorUploadBatchId 必填");
    verify(factorRowRefMapper, never()).selectList(any());
  }

  private FormulaFactorRef ref(String sheetName, Integer rowNumber) {
    FormulaFactorRef ref = new FormulaFactorRef();
    ref.setWorkbookName("monthly.xlsx");
    ref.setSheetName(sheetName);
    ref.setColumnName("E");
    ref.setRowNumber(rowNumber);
    ref.setRawRef(sheetName + "!$E$" + rowNumber);
    ref.setOrderIndex(1);
    return ref;
  }

  private FactorRowRef rowRef(
      String sheetName, Integer rowNumber, Long factorIdentityId, Long factorMonthlyPriceId) {
    FactorRowRef rowRef = new FactorRowRef();
    rowRef.setFactorUploadBatchId(10L);
    rowRef.setSourceWorkbookName("monthly.xlsx");
    rowRef.setSourceSheetName(sheetName);
    rowRef.setSourceRowNumber(rowNumber);
    rowRef.setFactorIdentityId(factorIdentityId);
    rowRef.setFactorMonthlyPriceId(factorMonthlyPriceId);
    rowRef.setFactorSeqNo(String.valueOf(rowNumber));
    rowRef.setShortName("SUS304/2B");
    rowRef.setPriceSource("出厂价");
    rowRef.setPrice(new BigDecimal("16.4"));
    return rowRef;
  }
}
