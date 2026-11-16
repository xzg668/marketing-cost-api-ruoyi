package com.sanhua.marketingcost.service.bomalternative;

import com.sanhua.marketingcost.dto.ingest.QuoteRequestDetailResponse;
import com.sanhua.marketingcost.dto.quotebom.*;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.QuoteBomAlternativeApplicationService;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.QuotePricingPeriod;
import com.sanhua.marketingcost.service.ingest.QuoteRequestQueryService;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import com.sanhua.marketingcost.service.reprice.QuotationWrite;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** One order-level action, persisted through each product's existing alternative selection/version chain. */
@Service
public class QuoteOrderBomAlternatives {
  public record Candidate(String materialCode, String materialName, String materialSpec, String childType) {}
  public record Occurrence(long itemId, String productCode, String productName, QuoteBomAlternativeGroupResponse group) {}
  public record Row(String key, String periodMonth, String organization, String standardMaterialCode,
      String materialName, String materialSpec, List<Candidate> candidates, List<Occurrence> occurrences,
      String selectedMaterialCode, boolean mixedSelection, boolean reviewRequired) {}
  public record Problem(long itemId, String productCode, String message, boolean preparable, boolean blocking) {}
  public record View(long formId, String fingerprint, boolean enabled, List<Row> rows, List<Problem> problems) {}
  public record Selection(String key, String selectedMaterialCode) {}
  public record Change(String expectedFingerprint, List<Selection> selections) {}
  public record Saved(int changedMaterials, int changedOccurrences, List<Long> affectedItemIds) {}
  private record Target(Row row, Occurrence occurrence, String selectedMaterialCode) {}

  private final QuoteRequestQueryService requests;
  private final QuoteBomAlternativeApplicationService alternatives;
  private final QuoteProductBomPreparationService preparations;
  private final QuoteBomAlternativeFeatureSwitch feature;
  private final JdbcTemplate jdbc;
  private final QuotePricingPeriod pricingPeriods;

  public QuoteOrderBomAlternatives(QuoteRequestQueryService requests, QuoteBomAlternativeApplicationService alternatives,
      QuoteProductBomPreparationService preparations, QuoteBomAlternativeFeatureSwitch feature, JdbcTemplate jdbc, QuotePricingPeriod pricingPeriods) {
    this.requests = requests; this.alternatives = alternatives; this.preparations = preparations;
    this.feature = feature; this.jdbc = jdbc; this.pricingPeriods = pricingPeriods;
  }

  public View list(String oaNo) { return collect(oaNo, requests.getRequestDetail(oaNo), false); }

  /** Explicitly prepare only missing BOMs. Viewing the tab never saves default selections. */
  @QuotationWrite(holdUntilCompletion = true)
  public View prepare(String oaNo) {
    var detail = requests.getRequestDetail(oaNo);
    requireEditable(detail);
    return collect(oaNo, detail, true);
  }

