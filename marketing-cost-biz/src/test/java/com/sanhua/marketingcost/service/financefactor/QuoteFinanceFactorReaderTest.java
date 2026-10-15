package com.sanhua.marketingcost.service.financefactor;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.pricing.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class QuoteFinanceFactorReaderTest {
  private final OaFormMapper forms = mock(OaFormMapper.class);
  private final OaFormItemMapper items = mock(OaFormItemMapper.class);
  private final FinanceFactorPriceReader factors = mock(FinanceFactorPriceReader.class);
  private final QuoteFinanceFactorReader reader =
      new QuoteFinanceFactorReader(forms, items, factors);
  private OaForm form;
  private OaFormItem item;

  @BeforeAll
  static void tableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), OaForm.class);
  }

  @BeforeEach
  void scope() {
    form = new OaForm();
    form.setId(1L);
    form.setOaNo("OA-1");
    form.setBusinessUnitType("COMMERCIAL");
    item = new OaFormItem();
    item.setId(2L);
    item.setOaFormId(1L);
    item.setMaterialNo("TOP-1");
    item.setBusinessUnitType("COMMERCIAL");
    when(forms.selectOne(any())).thenReturn(form);
    when(items.selectById(2L)).thenReturn(item);
    when(factors.readEffective(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              FinanceFactorImportContext request = invocation.getArgument(0);
              FactorPriceScope.Condition condition = invocation.getArgument(1);
              var financial =
                  new FactorPriceSnapshot(
                      new FactorPriceScope(
                          request.businessUnitType(),
                          request.priceOrgCode(),
                          "2026-10",
                          "A",
                          condition),
                      100L,
                      2,
                      "基价.xlsx",
                      "file-hash",
                      "content-hash",
                      Map.of(
                          PriceFactorContext.COPPER,
                          new FactorPriceSnapshot.FactorValue(
                              10L,
                              20L,
                              "电解铜",
                              "1#Cu",
                              new BigDecimal("95123.4567890123456"),
                              "CNY/TON",
                              true,
                              List.of(
                                  new FactorPriceSnapshot.Source(
                                      30L,
                                      "基价",
                                      "E2",
                                      "95123.4567890123456",
                                      "元/吨",
                                      null,
                                      null,
                                      "吨转公斤")))),
                      List.of("A"),
                      "财务",
                      LocalDateTime.of(2026, 10, 1, 0, 0),
                      "复核");
              return new PriceFactorContext(
                  "TOP-1",
                  new PriceMonthSelection(request.priceMonth(), "2026-10"),
                  financial,
                  null);
            });
  }

  @Test
  void fixedOnlyCommercialQuoteUsesPublishedMonthAndPreservesSourcePrecision() {
    var result = reader.read("OA-1", 2L, "2026-11", "COMMERCIAL");
    assertThat(result.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true))
        .isEqualByComparingTo("95.1234567890123456");
    assertThat(result.months().sourceMonth()).isEqualTo("2026-10");
    assertThat(
            result.financial().required(PriceFactorContext.COPPER).sources().getFirst().cellRef())
        .isEqualTo("E2");
    verify(factors)
        .readEffective(
            new FinanceFactorImportContext("COMMERCIAL", "210", "2026-11", null, null),
            FactorPriceScope.Condition.GENERAL,
            "TOP-1");
  }

  @ParameterizedTest
  @CsvSource({"true,TENDER", "false,NON_TENDER"})
  void boardProductUsesOrganization220AndActualTenderCondition(boolean tender, String condition) {
    item.setProductName("钎焊板式换热器");
    form.setBoardTender(tender);
    reader.read("OA-1", 2L, "2026-10", "COMMERCIAL");
    verify(factors)
        .readEffective(
            new FinanceFactorImportContext("COMMERCIAL", "220", "2026-10", null, null),
            FactorPriceScope.Condition.valueOf(condition),
            "TOP-1");
  }

  @Test
  void boardProcessWithoutTenderFlagUsesNonTender() {
    form.setProcessCode("FI-SC-020");
    reader.read("OA-1", 2L, "2026-10", "COMMERCIAL");
    verify(factors)
        .readEffective(
            new FinanceFactorImportContext("COMMERCIAL", "220", "2026-10", null, null),
            FactorPriceScope.Condition.NON_TENDER,
            "TOP-1");
  }

  @Test
  void rejectsDifferentQuotationItemAndBusinessUnitBeforeReadingPrices() {
    item.setOaFormId(99L);
    assertThatThrownBy(() -> reader.read("OA-1", 2L, "2026-10", "COMMERCIAL"))
        .hasMessageContaining("不匹配");
    item.setOaFormId(1L);
    assertThatThrownBy(() -> reader.read("OA-1", 2L, "2026-10", "HOUSEHOLD"))
        .hasMessageContaining("业务单元");
    verifyNoInteractions(factors);
  }

  @Test
  void missingPublicationPropagatesWithoutLegacyFallback() {
    doThrow(new IllegalArgumentException("未发布该范围的财务基价"))
        .when(factors)
        .readEffective(any(), any(), any());
    assertThatThrownBy(() -> reader.read("OA-1", 2L, "2026-10", "COMMERCIAL"))
        .hasMessageContaining("未发布");
  }
}
