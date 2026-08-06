package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorMonthlyPriceChangeLog;
import com.sanhua.marketingcost.enums.FactorPriceConflictStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-06 类型2因素价格冲突")
class PriceLinkedType2FactorConflictTest {

  @Test
  @DisplayName("同月异价默认保留已有价格并在任何写入前阻断")
  void keepExistingBlocksBeforeAnyWrite() {
    PriceLinkedType2FactorUpsertTestSupport support =
        new PriceLinkedType2FactorUpsertTestSupport();
    support.addIdentity(501L, "COMMERCIAL", "1", "铜价", "1#Cu", "平均价");
    support.addMonthlyPrice(601L, 501L, "2026-07", "90");

    FactorMonthlyPriceUpsertResult result = support.service.upsert(
        support.workbook(support.factor(
            2, "1", "铜价", "1#Cu", "平均价", "91")),
        "2026-07",
        "COMMERCIAL",
        "tester",
        99011L,
        FactorPriceConflictStrategy.KEEP_EXISTING.getCode());

    assertThat(result.getMonthlyPriceConflictCount()).isEqualTo(1);
    assertThat(result.getMonthlyPriceSkippedCount()).isEqualTo(1);
    assertThat(result.getErrors()).singleElement()
        .extracting(FactorMonthlyPriceUpsertResult.RowError::getMessage)
        .asString()
        .contains("90", "91");
    assertThat(support.monthlyPrices).singleElement()
        .extracting(FactorMonthlyPrice::getPrice)
        .isEqualTo(new java.math.BigDecimal("90"));
    verify(support.identityMapper, never()).updateById(any(FactorIdentity.class));
    verify(support.monthlyPriceMapper, never()).insert(any(FactorMonthlyPrice.class));
    verify(support.monthlyPriceMapper, never()).updateById(any(FactorMonthlyPrice.class));
    verify(support.changeLogMapper, never()).insert(any(FactorMonthlyPriceChangeLog.class));
    verify(support.factorUploadBatchService, never()).saveRowRefs(
        any(), any(FactorWorkbookParseResult.class), any(FactorMonthlyPriceUpsertResult.class));
  }

  @Test
  @DisplayName("用户明确选择覆盖后更新同月价格并记录新旧值")
  void explicitOverwriteUpdatesPriceAndWritesChangeLog() {
    PriceLinkedType2FactorUpsertTestSupport support =
        new PriceLinkedType2FactorUpsertTestSupport();
    support.addIdentity(502L, "COMMERCIAL", "1", "铜价", "1#Cu", "平均价");
    support.addMonthlyPrice(602L, 502L, "2026-07", "90");

    FactorMonthlyPriceUpsertResult result = support.service.upsert(
        support.workbook(support.factor(
            2, "1", "铜价", "1#Cu", "平均价", "91")),
        "2026-07",
        "COMMERCIAL",
        "tester",
        99012L,
        FactorPriceConflictStrategy.OVERWRITE.getCode());

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getMonthlyPriceConflictCount()).isZero();
    assertThat(result.getMonthlyPriceUpdatedCount()).isEqualTo(1);
    assertThat(result.getMonthlyPriceOverwriteCount()).isEqualTo(1);
    assertThat(result.getRows().getFirst().getMonthlyPriceAction()).isEqualTo("UPDATE");
    assertThat(support.monthlyPrices).singleElement().satisfies(price -> {
      assertThat(price.getPrice()).isEqualByComparingTo("91");
      assertThat(price.getSourceUploadBatchId()).isEqualTo(99012L);
      assertThat(price.getSourceTag()).isEqualTo("EXCEL_IMPORT");
    });
    assertThat(support.changeLogs).singleElement().satisfies(log -> {
      assertThat(log.getChangeType()).isEqualTo("UPDATE");
      assertThat(log.getOldPrice()).isEqualByComparingTo("90");
      assertThat(log.getNewPrice()).isEqualByComparingTo("91");
      assertThat(log.getSourceUploadBatchId()).isEqualTo(99012L);
      assertThat(log.getChangedBy()).isEqualTo("tester");
    });
  }

  @Test
  @DisplayName("同一统一因素的历史候选价已不一致时即使选择覆盖也阻断")
  void overwriteCannotHideConflictingAliasPrices() {
    PriceLinkedType2FactorUpsertTestSupport support =
        new PriceLinkedType2FactorUpsertTestSupport();
    support.addIdentity(503L, "COMMERCIAL", "1", "长江铜", "1#Cu", "平均价");
    support.addIdentity(504L, "COMMERCIAL", "2", "SMM铜", "1#Cu", "平均价");
    support.addMonthlyPrice(603L, 503L, "2026-07", "90");
    support.addMonthlyPrice(604L, 504L, "2026-07", "89");

    FactorMonthlyPriceUpsertResult result = support.service.upsert(
        support.workbook(support.factor(
            2, "3", "新铜名称", "1#Cu", "平均价", "91")),
        "2026-07",
        "COMMERCIAL",
        "tester",
        99013L,
        FactorPriceConflictStrategy.OVERWRITE.getCode());

    assertThat(result.getMonthlyPriceConflictCount()).isEqualTo(1);
    assertThat(result.getErrors()).singleElement()
        .extracting(FactorMonthlyPriceUpsertResult.RowError::getMessage)
        .asString()
        .contains("候选内部已不一致");
    verify(support.monthlyPriceMapper, never()).updateById(any(FactorMonthlyPrice.class));
  }
}