  private View collect(String oaNo, QuoteRequestDetailResponse detail, boolean prepareMissing) {
    String month = pricingPeriods.currentMonth();
    var grouped = new TreeMap<String, List<Occurrence>>();
    var scopes = new HashMap<String, QuoteBomAlternativeApplicationService.Inspection>();
    var problems = new ArrayList<Problem>();
    var items = detail.getItems().stream().sorted(Comparator.comparing(item -> item.getId())).toList();
    if (feature.isEnabled()) for (var item : items) {
      try {
        QuoteBomAlternativeApplicationService.Inspection inspection;
        try {
          inspection = alternatives.inspectAlternativeGroups(oaNo, item.getId(), month);
        } catch (QuoteBomAlternativeSelectionException failure) {
          if (!prepareMissing || !preparable(failure) || confirmedNoBom(oaNo, item.getId(), month)) throw failure;
          preparations.prepareByOaFormItem(item.getId(), CostPricingPeriodUtils.currentPricingDate(), month);
          inspection = alternatives.inspectAlternativeGroups(oaNo, item.getId(), month);
        }
        for (var group : inspection.groups()) {
          if (group.candidates().stream().noneMatch(candidate -> "ALTERNATIVE".equals(candidate.childType()))) continue;
          var standard = standard(group);
          // Deliberately exclude product path and quantity: both remain local to each occurrence.
          var identity = inspection.periodMonth() + "|" + inspection.organization() + "|" + standard.materialCode()
              + "|" + group.candidates().stream().map(candidate -> candidate.childType() + ":" + candidate.materialCode()).sorted().toList();
          String key = MonthlyPriceDocumentCodec.sha256(identity);
          grouped.computeIfAbsent(key, ignored -> new ArrayList<>())
              .add(new Occurrence(item.getId(), item.getMaterialNo(), item.getProductName(), group));
          scopes.putIfAbsent(key, inspection);
        }
      } catch (RuntimeException failure) {
        boolean absent = preparable(failure) && confirmedNoBom(oaNo, item.getId(), month);
        problems.add(new Problem(item.getId(), item.getMaterialNo(), absent
            ? "本月已确认无BOM，不涉及当前替代关系；技术补齐后请重新核对并统一选择" : failure.getMessage(),
            !absent && preparable(failure), !absent));
      }
    }
    var rows = new ArrayList<Row>();
    grouped.forEach((key, occurrences) -> {
      var group = occurrences.getFirst().group(); var standard = standard(group); var scope = scopes.get(key);
      var codes = occurrences.stream().map(occurrence -> occurrence.group().selectedMaterialCode()).distinct().toList();
      var candidates = group.candidates().stream()
          .sorted(Comparator.comparing((QuoteBomAlternativeCandidateResponse c) -> !"STANDARD".equals(c.childType()))
              .thenComparing(QuoteBomAlternativeCandidateResponse::materialCode))
          .map(c -> new Candidate(c.materialCode(), c.materialName(), c.materialSpec(), c.childType())).toList();
      rows.add(new Row(key, scope.periodMonth(), scope.organization(), standard.materialCode(), standard.materialName(),
          standard.materialSpec(), candidates, List.copyOf(occurrences), codes.size() == 1 ? codes.getFirst() : null,
          codes.size() != 1, occurrences.stream().anyMatch(occurrence -> occurrence.group().reviewRequired())));
    });
    rows.sort(Comparator.comparing(Row::standardMaterialCode).thenComparing(Row::key));
    String fingerprint = MonthlyPriceDocumentCodec.sha256(BusinessUnitContext.getCurrentBusinessUnitType() + "|"
        + detail.getId() + "|" + month + "|"
        + items.stream().map(item -> item.getId() + ":" + item.getMaterialNo()).toList() + "|" + rows + "|" + problems);
    return new View(detail.getId(), fingerprint, feature.isEnabled(), List.copyOf(rows), List.copyOf(problems));
  }

