package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.dto.PriceLinkedType2WorkbookParseResult;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.FactorMonthlyPriceChangeLog;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

@DisplayName("PLI2-06 类型2影响因素月度价格写入")
class PriceLinkedType2FactorMonthlyUpsertTest {

  private static final Path TYPE2_SAMPLE = Path.of(
      "/Users/xiexicheng/Desktop/price/采购价表二次开发导入模板-股份251115联动价格导入类型2.xls");

  @Test
  @DisplayName("真实 Excel 的 Cu=90、Zn=21.68 复用现有身份和月度价格")
  void realCuAndZnReuseExistingPricesAndSaveSourceRows() throws Exception {
    PriceLinkedType2FactorUpsertTestSupport support =
        new PriceLinkedType2FactorUpsertTestSupport();
    support.addIdentity(191L, "COMMERCIAL", "5", "旧长江铜名称", "1#Cu", "平均价");
    support.addIdentity(193L, "COMMERCIAL", "7", "旧SMM铜名称", "1#Cu", "平均价");
    support.addIdentity(208L, "COMMERCIAL", "22", "旧月长江铜名称", "1#Cu", "平均价");
    support.addIdentity(192L, "COMMERCIAL", "6", "旧长江锌名称", "1#Zn", "平均价");
    support.addIdentity(194L, "COMMERCIAL", "8", "旧SMM锌名称", "1#Zn", "平均价");
    support.addMonthlyPrice(316L, 191L, "2026-07", "90");
    support.addMonthlyPrice(318L, 193L, "2026-07", "90");
    support.addMonthlyPrice(333L, 208L, "2026-07", "90");
    support.addMonthlyPrice(317L, 192L, "2026-07", "21.68");
    support.addMonthlyPrice(319L, 194L, "2026-07", "21.680000");
    support.bindingCounts.put(191L, 47L);
    support.bindingCounts.put(193L, 6L);
    support.bindingCounts.put(208L, 1L);
    support.bindingCounts.put(192L, 14L);
    support.bindingCounts.put(194L, 4L);
    assertThat(Files.exists(TYPE2_SAMPLE)).as("真实类型2样例存在").isTrue();
    PriceLinkedType2WorkbookParseResult workbook =
        new PriceLinkedType2WorkbookParserImpl(new PriceLinkedWorkbookTypeDetectorImpl())
            .parse(
                new ByteArrayInputStream(Files.readAllBytes(TYPE2_SAMPLE)),
                TYPE2_SAMPLE.getFileName().toString());

    FactorMonthlyPriceUpsertResult result = support.service.upsert(
        workbook, "2026-07", "COMMERCIAL", "tester", 99001L);

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getIdentityCreatedCount()).isZero();
    assertThat(result.getIdentityReusedCount()).isEqualTo(2);
    assertThat(result.getMonthlyPriceUnchangedCount()).isEqualTo(2);
    assertThat(result.getMonthlyPriceCreatedCount()).isZero();
    assertThat(result.getMonthlyPriceUpdatedCount()).isZero();
    assertThat(result.getRows())
        .extracting(FactorMonthlyPriceUpsertResult.RowResult::getFactorIdentityId)
        .containsExactly(191L, 192L);
    assertThat(result.getRows())
        .extracting(FactorMonthlyPriceUpsertResult.RowResult::getIdentityAction)
        .containsOnly("REUSE");
    assertThat(result.getRows())
        .extracting(FactorMonthlyPriceUpsertResult.RowResult::getMonthlyPriceAction)
        .containsOnly("NO_CHANGE");
    assertThat(support.changeLogs).isEmpty();
    verify(support.monthlyPriceMapper, never()).insert(any(FactorMonthlyPrice.class));
    verify(support.monthlyPriceMapper, never()).updateById(any(FactorMonthlyPrice.class));
    verify(support.changeLogMapper, never()).insert(any(FactorMonthlyPriceChangeLog.class));

