package com.sanhua.marketingcost.service.bomalternative;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

final class QuoteBomAlternativeSelectionTestSupport {

  static final String GROUP_KEY = "a".repeat(64);
  static final String OTHER_GROUP_KEY = "b".repeat(64);
  static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-07-30T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  final InMemoryRepository repository = new InMemoryRepository();
  final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
  final QuoteBomAlternativeSelectionServiceImpl service =
      new QuoteBomAlternativeSelectionServiceImpl(repository, objectMapper, CLOCK);

  QuoteBomAlternativeSelectionScope scope() {
    return scope("OA-1", 10L, "2026-07", "COMMERCIAL");
  }

  QuoteBomAlternativeSelectionScope scope(
      String oaNo, long itemId, String periodMonth, String businessUnitType) {
    return new QuoteBomAlternativeSelectionScope(
        oaNo, itemId, "TOP", periodMonth, "210", businessUnitType);
  }

  BomAlternativeGroup group() {
    return group(GROUP_KEY, "STD", List.of("ALT"), "BUILD-1");
  }

  BomAlternativeGroup groupWithTwoAlternatives() {
    return group(GROUP_KEY, "STD", List.of("ALT", "ALT-2"), "BUILD-1");
  }

  BomAlternativeGroup group(
      String groupKey,
      String standardCode,
      List<String> alternativeCodes,
      String buildBatchId) {
    BomAlternativeGroupIdentity identity =
        new BomAlternativeGroupIdentity(
            "210",
            "TOP",
            "parent-fingerprint",
            "PARENT",
            "主制造",
            "F006",
            LocalDate.of(2026, 5, 21),
            LocalDate.of(9999, 12, 31),
            10,
            "010");
    List<BomAlternativeCandidate> candidates = new ArrayList<>();
    candidates.add(
        candidate(
            1L,
            standardCode,
            BomChildType.STANDARD,
            "/TOP/PARENT@10@030/" + standardCode + "@10@010/",
            buildBatchId));
    long id = 2L;
    for (String code : alternativeCodes) {
      candidates.add(
          candidate(
              id++,
              code,
              BomChildType.ALTERNATIVE,
              "/TOP/PARENT@10@030/" + code + "@10@010/",
              buildBatchId));
    }
    return new BomAlternativeGroup(identity, groupKey, candidates);
  }

  QuoteBomAlternativeSelectionCommand command(
      String selectedMaterialCode, int expectedVersion) {
    return new QuoteBomAlternativeSelectionCommand(
        scope(),
        GROUP_KEY,
        selectedMaterialCode,
        expectedVersion,
        "BUILD-1",
        "quote-user",
        "报价选择");
  }

  private BomAlternativeCandidate candidate(
      Long id,
      String code,
      BomChildType type,
      String path,
      String buildBatchId) {
    return new BomAlternativeCandidate(
        id,
        code,
        "名称-" + code,
        "规格-" + code,
        type,
        BigDecimal.ONE,
        path,
        "IMPORT-1",
        buildBatchId);
  }

