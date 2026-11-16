package com.sanhua.marketingcost.service.bomalternative;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.dto.ingest.*;
import com.sanhua.marketingcost.dto.quotebom.*;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

class QuoteOrderBomAlternativesTest {
  final QuoteRequestQueryService requests = mock(QuoteRequestQueryService.class);
  final QuoteBomAlternativeApplicationService alternatives = mock(QuoteBomAlternativeApplicationService.class);
  final QuoteProductBomPreparationService preparations = mock(QuoteProductBomPreparationService.class);
  final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  final QuoteOrderBomAlternatives service = new QuoteOrderBomAlternatives(requests, alternatives, preparations,
      new QuoteBomAlternativeFeatureSwitch(true), jdbc, unit -> "2026-11");
  final Map<Long, QuoteBomAlternativeApplicationService.Inspection> data = new LinkedHashMap<>();
  QuoteRequestDetailResponse detail;

  @BeforeEach void setup() {
    detail = new QuoteRequestDetailResponse(); detail.setId(10L); detail.setAccountingPeriodMonth("2026-11");
    detail.setItems(List.of(item(1L), item(2L))); when(requests.getRequestDetail("OA")).thenReturn(detail);
    data.put(1L, scope("210", group("G1", "STD", 1, "1")));
    data.put(2L, scope("210", group("G2", "STD", 1, "5")));
    when(alternatives.inspectAlternativeGroups(eq("OA"), anyLong(), eq("2026-11")))
        .thenAnswer(call -> data.get(call.getArgument(1, Long.class)));
    when(alternatives.saveSelection(eq("OA"), anyLong(), anyString(), any(), any())).thenAnswer(call -> {
      long item = call.getArgument(1); String groupKey = call.getArgument(2);
      QuoteBomAlternativeSelectionRequest request = call.getArgument(3);
      var old = data.get(item); var group = old.groups().stream().filter(g -> g.alternativeGroupKey().equals(groupKey)).findFirst().orElseThrow();
      assertThat(request.expectedSelectionVersion()).isEqualTo(group.selectionVersion());
      assertThat(request.expectedBuildBatchId()).isEqualTo("BUILD-" + groupKey);
      var changed = group(groupKey, request.selectedMaterialCode(), group.selectionVersion() + 1, group.candidates().getFirst().qtyPerParent().toString());
      data.put(item, new QuoteBomAlternativeApplicationService.Inspection(old.periodMonth(), old.organization(),
          old.groups().stream().map(g -> g.alternativeGroupKey().equals(groupKey) ? changed : g).toList()));
      return new QuoteBomAlternativeSelectionResponse(groupKey, changed.selectionVersion(), request.selectedMaterialCode(),
          request.selectedMaterialCode().equals("STD") ? "STANDARD" : "ALTERNATIVE", "MANUAL", false, true, List.of("COST_RUN"));
    });
  }
  static QuoteRequestItemResponse item(long id) {
    var item = new QuoteRequestItemResponse(); item.setId(id); item.setMaterialNo("PRODUCT-" + id); item.setProductName("产品" + id); return item;
  }
  static QuoteBomAlternativeApplicationService.Inspection scope(String org, QuoteBomAlternativeGroupResponse... groups) {
    return new QuoteBomAlternativeApplicationService.Inspection("2026-11", org, List.of(groups));
  }
  static QuoteBomAlternativeGroupResponse group(String key, String selected, int version, String qty) {
    return new QuoteBomAlternativeGroupResponse(key, "PARENT-" + key, "父件", "/PRODUCT/" + key + "/", 20, "010", "主制造", "F009",
        version, "MANUAL", "ACTIVE", selected, "STD".equals(selected) ? "STANDARD" : "ALTERNATIVE", "BUILD-" + key, false, true,
        List.of(new QuoteBomAlternativeCandidateResponse("STD", "标准", "STD-SPEC", "STANDARD", new BigDecimal(qty), "IMPORT", "BUILD-" + key, selected.equals("STD")),
            new QuoteBomAlternativeCandidateResponse("ALT", "替代", "ALT-SPEC", "ALTERNATIVE", new BigDecimal(qty), "IMPORT", "BUILD-" + key, selected.equals("ALT"))));
  }
  QuoteOrderBomAlternatives.Change choose(QuoteOrderBomAlternatives.View view, String code) {
    return new QuoteOrderBomAlternatives.Change(view.fingerprint(), List.of(new QuoteOrderBomAlternatives.Selection(view.rows().getFirst().key(), code)));
  }