  @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
  @QuotationWrite(holdUntilCompletion = true)
  public Saved change(String oaNo, Change change, String actor) {
    if (change == null || change.expectedFingerprint() == null || change.selections() == null
        || change.selections().isEmpty() || change.selections().size() > 1000)
      throw new IllegalArgumentException("请选择1至1000组标准／替代料");
    var detail = requests.getRequestDetail(oaNo); requireEditable(detail);
    jdbc.queryForObject("SELECT id FROM oa_form WHERE id=? AND business_unit_type=? FOR UPDATE", Long.class,
        detail.getId(), BusinessUnitContext.getCurrentBusinessUnitType());
    var current = collect(oaNo, detail, false);
    if (current.problems().stream().anyMatch(Problem::blocking)) throw new IllegalArgumentException("本单仍有产品BOM未加载完整，请补齐后再统一保存，避免漏改产品");
    if (!Objects.equals(current.fingerprint(), change.expectedFingerprint())) throw conflict();
    var rows = new HashMap<String, Row>(); current.rows().forEach(row -> rows.put(row.key(), row));
    var keys = new HashSet<String>(); var targets = new ArrayList<Target>();
    for (var selection : change.selections()) {
      if (selection == null || !keys.add(selection.key())) throw new IllegalArgumentException("选择包含空值或重复替代关系");
      var row = rows.get(selection.key());
      if (row == null || row.candidates().stream().noneMatch(c -> Objects.equals(c.materialCode(), selection.selectedMaterialCode())))
        throw new IllegalArgumentException("所选料号不是本单该关系中的有效标准件或替代料，请刷新");
      row.occurrences().forEach(occurrence -> targets.add(new Target(row, occurrence, selection.selectedMaterialCode())));
    }
    targets.sort(Comparator.comparingLong((Target t) -> t.occurrence().itemId())
        .thenComparingInt(t -> Objects.toString(t.occurrence().group().parentPath(), "").length())
        .thenComparing(t -> t.occurrence().group().alternativeGroupKey()));
    var affected = new LinkedHashSet<Long>(); var changedRows = new HashSet<String>(); int changedOccurrences = 0;
    for (var target : targets) {
      var occurrence = target.occurrence(); var expected = occurrence.group();
      var live = alternatives.inspectAlternativeGroups(oaNo, occurrence.itemId(), target.row().periodMonth()).groups().stream()
          .filter(group -> group.alternativeGroupKey().equals(expected.alternativeGroupKey())).findFirst().orElseThrow(QuoteOrderBomAlternatives::conflict);
      if (!live.equals(expected)) throw conflict();
      if (Objects.equals(live.selectedMaterialCode(), target.selectedMaterialCode()) && !live.reviewRequired()) continue;
      if (live.reviewRequired()) {
        live = alternatives.getAlternativeGroups(oaNo, occurrence.itemId(), target.row().periodMonth()).groups().stream()
            .filter(group -> group.alternativeGroupKey().equals(expected.alternativeGroupKey())).findFirst().orElseThrow(QuoteOrderBomAlternatives::conflict);
        if (!Objects.equals(live.selectionVersion(), expected.selectionVersion())) throw conflict();
      }
      var candidate = live.candidates().stream().filter(c -> c.materialCode().equals(target.selectedMaterialCode()))
          .findFirst().orElseThrow(QuoteOrderBomAlternatives::conflict);
      var saved = alternatives.saveSelection(oaNo, occurrence.itemId(), live.alternativeGroupKey(),
          new QuoteBomAlternativeSelectionRequest(target.row().periodMonth(), candidate.materialCode(),
              live.selectionVersion(), candidate.sourceBuildBatchId(), "本单统一选择标准／替代料"), actor);
      if (!saved.idempotent()) { changedOccurrences++; changedRows.add(target.row().key()); affected.add(occurrence.itemId()); }
    }
    return new Saved(changedRows.size(), changedOccurrences, List.copyOf(affected));
  }

  private void requireEditable(QuoteRequestDetailResponse detail) {
    feature.requireEnabled();
    if (detail.getOaWorkflow() != null && !detail.getOaWorkflow().canCost())
      throw new IllegalArgumentException("当前OA流程状态不允许调整标准／替代料");
  }
  private boolean confirmedNoBom(String oaNo, long itemId, String month) {
    var rows = jdbc.queryForList("""
        SELECT s.bom_status,m.sync_status FROM lp_quote_bom_status s
        JOIN lp_quote_bom_monthly_snapshot m ON m.id=s.sync_record_id
          AND m.active_flag=1 AND m.snapshot_identity_key IS NOT NULL
        WHERE s.oa_no=? AND s.oa_form_item_id=? AND m.business_unit_type=? AND m.cost_period_month=?
        ORDER BY s.id DESC LIMIT 1
        """, oaNo, itemId, BusinessUnitContext.getCurrentBusinessUnitType(), month);
    return !rows.isEmpty() && Set.of("NO_BOM", "ENTRY_PENDING").contains(Objects.toString(rows.getFirst().get("bom_status"), ""))
        && "NOT_FOUND".equals(rows.getFirst().get("sync_status"));
  }
  private static QuoteBomAlternativeCandidateResponse standard(QuoteBomAlternativeGroupResponse group) {
    return group.candidates().stream().filter(candidate -> "STANDARD".equals(candidate.childType())).findFirst()
        .orElseThrow(() -> new IllegalArgumentException("替代关系缺少标准件，请核对BOM"));
  }
  private static boolean preparable(RuntimeException failure) {
    return failure instanceof QuoteBomAlternativeSelectionException alternative
        && Set.of("ALT_QUOTE_SCOPE_INVALID", "ALT_SOURCE_STALE").contains(alternative.getCode());
  }
  private static QuoteBomAlternativeSelectionException conflict() {
    return new QuoteBomAlternativeSelectionException("ALT_SELECTION_CONFLICT",
        "本单BOM或标准／替代选择已变化，请刷新后重新选择；父级切换与其下级选择请分次保存");
  }
}