  static final class InMemoryRepository
      implements QuoteBomAlternativeSelectionRepository {

    final List<QuoteBomAlternativeSelection> rows = new ArrayList<>();
    long sequence = 1L;
    boolean failNextInsert;

    @Override
    public QuoteBomAlternativeSelection findCurrent(
        QuoteBomAlternativeSelectionScope scope, String groupKey) {
      return rows.stream()
          .filter(row -> sameScope(row, scope))
          .filter(row -> Objects.equals(row.getAlternativeGroupKey(), groupKey))
          .filter(
              row ->
                  QuoteBomAlternativeSelection.STATUS_ACTIVE.equals(
                      row.getSelectionStatus()))
          .filter(
              row ->
                  Objects.equals(
                      row.getCurrentSlot(),
                      QuoteBomAlternativeSelection.CURRENT_SLOT))
          .findFirst()
          .orElse(null);
    }

    @Override
    public QuoteBomAlternativeSelection findCurrentForUpdate(
        QuoteBomAlternativeSelectionScope scope, String groupKey) {
      return findCurrent(scope, groupKey);
    }

    @Override
    public List<QuoteBomAlternativeSelection> findCurrentsForUpdate(
        QuoteBomAlternativeSelectionScope scope) {
      return rows.stream()
          .filter(row -> sameScope(row, scope))
          .filter(
              row ->
                  QuoteBomAlternativeSelection.STATUS_ACTIVE.equals(
                      row.getSelectionStatus()))
          .filter(
              row ->
                  Objects.equals(
                      row.getCurrentSlot(),
                      QuoteBomAlternativeSelection.CURRENT_SLOT))
          .sorted(
              Comparator.comparing(
                  QuoteBomAlternativeSelection::getAlternativeGroupKey))
          .toList();
    }

    @Override
    public QuoteBomAlternativeSelection findLatest(
        QuoteBomAlternativeSelectionScope scope, String groupKey) {
      return rows.stream()
          .filter(row -> sameScope(row, scope))
          .filter(row -> Objects.equals(row.getAlternativeGroupKey(), groupKey))
          .max(
              Comparator.comparing(
                  QuoteBomAlternativeSelection::getSelectionVersion))
          .orElse(null);
    }

    @Override
    public List<QuoteBomAlternativeSelection> findHistory(
        QuoteBomAlternativeSelectionScope scope, String groupKey) {
      return rows.stream()
          .filter(row -> sameScope(row, scope))
          .filter(row -> Objects.equals(row.getAlternativeGroupKey(), groupKey))
          .sorted(
              Comparator.comparing(
                  QuoteBomAlternativeSelection::getSelectionVersion))
          .toList();
    }

    @Override
    public List<QuoteBomAlternativeSelection> findByIds(Collection<Long> ids) {
      if (ids == null || ids.isEmpty()) {
        return List.of();
      }
      return rows.stream().filter(row -> ids.contains(row.getId())).toList();
    }

    @Override
    public void insert(QuoteBomAlternativeSelection selection) {
      if (failNextInsert) {
        failNextInsert = false;
        throw new IllegalStateException("模拟新版本写入失败");
      }
      boolean currentDuplicate =
          selection.getCurrentSlot() != null
              && rows.stream()
                  .anyMatch(
                      row ->
                          sameScope(row, scopeOf(selection))
                              && Objects.equals(
                                  row.getAlternativeGroupKey(),
                                  selection.getAlternativeGroupKey())
                              && row.getCurrentSlot() != null);
      if (currentDuplicate) {
        throw new IllegalStateException("同一作用域出现两个当前选择");
      }
      boolean versionDuplicate =
          rows.stream()
              .anyMatch(
                  row ->
                      sameScope(row, scopeOf(selection))
                          && Objects.equals(
                              row.getAlternativeGroupKey(),
                              selection.getAlternativeGroupKey())
                          && Objects.equals(
                              row.getSelectionVersion(),
                              selection.getSelectionVersion()));
      if (versionDuplicate) {
        throw new IllegalStateException("同一作用域出现重复选择版本");
      }
      selection.setId(sequence++);
      if (selection.getCreatedAt() == null) {
        selection.setCreatedAt(LocalDateTime.now(CLOCK));
      }
      selection.setUpdatedAt(LocalDateTime.now(CLOCK));
      rows.add(selection);
    }

    @Override
    public boolean transitionCurrent(
        Long id,
        Integer expectedVersion,
        String targetStatus,
        LocalDateTime updatedAt) {
      QuoteBomAlternativeSelection current =
          rows.stream()
              .filter(row -> Objects.equals(row.getId(), id))
              .filter(
                  row ->
                      Objects.equals(
                          row.getSelectionVersion(), expectedVersion))
              .filter(
                  row ->
                      QuoteBomAlternativeSelection.STATUS_ACTIVE.equals(
                          row.getSelectionStatus()))
              .filter(row -> row.getCurrentSlot() != null)
              .findFirst()
              .orElse(null);
      if (current == null) {
        return false;
      }
      current.setSelectionStatus(targetStatus);
      current.setCurrentSlot(null);
      current.setUpdatedAt(updatedAt);
      return true;
    }

    @Override
    public boolean refreshSource(
        Long id,
        Integer expectedVersion,
        String sourceImportBatchId,
        String sourceBuildBatchId,
        LocalDateTime updatedAt) {
      QuoteBomAlternativeSelection current =
          rows.stream()
              .filter(row -> Objects.equals(row.getId(), id))
              .filter(
                  row ->
                      Objects.equals(
                          row.getSelectionVersion(), expectedVersion))
              .filter(row -> row.getCurrentSlot() != null)
              .findFirst()
              .orElse(null);
      if (current == null) {
        return false;
      }
      current.setSourceImportBatchId(sourceImportBatchId);
      current.setSourceBuildBatchId(sourceBuildBatchId);
      current.setUpdatedAt(updatedAt);
      return true;
    }

    private static boolean sameScope(
        QuoteBomAlternativeSelection row,
        QuoteBomAlternativeSelectionScope scope) {
      return Objects.equals(row.getOaNo(), scope.oaNo())
          && Objects.equals(row.getOaFormItemId(), scope.oaFormItemId())
          && Objects.equals(row.getTopProductCode(), scope.topProductCode())
          && Objects.equals(row.getPeriodMonth(), scope.periodMonth())
          && Objects.equals(row.getPriceOrgCode(), scope.priceOrgCode())
          && Objects.equals(row.getBusinessUnitType(), scope.businessUnitType());
    }

    private static QuoteBomAlternativeSelectionScope scopeOf(
        QuoteBomAlternativeSelection row) {
      return new QuoteBomAlternativeSelectionScope(
          row.getOaNo(),
          row.getOaFormItemId(),
          row.getTopProductCode(),
          row.getPeriodMonth(),
          row.getPriceOrgCode(),
          row.getBusinessUnitType());
    }
  }
}
