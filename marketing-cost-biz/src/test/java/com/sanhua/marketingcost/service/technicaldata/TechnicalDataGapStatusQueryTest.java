package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TechnicalDataGapStatusQueryTest {
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final TechnicalDataGapStatusQuery query = new TechnicalDataGapStatusQuery(jdbc, new ObjectMapper());
  private final List<String> types = List.of("PROFILE", "PACKAGE", "AUXILIARY", "SOLDER", "SALARY", "NET_LOSS", "PRICE");

  private List<Map<String,Object>> submitted() {
    return types.stream().map(type -> {
      Map<String,Object> row = new HashMap<>();
      row.put("task_id", 1L); row.put("oa_form_item_id", 20L); row.put("accounting_month", "2026-10");
      row.put("applicable_org_code", "210"); row.put("task_status", "SUBMITTED"); row.put("product_status", "SUBMITTED");
      row.put("module_type", type); row.put("module_status", "SUBMITTED"); row.put("version_status", "SUBMITTED");
      row.put("todo_status", "SUBMITTED"); row.put("submission_status", "SENT"); row.put("current_form", 1L);
      if (type.equals("PRICE")) {
        row.put("price_json", "{\"items\":[{\"materialNo\":\"A\",\"organizationCode\":\"210\",\"entryMode\":\"FIXED\",\"unitPrice\":0}]}");
        row.put("technical_check_json", "{\"evidence\":{\"PRICE\":{\"gaps\":[[\"A\",\"MISSING_PRICE\",\"MISSING\"],[\"B\",\"MISSING_PRICE\",\"MISSING\"],[\"A\",\"MISSING_PRICE\",\"INVALID_INPUT\"]]}}}");
      }
      return row;
    }).toList();
  }
  private TechnicalDataGapStatusQuery.Status current(List<Map<String,Object>> rows) {
    when(jdbc.queryForList(anyString(), eq(10L), eq(20L), eq(20L), eq("2026-10"))).thenReturn(rows);
    return query.forForm(10L,20L,"2026-10").getFirst();
  }

  @Test void successfulSubmissionResolvesSevenModulesAndOnlyItsActualMissingFixedPrices() {
    var state = current(submitted());
    assertThat(state.status()).isEqualTo("SUBMITTED");
    assertThat(state.pendingModules()).isEmpty();
    assertThat(state.requiredModules()).containsExactlyElementsOf(types);
    assertThat(state.resolvedPriceGapKeys()).containsExactly("A|MISSING_PRICE|MISSING");
    assertThat(state.remainingPriceGapCount()).isEqualTo(2);
  }

  @Test void returnedProductRestoresAllSevenGapsUntilTheLastResubmissionSucceeds() {
    var rows = submitted();
    rows.getFirst().put("todo_status", "OPEN"); rows.getFirst().put("return_reason", "领导退回");
    rows.getFirst().put("module_status", "READY");
    var returned = current(rows);
    assertThat(returned.status()).isEqualTo("RETURNED");
    assertThat(returned.pendingModules()).containsExactlyElementsOf(types);
    assertThat(returned.resolvedPriceGapKeys()).isEmpty();
    assertThat(returned.remainingPriceGapCount()).isEqualTo(3);
    assertThat(current(submitted()).pendingModules()).isEmpty();
  }

  @Test void draftUnknownReceiptAndChangedDemandCannotPretendSubmissionSucceeded() {
    for (var changed : List.of(Map.entry("submission_status", "UNKNOWN"), Map.entry("module_status", "READY"),
        Map.entry("version_status", "DRAFT"), Map.entry("current_form", "0"))) {
      var rows = submitted(); rows.forEach(row -> row.put(changed.getKey(), changed.getValue()));
      assertThat(current(rows).pendingModules()).hasSize(7);
      assertThat(current(rows).resolvedPriceGapKeys()).isEmpty();
    }
  }

  @Test void unconfirmedReturnAndFailedReturnKeepPreviouslySubmittedGapsClosed() {
    var rows = submitted(); rows.forEach(row -> row.put("todo_status", "RETURN_PENDING"));
    assertThat(current(rows).pendingModules()).isEmpty();
    assertThat(current(submitted()).pendingModules()).isEmpty();
  }

  @Test void anotherOrganizationOrLinkedFormulaDoesNotHideUnresolvedFinancialPrice() {
    for (String replacement : List.of("220", "LINKED")) {
      var rows = submitted(); var price = rows.getLast();
      price.put("price_json", price.get("price_json").toString().replace(replacement.equals("220") ? "210" : "FIXED", replacement));
      assertThat(current(rows).resolvedPriceGapKeys()).isEmpty();
      assertThat(current(rows).remainingPriceGapCount()).isEqualTo(3);
    }
  }

  @Test void financialGapsOutsideTheTechnicalChecklistRemainVisible() {
    var rows = submitted();
    rows.getLast().put("saved_gap_count", 5);
    assertThat(current(rows).remainingPriceGapCount()).isEqualTo(4);
  }
}
