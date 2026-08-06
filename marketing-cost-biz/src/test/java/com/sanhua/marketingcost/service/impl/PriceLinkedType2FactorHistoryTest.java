package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceUpsertResult;
import com.sanhua.marketingcost.dto.FactorRowParseResult;
import com.sanhua.marketingcost.dto.FactorSheetParseResult;
import com.sanhua.marketingcost.dto.FactorWorkbookParseResult;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-06 类型2因素身份和跨月历史")
class PriceLinkedType2FactorHistoryTest {

  @Test
  @DisplayName("新月份新增价格且不改变上一月份")
  void newMonthCreatesPriceAndPreservesPreviousMonth() {
    PriceLinkedType2FactorUpsertTestSupport support =
        new PriceLinkedType2FactorUpsertTestSupport();
    support.addIdentity(701L, "COMMERCIAL", "1", "铜价", "1#Cu", "平均价");
    FactorMonthlyPrice july = support.addMonthlyPrice(801L, 701L, "2026-07", "90");

    FactorMonthlyPriceUpsertResult result = support.service.upsert(
        support.workbook(support.factor(
            2, "1", "铜价", "1#Cu", "平均价", "91")),
        "2026-08",
        "COMMERCIAL",
        "tester",
        99021L);

    assertThat(result.getErrors()).isEmpty();
    assertThat(result.getMonthlyPriceCreatedCount()).isEqualTo(1);
    assertThat(result.getMonthlyPriceUpdatedCount()).isZero();
    assertThat(support.monthlyPrices).hasSize(2);
    assertThat(july.getPrice()).isEqualByComparingTo("90");
    assertThat(support.monthlyPrices)
        .filteredOn(price -> "2026-08".equals(price.getPriceMonth()))
        .singleElement()
        .extracting(FactorMonthlyPrice::getPrice)
        .isEqualTo(new BigDecimal("91"));
    assertThat(support.changeLogs).singleElement()
        .extracting("changeType", "priceMonth")
        .containsExactly("CREATE", "2026-08");
  }

  @Test
  @DisplayName("只导入 Cu 不删除系统中未出现的其他因素和价格")
  void omittedFactorsRemainUntouched() {
    PriceLinkedType2FactorUpsertTestSupport support =
        new PriceLinkedType2FactorUpsertTestSupport();
    support.addIdentity(702L, "COMMERCIAL", "1", "铜价", "1#Cu", "平均价");
    support.addIdentity(703L, "COMMERCIAL", "2", "镍价", "1#Ni", "平均价");
    support.addMonthlyPrice(802L, 702L, "2026-07", "90");
    FactorMonthlyPrice nickel =
        support.addMonthlyPrice(803L, 703L, "2026-07", "16.5");

    FactorMonthlyPriceUpsertResult result = support.service.upsert(
        support.workbook(support.factor(
            2, "1", "铜价", "1#Cu", "平均价", "90")),
        "2026-07",
        "COMMERCIAL",
        "tester",
        99022L);

    assertThat(result.getErrors()).isEmpty();
    assertThat(support.identities).hasSize(2);
    assertThat(support.monthlyPrices).hasSize(2);
    assertThat(nickel.getPrice()).isEqualByComparingTo("16.5");
  }

  @Test
  @DisplayName("类型2首次创建后，完整影响因素表复用同一身份并补全名称")
  void laterStandardFactorImportReusesType2Identity() {
    PriceLinkedType2FactorUpsertTestSupport support =
        new PriceLinkedType2FactorUpsertTestSupport();
    support.service.upsert(
        support.workbook(support.factor(
            2, "1", "简化铜名称", "1#Cu", "平均价", "90")),
        "2026-07",
        "COMMERCIAL",
        "tester",
        99023L);
    FactorIdentity type2Identity = support.identities.getFirst();
    Long stableIdentityId = type2Identity.getId();
    when(support.identityMapper.selectOne(any(Wrapper.class))).thenReturn(null);
    when(support.identityMapper.selectList(any(Wrapper.class)))
        .thenAnswer(invocation -> List.copyOf(support.identities));
    when(support.monthlyPriceMapper.selectOne(any(Wrapper.class)))
        .thenAnswer(invocation -> support.monthlyPrices.getFirst());
    FactorMonthlyPriceUpsertServiceImpl standardService =
        new FactorMonthlyPriceUpsertServiceImpl(
            support.identityMapper, support.monthlyPriceMapper, support.changeLogMapper);
    PriceLinkedType2TextNormalizerImpl textNormalizer =
        new PriceLinkedType2TextNormalizerImpl();
    standardService.setFactorCanonicalKeyService(
        new FactorCanonicalKeyServiceImpl(textNormalizer));

    FactorMonthlyPriceUpsertResult standardResult = standardService.upsert(
        standardWorkbook(
            "5",
            "上月16日至本月15日中华商务网长江现货市场1#电解铜含税平均价格",
            "1#Cu",
            "平均价",
            "90"),
        "2026-07",
        "COMMERCIAL",
        "finance",
        99024L);

    assertThat(standardResult.getErrors()).isEmpty();
    assertThat(standardResult.getIdentityReusedCount()).isEqualTo(1);
    assertThat(standardResult.getMonthlyPriceUnchangedCount()).isEqualTo(1);
    assertThat(standardResult.getRows().getFirst().getFactorIdentityId())
        .isEqualTo(stableIdentityId);
    assertThat(support.identities).hasSize(1).singleElement().satisfies(identity -> {
      assertThat(identity.getId()).isEqualTo(stableIdentityId);
      assertThat(identity.getFactorSeqNo()).isEqualTo("5");
      assertThat(identity.getFactorName()).contains("中华商务网长江现货市场");
      assertThat(identity.getIdentityOrigin()).isEqualTo("STANDARD_IMPORT");
      assertThat(identity.getCanonicalFactorKey()).isEqualTo("AVG|1#CU");
    });
    assertThat(support.monthlyPrices).hasSize(1);
    verify(support.identityMapper, times(1)).insert(any(FactorIdentity.class));
    verify(support.monthlyPriceMapper, times(1)).insert(any(FactorMonthlyPrice.class));
  }

  private FactorWorkbookParseResult standardWorkbook(
      String seq,
      String name,
      String shortName,
      String source,
      String price) {
    FactorRowParseResult row = new FactorRowParseResult();
    row.setSourceSheetName("影响因素");
    row.setSourceRowNumber(7);
    row.setFactorSeqNo(seq);
    row.setFactorName(name);
    row.setShortName(shortName);
    row.setPriceSource(source);
    row.setPrice(new BigDecimal(price));
    row.setOriginalPrice(new BigDecimal(price));
    row.setUnit("公斤");
    FactorSheetParseResult sheet = new FactorSheetParseResult();
    sheet.setSheetName("影响因素");
    sheet.getRows().add(row);
    FactorWorkbookParseResult workbook = new FactorWorkbookParseResult();
    workbook.setSourceFileName("完整影响因素.xls");
    workbook.getSheets().add(sheet);
    return workbook;
  }
}