  @Test void readGroupsProductsWithoutPersistingDefaultsOrFlatteningQuantities() {
    var view = service.list("OA"); assertThat(view.problems()).isEmpty(); assertThat(view.rows()).hasSize(1);
    var row = view.rows().getFirst(); assertThat(row.occurrences()).hasSize(2); assertThat(row.selectedMaterialCode()).isEqualTo("STD");
    assertThat(row.occurrences().stream().map(o -> o.group().candidates().getFirst().qtyPerParent()).toList())
        .containsExactly(new BigDecimal("1"), new BigDecimal("5"));
    verify(alternatives, never()).getAlternativeGroups(any(), any(), any());
    verify(alternatives, never()).saveSelection(any(), any(), any(), any(), any()); verifyNoInteractions(preparations);
  }
  @Test void unifiedAlternativeAndRestoreUseEachProductVersionAndBuildBatch() {
    var saved = service.change("OA", choose(service.list("OA"), "ALT"), "finance");
    assertThat(saved.changedMaterials()).isEqualTo(1); assertThat(saved.changedOccurrences()).isEqualTo(2);
    assertThat(saved.affectedItemIds()).containsExactly(1L, 2L);
    assertThat(service.list("OA").rows().getFirst().selectedMaterialCode()).isEqualTo("ALT");
    var restored = service.change("OA", choose(service.list("OA"), "STD"), "finance");
    assertThat(restored.changedOccurrences()).isEqualTo(2);
    assertThat(data.values()).allSatisfy(scope -> assertThat(scope.groups().getFirst().selectionVersion()).isEqualTo(3));
    assertThat(service.list("OA").rows().getFirst().selectedMaterialCode()).isEqualTo("STD");
    var repeat = service.change("OA", choose(service.list("OA"), "STD"), "finance"); assertThat(repeat.changedOccurrences()).isZero();
    verify(alternatives, times(4)).saveSelection(any(), any(), any(), any(), any());
  }
  @Test void mixedSelectionsUnifyOnlyChangedOccurrencesAndCanRestoreStandard() {
    data.put(2L, scope("210", group("G2", "ALT", 4, "5")));
    var view = service.list("OA"); assertThat(view.rows().getFirst().mixedSelection()).isTrue();
    assertThat(service.change("OA", choose(view, "STD"), "finance").affectedItemIds()).containsExactly(2L);
  }
  @Test void repeatedPositionsInOneProductAreAllChangedButProductIsCountedOnce() {
    data.put(1L, scope("210", group("G1", "STD", 1, "1"), group("G1B", "STD", 1, "3")));
    var view = service.list("OA"); assertThat(view.rows().getFirst().occurrences()).hasSize(3);
    var saved = service.change("OA", choose(view, "ALT"), "finance");
    assertThat(saved.changedOccurrences()).isEqualTo(3); assertThat(saved.affectedItemIds()).containsExactly(1L, 2L);
    assertThat(data.get(1L).groups()).extracting(QuoteBomAlternativeGroupResponse::selectedMaterialCode).containsExactly("ALT", "ALT");
  }
  @Test void differentOrganizationsAndCandidateSetsStaySeparateAndNoAlternativeIsHidden() {
    data.put(2L, scope("220", group("G2", "STD", 1, "5"))); assertThat(service.list("OA").rows()).hasSize(2);
    var ordinary = group("G2", "STD", 1, "5");
    data.put(2L, scope("210", new QuoteBomAlternativeGroupResponse(ordinary.alternativeGroupKey(), "P", "父", "/P/", 1, "010", "主制造", "F1", 0,
        "AUTO_STANDARD", "PREVIEW", "STD", "STANDARD", "B", false, false, List.of(ordinary.candidates().getFirst()))));
    assertThat(service.list("OA").rows()).hasSize(1);
    assertThat(service.list("OA").rows().getFirst().occurrences()).extracting(QuoteOrderBomAlternatives.Occurrence::itemId).containsExactly(1L);
  }
  @Test void changedVersionRejectsEntireRequestBeforeAnySave() {
    var before = service.list("OA"); data.put(2L, scope("210", group("G2", "ALT", 2, "5")));
    assertThatThrownBy(() -> service.change("OA", choose(before, "ALT"), "finance")).hasMessageContaining("已变化");
    verify(alternatives, never()).saveSelection(any(), any(), any(), any(), any());
  }
  @Test void forgedCandidateOrDuplicateGroupDoesNotApplyPartialChanges() {
    var view = service.list("OA");
    assertThatThrownBy(() -> service.change("OA", choose(view, "FORGED"), "finance")).hasMessageContaining("有效标准件或替代料");
    var selection = choose(view, "ALT").selections().getFirst();
    assertThatThrownBy(() -> service.change("OA", new QuoteOrderBomAlternatives.Change(view.fingerprint(), List.of(selection, selection)), "finance")).hasMessageContaining("重复");
    verify(alternatives, never()).saveSelection(any(), any(), any(), any(), any());
  }
  @Test void incompleteProductIsVisibleAndBlocksPartialOrderSave() {
    when(alternatives.inspectAlternativeGroups("OA", 2L, "2026-11")).thenThrow(new QuoteBomAlternativeSelectionException("ALT_SOURCE_STALE", "BOM缺少"));
    var view = service.list("OA"); assertThat(view.rows()).hasSize(1); assertThat(view.problems()).hasSize(1);
    assertThat(view.problems().getFirst().preparable()).isTrue();
    assertThatThrownBy(() -> service.change("OA", choose(view, "ALT"), "finance")).hasMessageContaining("避免漏改");
    verify(alternatives, never()).saveSelection(any(), any(), any(), any(), any()); verifyNoInteractions(preparations);
  }
  @Test void preparationUsesActiveMonthEvenForAnOlderQuotation() {
    detail.setAccountingPeriodMonth("2026-10");
    when(alternatives.inspectAlternativeGroups("OA", 2L, "2026-11"))
        .thenThrow(new QuoteBomAlternativeSelectionException("ALT_SOURCE_STALE", "BOM缺少")).thenReturn(data.get(2L));
    assertThat(service.prepare("OA").problems()).isEmpty();
    verify(preparations).prepareByOaFormItem(eq(2L), any(), eq("2026-11"));
    verify(preparations, never()).prepareByOaFormItem(eq(1L), any(), any());
  }
  @Test void confirmedNoBomDoesNotBlockExistingRelationsButCheckFailureStillBlocks() {
    when(alternatives.inspectAlternativeGroups("OA", 2L, "2026-11")).thenThrow(new QuoteBomAlternativeSelectionException("ALT_SOURCE_STALE", "缺少"));
    when(jdbc.queryForList(anyString(), eq("OA"), eq(2L), any(), eq("2026-11")))
        .thenReturn(List.of(Map.of("bom_status","ENTRY_PENDING","sync_status","NOT_FOUND")));
    var view=service.list("OA");assertThat(view.problems().getFirst().blocking()).isFalse();
    assertThat(view.problems().getFirst().preparable()).isFalse();
    assertThat(service.change("OA",choose(view,"ALT"),"finance").affectedItemIds()).containsExactly(1L);
    service.prepare("OA");verifyNoInteractions(preparations);
    when(jdbc.queryForList(anyString(), eq("OA"), eq(2L), any(), eq("2026-11")))
        .thenReturn(List.of(Map.of("bom_status","CHECK_FAILED","sync_status","NOT_FOUND")));
    assertThat(service.list("OA").problems().getFirst().blocking()).isTrue();
  }
  @Test void quotationVisibilityIsCheckedBeforeReadingAnyProduct() {
    when(requests.getRequestDetail("DENIED")).thenThrow(new org.springframework.security.access.AccessDeniedException("无权查看本单"));
    assertThatThrownBy(() -> service.list("DENIED")).hasMessageContaining("无权");
    verifyNoInteractions(alternatives, preparations);
  }
  @Test void hiddenNestedOccurrenceAfterParentSwitchAbortsBatch() {
    var view = service.list("OA");
    when(alternatives.inspectAlternativeGroups("OA", 2L, "2026-11")).thenReturn(data.get(2L)).thenReturn(scope("210"));
    assertThatThrownBy(() -> service.change("OA", choose(view, "ALT"), "finance")).hasMessageContaining("父级切换");
  }
}