    FactorWorkbookParseResult savedSource = support.savedRowRefWorkbook.get();
    assertThat(savedSource).isNotNull();
    assertThat(savedSource.getSourceFileName()).isEqualTo(TYPE2_SAMPLE.getFileName().toString());
    assertThat(savedSource.getValidRowCount()).isEqualTo(2);
    assertThat(savedSource.getSheets().getFirst().getRows())
        .extracting("shortName", "sourceRowNumber")
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("1#Cu", 2),
            org.assertj.core.groups.Tuple.tuple("1#Zn", 3));
    assertThat(savedSource.getSheets().getFirst().getRows().get(0).getPrice())
        .isEqualByComparingTo("90");
    assertThat(savedSource.getSheets().getFirst().getRows().get(1).getPrice())
        .isEqualByComparingTo("21.68");

    FactorIdentity cuMaster = support.identities.stream()
        .filter(identity -> Long.valueOf(191L).equals(identity.getId()))
        .findFirst()
        .orElseThrow();
    assertThat(cuMaster.getCanonicalFactorKey()).isEqualTo("AVG|1#CU");
    assertThat(cuMaster.getCanonicalFactorIdentityId()).isEqualTo(191L);
    assertThat(support.identities)
        .filteredOn(identity -> "1#Cu".equalsIgnoreCase(identity.getShortName()))
        .extracting(FactorIdentity::getCanonicalFactorIdentityId)
        .containsOnly(191L);
  }

  @Test
  @DisplayName("系统首次无因素时创建稳定身份、当月价格、日志和来源行")
  void createsIdentityPriceLogAndRowReferenceWhenMissing() {
    PriceLinkedType2FactorUpsertTestSupport support =
        new PriceLinkedType2FactorUpsertTestSupport();

    FactorMonthlyPriceUpsertResult result = support.service.upsert(
        support.workbook(support.factor(
            2, "1", "新铜价完整名称", "1#Cu", "平均价", "90")),
        "2026-07",
        "COMMERCIAL",
        "tester",
        99002L);

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getIdentityCreatedCount()).isEqualTo(1);
    assertThat(result.getMonthlyPriceCreatedCount()).isEqualTo(1);
    assertThat(result.getRows().getFirst().getIdentityAction()).isEqualTo("CREATE");
    assertThat(support.identities).singleElement().satisfies(identity -> {
      assertThat(identity.getCanonicalFactorKey()).isEqualTo("AVG|1#CU");
      assertThat(identity.getCanonicalFactorIdentityId()).isEqualTo(identity.getId());
      assertThat(identity.getIdentityOrigin()).isEqualTo("TYPE2_AUTO_CREATE");
      assertThat(identity.getIdentityHash()).hasSize(64);
    });
    assertThat(support.monthlyPrices).singleElement().satisfies(price -> {
      assertThat(price.getPriceMonth()).isEqualTo("2026-07");
      assertThat(price.getPrice()).isEqualByComparingTo("90");
      assertThat(price.getSourceUploadBatchId()).isEqualTo(99002L);
      assertThat(price.getSourceTag()).isEqualTo("EXCEL_IMPORT");
    });
    assertThat(support.changeLogs).singleElement().satisfies(log -> {
      assertThat(log.getChangeType()).isEqualTo("CREATE");
      assertThat(log.getOldPrice()).isNull();
      assertThat(log.getNewPrice()).isEqualByComparingTo("90");
      assertThat(log.getSourceType()).isEqualTo("EXCEL_IMPORT");
    });
    verify(support.factorUploadBatchService, times(1)).saveRowRefs(
        org.mockito.ArgumentMatchers.eq(99002L),
        any(FactorWorkbookParseResult.class),
        any(FactorMonthlyPriceUpsertResult.class));
  }

  @Test
  @DisplayName("同一类型2因素再次导入同月同价不重复创建")
  void secondType2ImportReusesCreatedIdentityAndPrice() {
    PriceLinkedType2FactorUpsertTestSupport support =
        new PriceLinkedType2FactorUpsertTestSupport();
    PriceLinkedType2WorkbookParseResult workbook = support.workbook(support.factor(
        2, "1", "新铜价完整名称", "1#Cu", "平均价", "90"));

    FactorMonthlyPriceUpsertResult first = support.service.upsert(
        workbook, "2026-07", "COMMERCIAL", "alice", 99003L);
    FactorMonthlyPriceUpsertResult second = support.service.upsert(
        workbook, "2026-07", "COMMERCIAL", "bob", 99004L);

    assertThat(first.getIdentityCreatedCount()).isEqualTo(1);
    assertThat(second.getIdentityReusedCount()).isEqualTo(1);
    assertThat(second.getMonthlyPriceUnchangedCount()).isEqualTo(1);
    assertThat(second.getRows().getFirst().getIdentityAction()).isEqualTo("REUSE");
    assertThat(support.identities).hasSize(1);
    assertThat(support.monthlyPrices).hasSize(1);
    assertThat(support.changeLogs).hasSize(1);
    verify(support.identityMapper, times(1)).insert(any(FactorIdentity.class));
    verify(support.monthlyPriceMapper, times(1)).insert(any(FactorMonthlyPrice.class));
  }
}
