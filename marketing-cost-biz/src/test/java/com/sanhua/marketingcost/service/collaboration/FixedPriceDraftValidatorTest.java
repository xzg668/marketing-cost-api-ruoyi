package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("QCBP-15 固定采购价与结算固定价草稿校验")
class FixedPriceDraftValidatorTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void validFixedPurchaseCalculatesTaxConversionAndAcceptsSixDecimalAmount() {
    FixedPriceDraftValidator.Result result = validator(0).validate(
        task(), draft("FIXED_PURCHASE", 1, "0.13"),
        List.of(field("PRICE", "固定单价", "100.123456")));

    assertThat(result.valid()).isTrue();
    assertThat(result.taxConversion().taxIncludedPrice()).isEqualTo("100.123456");
    assertThat(result.taxConversion().taxExcludedPrice()).isEqualTo("88.604828");
    assertThat(result.fields()).allMatch(field -> "PASSED".equals(field.getValidationStatus()));
  }

  @Test
  void validSettleFixedUsesBaseSettlePriceAndCalculatesIncludedPrice() {
    FixedPriceDraftValidator.Result result = validator(0).validate(
        task(), draft("SETTLE_FIXED", 0, "0.13"),
        List.of(field("BASE_SETTLE_PRICE", "基础结算价", "80"),
            field("MARKUP_RATIO", "加价比例", "0.25")));

    assertThat(result.valid()).isTrue();
    assertThat(result.taxConversion().taxIncludedPrice()).isEqualTo("90.400000");
    assertThat(result.taxConversion().taxExcludedPrice()).isEqualTo("80");
  }

  @Test
  void rejectsMissingCommonFieldsZeroNegativeAndOverPrecision() {
    QuotePriceDraft missing = draft("FIXED_PURCHASE", null, null);
    missing.setSupplierCode(null);
    missing.setSupplierName(null);
    missing.setUnit(null);
    missing.setEffectiveFrom(null);
    FixedPriceDraftValidator.Result missingResult = validator(0).validate(
        task(), missing, List.of(field("PRICE", "固定单价", "0")));

    assertThat(missingResult.valid()).isFalse();
    assertThat(missingResult.message())
        .contains("供应商编码或供应商名称至少填写一项", "单位不能为空", "请选择含税或不含税",
            "税率不能为空", "生效日期不能为空", "价格必须大于0");

    FixedPriceDraftValidator.Result negative = validator(0).validate(
        task(), draft("SETTLE_FIXED", 1, "0.13"),
        List.of(field("BASE_SETTLE_PRICE", "基础结算价", "-1")));
    assertThat(negative.valid()).isFalse();
    assertThat(negative.message()).contains("价格必须大于0");

    FixedPriceDraftValidator.Result precision = validator(0).validate(
        task(), draft("FIXED_PURCHASE", 1, "0.13"),
        List.of(field("PRICE", "固定单价", "1.1234567")));
    assertThat(precision.valid()).isFalse();
    assertThat(precision.message()).contains("最多18位数字、6位小数");
  }

  @Test
  void rejectsPeriodOutsideAccountingMonthInvalidTaxAndFormalOverlap() {
    QuotePriceDraft outside = draft("FIXED_PURCHASE", 1, "1.000001");
    outside.setEffectiveFrom(LocalDate.of(2026, 9, 1));
    FixedPriceDraftValidator.Result outsideResult = validator(0).validate(
        task(), outside, List.of(field("PRICE", "固定单价", "10")));
    assertThat(outsideResult.valid()).isFalse();
    assertThat(outsideResult.message()).contains("税率必须为0到1之间", "生效期未覆盖当前核算月份");

    FixedPriceDraftValidator.Result overlap = validator(1).validate(
        task(), draft("FIXED_PURCHASE", 1, "0.13"),
        List.of(field("PRICE", "固定单价", "10")));
    assertThat(overlap.valid()).isFalse();
    assertThat(overlap.message()).contains("与现有正式价格冲突");
  }

  private FixedPriceDraftValidator validator(int conflictCount) {
    JdbcTemplate jdbc = new JdbcTemplate() {
      @Override
      public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        return requiredType.cast(conflictCount);
      }
    };
    return new FixedPriceDraftValidator(jdbc, objectMapper);
  }

  private QuoteCollaborationProductTask task() {
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setBusinessUnitType("COMMERCIAL");
    task.setApplicableOrgCode("210");
    task.setAccountingMonth("2026-08");
    return task;
  }

  private QuotePriceDraft draft(String type, Integer taxIncluded, String taxRate) {
    QuotePriceDraft draft = new QuotePriceDraft();
    draft.setMaterialCode("Q15-TARGET");
    draft.setPriceType(type);
    draft.setSupplierCode("SUP-1");
    draft.setSupplierName("测试供应商");
    draft.setUnit("kg");
    draft.setTaxIncluded(taxIncluded);
    draft.setTaxRate(taxRate == null ? null : new BigDecimal(taxRate));
    draft.setEffectiveFrom(LocalDate.of(2026, 8, 1));
    draft.setEffectiveTo(LocalDate.of(2026, 12, 31));
    return draft;
  }

  private QuotePriceDraftField field(String code, String name, String value) {
    QuotePriceDraftField field = new QuotePriceDraftField();
    field.setPriceDraftId(31L);
    field.setSectionCode("COMMON");
    field.setRowKey("MAIN");
    field.setFieldCode(code);
    field.setFieldName(name);
    field.setValueType("DECIMAL");
    try {
      field.setTargetValueJson(objectMapper.writeValueAsString(value));
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
    field.setRequiredFlag(1);
    field.setTechInputRequired(1);
    field.setChangedFlag(1);
    field.setSortSeq(10);
    return field;
  }
}
