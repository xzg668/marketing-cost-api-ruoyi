package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@DisplayName("QCBP-17 区间价整组与唯一命中校验")
class RangePriceDraftValidatorTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RangePriceDraftValidator validator = new RangePriceDraftValidator(jdbc, objectMapper);
  private QuoteCollaborationProductTask task;
  private QuotePriceDraft draft;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setup() {
    task = new QuoteCollaborationProductTask();
    task.setId(11L);
    task.setAccountingMonth("2026-08");
    draft = new QuotePriceDraft();
    draft.setId(31L);
    draft.setPriceType("RANGE");
    draft.setSupplierCode("SUP-1");
    draft.setUnit("件");
    draft.setTaxIncluded(1);
    draft.setTaxRate(new BigDecimal("0.13"));
    draft.setEffectiveFrom(LocalDate.of(2026, 1, 1));
    when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<BigDecimal>>any(), eq(11L)))
        .thenReturn(List.of(new BigDecimal("10")));
  }

  @Test
  void continuousDecimalRangesUseLowerInclusiveUpperExclusiveAndLastMayBeInfinite() {
    List<QuotePriceDraftField> fields = fields(
        row("ROW-1", "0", "10.5", "8", null),
        row("ROW-2", "10.5", null, null, "11.3"));

    RangePriceDraftValidator.Result result = validator.validate(task, draft, fields);

    assertThat(result.valid()).isTrue();
    assertThat(result.message()).contains("当前报价数量10唯一命中一段");
    assertThat(value(result.fields(), "ROW-1", "PRICE_INCL_TAX")).isEqualTo("9.04");
    assertThat(value(result.fields(), "ROW-2", "PRICE_EXCL_TAX")).isEqualTo("10");
  }

  @Test
  void exactSharedBoundaryBelongsOnlyToNextRange() {
    when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<BigDecimal>>any(), eq(11L)))
        .thenReturn(List.of(new BigDecimal("10.5")));
    RangePriceDraftValidator.Result result = validator.validate(task, draft, fields(
        row("ROW-1", "0", "10.5", "8", null),
        row("ROW-2", "10.5", "20", "9", null)));
    assertThat(result.valid()).isTrue();
  }

  @Test
  void rejectsGapOverlapInversionDuplicateAndNonLastInfiniteRange() {
    assertThat(validate(row("A", "0", "10", "1", null), row("B", "11", "20", "2", null)))
        .contains("存在空档");
    assertThat(validate(row("A", "0", "10", "1", null), row("B", "9", "20", "2", null)))
        .contains("重叠");
    assertThat(validate(row("A", "10", "5", "1", null))).contains("上限必须大于下限");
    assertThat(validate(row("A", "0", "10", "1", null), row("B", "0", "20", "2", null)))
        .contains("区间下限重复");
    assertThat(validate(row("A", "0", null, "1", null), row("B", "10", null, "2", null)))
        .contains("只有最后一段的上限可以为空");
  }

  @Test
  void rejectsMissingOrInvalidPricesAndDecimalOverflow() {
    assertThat(validate(row("A", "0", "10", null, null))).contains("至少填写一项");
    assertThat(validate(row("A", "0", "10", "0", null))).contains("价格必须大于0");
    assertThat(validate(row("A", "0.1234567", "10", "1", null))).contains("最多18位数字、6位小数");
  }

  @Test
  void zeroOrMultipleCurrentHitsAreRejected() {
    when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<BigDecimal>>any(), eq(11L)))
        .thenReturn(List.of(new BigDecimal("30")));
    assertThat(validate(row("A", "0", "10", "1", null))).contains("命中0段");
  }

  private String validate(List<QuotePriceDraftField>... rows) {
    return validator.validate(task, draft, fields(rows)).message();
  }

  @SafeVarargs
  private final List<QuotePriceDraftField> fields(List<QuotePriceDraftField>... rows) {
    List<QuotePriceDraftField> result = new ArrayList<>();
    result.add(field("COMMON", "MAIN", "RANGE_BASIS", "区间依据", "QTY"));
    result.add(field("COMMON", "MAIN", "FACTOR_CODE", "报价行情", null));
    for (List<QuotePriceDraftField> row : rows) result.addAll(row);
    return result;
  }

  private List<QuotePriceDraftField> row(
      String key, String low, String high, String excl, String incl) {
    return List.of(
        field("RANGE_ROW", key, "RANGE_LOW", "区间下限", low),
        field("RANGE_ROW", key, "RANGE_HIGH", "区间上限", high),
        field("RANGE_ROW", key, "PRICE_EXCL_TAX", "不含税价", excl),
        field("RANGE_ROW", key, "PRICE_INCL_TAX", "含税价", incl));
  }

  private QuotePriceDraftField field(
      String section, String row, String code, String name, String value) {
    QuotePriceDraftField field = new QuotePriceDraftField();
    field.setPriceDraftId(31L);
    field.setSectionCode(section);
    field.setRowKey(row);
    field.setFieldCode(code);
    field.setFieldName(name);
    field.setTargetValueJson(value == null ? null : '"' + value + '"');
    return field;
  }

  private String value(List<QuotePriceDraftField> fields, String row, String code) {
    QuotePriceDraftField field = fields.stream().filter(item -> row.equals(item.getRowKey())
        && code.equals(item.getFieldCode())).findFirst().orElseThrow();
    try {
      return objectMapper.readTree(field.getTargetValueJson()).asText();
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }
}
