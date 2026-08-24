package com.sanhua.marketingcost.service.bomalternative;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 报价 BOM 标准/替代选择事务服务。 */
@Service
public class QuoteBomAlternativeSelectionServiceImpl
    implements QuoteBomAlternativeSelectionService {

  public static final String ALT_GROUP_NOT_FOUND = "ALT_GROUP_NOT_FOUND";
  public static final String ALT_CANDIDATE_INVALID = "ALT_CANDIDATE_INVALID";
  public static final String ALT_SELECTION_CONFLICT = "ALT_SELECTION_CONFLICT";
  public static final String ALT_SOURCE_STALE = "ALT_SOURCE_STALE";
  public static final String STATUS_PREVIEW = "PREVIEW";

  private final QuoteBomAlternativeSelectionRepository repository;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @Autowired
  public QuoteBomAlternativeSelectionServiceImpl(
      QuoteBomAlternativeSelectionRepository repository,
      ObjectMapper objectMapper) {
    this(repository, objectMapper, Clock.systemDefaultZone());
  }

  public QuoteBomAlternativeSelectionServiceImpl(
      QuoteBomAlternativeSelectionRepository repository,
      ObjectMapper objectMapper,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomAlternativeSelectionResult ensureDefault(
      QuoteBomAlternativeSelectionScope scope,
      BomAlternativeGroup group) {
    QuoteBomAlternativeSelectionScope normalizedScope = validateScope(scope);
    ValidatedGroup validatedGroup = validateGroup(normalizedScope, group, null);
    QuoteBomAlternativeSelection current =
        repository.findCurrentForUpdate(
            normalizedScope, validatedGroup.groupKey());
    if (current != null) {
      return reconcileLocked(normalizedScope, validatedGroup, current);
    }

    QuoteBomAlternativeSelection latest =
        repository.findLatest(normalizedScope, validatedGroup.groupKey());
    if (latest != null) {
      if (QuoteBomAlternativeSelection.STATUS_STALE.equals(
          latest.getSelectionStatus())) {
        return preview(validatedGroup, latest.getSelectionVersion());
      }
      throw conflict("当前替代组存在历史版本但没有有效当前选择，请刷新后重试");
    }

    QuoteBomAlternativeSelection created =
        newSelection(
            normalizedScope,
            validatedGroup,
            validatedGroup.standard(),
            1,
            QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD,
            currentUsername("system"),
            "系统首次默认标准件");
    insertCurrent(created);
    return result(created, false, false, true);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomAlternativeSelectionResult save(
      QuoteBomAlternativeSelectionCommand command,
      BomAlternativeGroup group) {
    if (command == null) {
      throw new IllegalArgumentException("选择命令不能为空");
    }
    QuoteBomAlternativeSelectionScope scope = validateScope(command.scope());
    ValidatedGroup validatedGroup =
        validateGroup(scope, group, command.alternativeGroupKey());
    QuoteBomAlternativeSelection current =
        repository.findCurrentForUpdate(scope, validatedGroup.groupKey());
    BomAlternativeCandidate selected =
        findCandidate(validatedGroup.group(), command.selectedMaterialCode());
    if (selected == null) {
      throw new QuoteBomAlternativeSelectionException(
          ALT_CANDIDATE_INVALID,
          "料号"
              + display(command.selectedMaterialCode())
              + "不是父件"
              + display(validatedGroup.group().identity().parentMaterialNo())
              + "当前位置的有效标准/替代候选");
    }
    validateExpectedBuildBatch(command.expectedBuildBatchId(), selected);

    if (current != null
        && sameText(
            current.getSelectedMaterialCode(), selected.materialCode())) {
      refreshSource(current, selected);
      return result(current, true, false, true);
    }

    QuoteBomAlternativeSelection latest =
        current == null
            ? repository.findLatest(scope, validatedGroup.groupKey())
            : current;
    if (latest == null) {
      if (!Objects.equals(command.expectedSelectionVersion(), 0)) {
        throw conflict("当前替代组尚未建立选择，请刷新报价BOM后重试");
      }
      String source =
          selected.childType() == BomChildType.STANDARD
              ? QuoteBomAlternativeSelection.SOURCE_MANUAL_STANDARD
              : QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE;
      QuoteBomAlternativeSelection created =
          newSelection(
              scope,
              validatedGroup,
              selected,
              1,
              source,
              firstText(command.selectedBy(), currentUsername("system")),
              trimToNull(command.selectionRemark()));
      insertCurrent(created);
      return result(created, false, false, true);
    }
    if (command.expectedSelectionVersion() == null
        || !Objects.equals(
            latest.getSelectionVersion(),
            command.expectedSelectionVersion())) {
      throw conflict(
          "选择已被其他人更新，当前版本="
              + latest.getSelectionVersion()
              + "，请求版本="
              + command.expectedSelectionVersion()
              + "，请刷新后重试");
    }

    if (current != null) {
      boolean selectedStillExists =
          findCandidate(
                  validatedGroup.group(),
                  current.getSelectedMaterialCode())
              != null;
      String targetStatus =
          selectedStillExists
              ? QuoteBomAlternativeSelection.STATUS_SUPERSEDED
              : QuoteBomAlternativeSelection.STATUS_STALE;
      transitionOrConflict(current, targetStatus);
    } else if (!QuoteBomAlternativeSelection.STATUS_STALE.equals(
        latest.getSelectionStatus())) {
      throw conflict("当前选择状态异常，请刷新后重试");
    }

    String source =
        selected.childType() == BomChildType.STANDARD
            ? QuoteBomAlternativeSelection.SOURCE_MANUAL_STANDARD
            : QuoteBomAlternativeSelection.SOURCE_MANUAL_ALTERNATIVE;
    QuoteBomAlternativeSelection created =
        newSelection(
            scope,
            validatedGroup,
            selected,
            latest.getSelectionVersion() + 1,
            source,
            firstText(command.selectedBy(), currentUsername("system")),
            trimToNull(command.selectionRemark()));
    insertCurrent(created);
    return result(created, false, false, true);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomAlternativeSelectionResult reconcile(
      QuoteBomAlternativeSelectionScope scope,
      BomAlternativeGroup currentGroup) {
    QuoteBomAlternativeSelectionScope normalizedScope = validateScope(scope);
    ValidatedGroup validatedGroup =
        validateGroup(normalizedScope, currentGroup, null);
    QuoteBomAlternativeSelection current =
        repository.findCurrentForUpdate(
            normalizedScope, validatedGroup.groupKey());
    if (current == null) {
      QuoteBomAlternativeSelection latest =
          repository.findLatest(normalizedScope, validatedGroup.groupKey());
      if (latest != null
          && QuoteBomAlternativeSelection.STATUS_STALE.equals(
              latest.getSelectionStatus())) {
        return preview(validatedGroup, latest.getSelectionVersion());
      }
      return createDefaultWithoutNestedTransaction(
          normalizedScope, validatedGroup);
    }
    return reconcileLocked(normalizedScope, validatedGroup, current);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<QuoteBomAlternativeSelectionResult> synchronize(
      QuoteBomAlternativeSelectionScope scope,
      List<BomAlternativeGroup> currentGroups) {
    QuoteBomAlternativeSelectionScope normalizedScope = validateScope(scope);
    List<ValidatedGroup> groups =
        currentGroups == null
            ? List.of()
            : currentGroups.stream()
                .map(group -> validateGroup(normalizedScope, group, null))
                .sorted(
                    java.util.Comparator.comparing(
                        ValidatedGroup::groupKey))
                .toList();
    Map<String, ValidatedGroup> groupByKey =
        groups.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ValidatedGroup::groupKey,
                    group -> group,
                    (left, right) -> left,
                    LinkedHashMap::new));
    List<QuoteBomAlternativeSelection> currents =
        repository.findCurrentsForUpdate(normalizedScope);
    Map<String, QuoteBomAlternativeSelection> currentByKey =
        currents.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    QuoteBomAlternativeSelection::getAlternativeGroupKey,
                    row -> row));
    List<SelectionPosition> stalePositions = new java.util.ArrayList<>();

    for (QuoteBomAlternativeSelection current : currents) {
      if (groupByKey.containsKey(current.getAlternativeGroupKey())) {
        continue;
      }
      transitionOrConflict(
          current, QuoteBomAlternativeSelection.STATUS_STALE);
      current.setSelectionStatus(
          QuoteBomAlternativeSelection.STATUS_STALE);
      current.setCurrentSlot(null);
      stalePositions.add(position(current));
    }

    List<QuoteBomAlternativeSelectionResult> results =
        new java.util.ArrayList<>();
    for (ValidatedGroup group : groups) {
      QuoteBomAlternativeSelection current =
          currentByKey.get(group.groupKey());
      if (current != null) {
        results.add(
            reconcileLocked(normalizedScope, group, current));
        continue;
      }
      QuoteBomAlternativeSelection latest =
          repository.findLatest(normalizedScope, group.groupKey());
      if (latest != null
          && QuoteBomAlternativeSelection.STATUS_STALE.equals(
              latest.getSelectionStatus())) {
        results.add(preview(group, latest.getSelectionVersion()));
        continue;
      }
      if (stalePositions.contains(position(group))) {
        results.add(preview(group, 0));
        continue;
      }
      results.add(
          createDefaultWithoutNestedTransaction(
              normalizedScope, group));
    }
    return List.copyOf(results);
  }

  @Override
  public QuoteBomAlternativeSelectionResult findCurrent(
      QuoteBomAlternativeSelectionScope scope,
      String alternativeGroupKey) {
    QuoteBomAlternativeSelectionScope normalizedScope = validateScope(scope);
    String groupKey = required("alternativeGroupKey", alternativeGroupKey);
    QuoteBomAlternativeSelection current =
        repository.findCurrent(normalizedScope, groupKey);
    return current == null ? null : result(current, false, false, true);
  }

  @Override
  public List<QuoteBomAlternativeSelectionResult> history(
      QuoteBomAlternativeSelectionScope scope,
      String alternativeGroupKey) {
    QuoteBomAlternativeSelectionScope normalizedScope = validateScope(scope);
    String groupKey = required("alternativeGroupKey", alternativeGroupKey);
    return repository.findHistory(normalizedScope, groupKey).stream()
        .map(
            row ->
                result(
                    row,
                    false,
                    QuoteBomAlternativeSelection.STATUS_STALE.equals(
                        row.getSelectionStatus()),
                    true))
        .toList();
  }

  private QuoteBomAlternativeSelectionResult reconcileLocked(
      QuoteBomAlternativeSelectionScope scope,
      ValidatedGroup group,
      QuoteBomAlternativeSelection current) {
    BomAlternativeCandidate selected =
        findCandidate(group.group(), current.getSelectedMaterialCode());
    boolean standardUnchanged =
        sameText(
            current.getStandardMaterialCode(),
            group.standard().materialCode());
    if (selected == null || !standardUnchanged) {
      transitionOrConflict(
          current, QuoteBomAlternativeSelection.STATUS_STALE);
      current.setSelectionStatus(
          QuoteBomAlternativeSelection.STATUS_STALE);
      current.setCurrentSlot(null);
      return preview(group, current.getSelectionVersion());
    }
    refreshSource(current, selected);
    return result(current, true, false, true);
  }

  private QuoteBomAlternativeSelectionResult createDefaultWithoutNestedTransaction(
      QuoteBomAlternativeSelectionScope scope,
      ValidatedGroup group) {
    QuoteBomAlternativeSelection created =
        newSelection(
            scope,
            group,
            group.standard(),
            1,
            QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD,
            currentUsername("system"),
            "系统首次默认标准件");
    insertCurrent(created);
    return result(created, false, false, true);
  }

  private QuoteBomAlternativeSelectionResult preview(
      ValidatedGroup group, Integer expectedVersion) {
    BomAlternativeCandidate standard = group.standard();
    return new QuoteBomAlternativeSelectionResult(
        null,
        group.groupKey(),
        standard.materialCode(),
        standard.materialCode(),
        BomChildType.STANDARD,
        QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD,
        expectedVersion,
        STATUS_PREVIEW,
        false,
        true,
        false,
        standard.sourceImportBatchId(),
        standard.sourceBuildBatchId());
  }

  private QuoteBomAlternativeSelection newSelection(
      QuoteBomAlternativeSelectionScope scope,
      ValidatedGroup group,
      BomAlternativeCandidate selected,
      int version,
      String selectionSource,
      String selectedBy,
      String remark) {
    LocalDateTime now = LocalDateTime.now(clock);
    QuoteBomAlternativeSelection entity =
        new QuoteBomAlternativeSelection();
    entity.setSelectionNo(
        "BOM-ALT-"
            + UUID.randomUUID()
                .toString()
                .replace("-", "")
                .toUpperCase(Locale.ROOT));
    entity.setOaNo(scope.oaNo());
    entity.setOaFormItemId(scope.oaFormItemId());
    entity.setTopProductCode(scope.topProductCode());
    entity.setPeriodMonth(scope.periodMonth());
    entity.setPriceOrgCode(scope.priceOrgCode());
    entity.setAlternativeGroupKey(group.groupKey());
    entity.setParentPath(parentPath(group.standard().path()));
    entity.setParentMaterialCode(
        group.group().identity().parentMaterialNo());
    entity.setParentMaterialName(null);
    entity.setChildSeq(group.group().identity().childSeq());
    entity.setProcessSeq(group.group().identity().processSeq());
    entity.setBomPurpose(group.group().identity().bomPurpose());
    entity.setBomVersion(group.group().identity().bomVersion());
    entity.setSourceEffectiveFrom(
        group.group().identity().effectiveFrom());
    entity.setSourceEffectiveTo(
        group.group().identity().effectiveTo());
    entity.setStandardMaterialCode(group.standard().materialCode());
    entity.setSelectedMaterialCode(selected.materialCode());
    entity.setSelectedChildType(selected.childType().name());
    entity.setSelectionSource(selectionSource);
    entity.setSelectionVersion(version);
    entity.setSelectionStatus(
        QuoteBomAlternativeSelection.STATUS_ACTIVE);
    entity.setCurrentSlot(
        QuoteBomAlternativeSelection.CURRENT_SLOT);
    entity.setCandidateSnapshotJson(
        candidateSnapshot(group.group()));
    entity.setSourceImportBatchId(selected.sourceImportBatchId());
    entity.setSourceBuildBatchId(selected.sourceBuildBatchId());
    entity.setSelectedBy(selectedBy);
    entity.setSelectedAt(now);
    entity.setSelectionRemark(remark);
    entity.setBusinessUnitType(scope.businessUnitType());
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    return entity;
  }

  private String candidateSnapshot(BomAlternativeGroup group) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("alternativeGroupKey", group.alternativeGroupKey());
    snapshot.put(
        "standardMaterialCode",
        group.standardCandidate().materialCode());
    snapshot.put(
        "candidates",
        group.candidates().stream()
            .map(
                candidate -> {
                  Map<String, Object> item = new LinkedHashMap<>();
                  item.put(
                      "rawHierarchyNodeId",
                      candidate.rawHierarchyNodeId());
                  item.put("materialCode", candidate.materialCode());
                  item.put("materialName", candidate.materialName());
                  item.put("materialSpec", candidate.materialSpec());
                  item.put("childType", candidate.childType().name());
                  item.put("qtyPerParent", candidate.qtyPerParent());
                  item.put("path", candidate.path());
                  item.put(
                      "sourceImportBatchId",
                      candidate.sourceImportBatchId());
                  item.put(
                      "sourceBuildBatchId",
                      candidate.sourceBuildBatchId());
                  return item;
                })
            .toList());
    try {
      return objectMapper.writeValueAsString(snapshot);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("替代组候选快照序列化失败", ex);
    }
  }

  private void insertCurrent(QuoteBomAlternativeSelection entity) {
    try {
      repository.insert(entity);
    } catch (DataAccessException ex) {
      throw new QuoteBomAlternativeSelectionException(
          ALT_SELECTION_CONFLICT,
          "当前替代组选择已被其他请求更新，请刷新后重试",
          ex);
    }
  }

  private void transitionOrConflict(
      QuoteBomAlternativeSelection current, String targetStatus) {
    boolean transitioned =
        repository.transitionCurrent(
            current.getId(),
            current.getSelectionVersion(),
            targetStatus,
            LocalDateTime.now(clock));
    if (!transitioned) {
      throw conflict("当前替代组选择已被其他请求更新，请刷新后重试");
    }
  }

  private void refreshSource(
      QuoteBomAlternativeSelection current,
      BomAlternativeCandidate selected) {
    if (Objects.equals(
            current.getSourceImportBatchId(),
            selected.sourceImportBatchId())
        && Objects.equals(
            current.getSourceBuildBatchId(),
            selected.sourceBuildBatchId())) {
      return;
    }
    boolean refreshed =
        repository.refreshSource(
            current.getId(),
            current.getSelectionVersion(),
            selected.sourceImportBatchId(),
            selected.sourceBuildBatchId(),
            LocalDateTime.now(clock));
    if (!refreshed) {
      throw conflict("BOM来源批次刷新时选择已变化，请重新加载");
    }
    current.setSourceImportBatchId(selected.sourceImportBatchId());
    current.setSourceBuildBatchId(selected.sourceBuildBatchId());
  }

  private ValidatedGroup validateGroup(
      QuoteBomAlternativeSelectionScope scope,
      BomAlternativeGroup group,
      String expectedGroupKey) {
    if (group == null
        || group.identity() == null
        || !StringUtils.hasText(group.alternativeGroupKey())) {
      throw new QuoteBomAlternativeSelectionException(
          ALT_GROUP_NOT_FOUND, "替代组不存在或结构不完整，请刷新BOM");
    }
    String groupKey = group.alternativeGroupKey().trim();
    if (StringUtils.hasText(expectedGroupKey)
        && !groupKey.equals(expectedGroupKey.trim())) {
      throw new QuoteBomAlternativeSelectionException(
          ALT_GROUP_NOT_FOUND, "替代组已变化，请刷新后重新选择");
    }
    if (!sameText(
            scope.topProductCode(), group.identity().topProductCode())
        || !sameText(
            scope.priceOrgCode(), group.identity().priceOrgCode())) {
      throw new QuoteBomAlternativeSelectionException(
          ALT_GROUP_NOT_FOUND,
          "替代组不属于当前报价产品或报价组织，请刷新后重试");
    }
    BomAlternativeCandidate standard;
    try {
      standard = group.standardCandidate();
    } catch (IllegalStateException ex) {
      throw new QuoteBomAlternativeSelectionException(
          ALT_GROUP_NOT_FOUND, ex.getMessage(), ex);
    }
    if (group.alternativeCandidates().isEmpty()) {
      throw new QuoteBomAlternativeSelectionException(
          ALT_GROUP_NOT_FOUND, "当前位置不包含替代件，不需要建立替代选择");
    }
    return new ValidatedGroup(group, groupKey, standard);
  }

  private QuoteBomAlternativeSelectionScope validateScope(
      QuoteBomAlternativeSelectionScope scope) {
    if (scope == null) {
      throw new IllegalArgumentException("报价选择作用域不能为空");
    }
    String month = required("periodMonth", scope.periodMonth());
    try {
      YearMonth.parse(month);
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException(
          "periodMonth必须为YYYY-MM", ex);
    }
    if (scope.oaFormItemId() == null
        || scope.oaFormItemId() <= 0) {
      throw new IllegalArgumentException("oaFormItemId不能为空");
    }
    return new QuoteBomAlternativeSelectionScope(
        required("oaNo", scope.oaNo()),
        scope.oaFormItemId(),
        required("topProductCode", scope.topProductCode()),
        month,
        required("priceOrgCode", scope.priceOrgCode()),
        required("businessUnitType", scope.businessUnitType()));
  }

  private void validateExpectedBuildBatch(
      String expectedBuildBatchId,
      BomAlternativeCandidate selected) {
    if (StringUtils.hasText(expectedBuildBatchId)
        && !Objects.equals(
            expectedBuildBatchId.trim(),
            trimToNull(selected.sourceBuildBatchId()))) {
      throw new QuoteBomAlternativeSelectionException(
          ALT_SOURCE_STALE,
          "BOM构建批次已变化，请刷新标准/替代候选后重新选择");
    }
  }

  private BomAlternativeCandidate findCandidate(
      BomAlternativeGroup group, String materialCode) {
    if (!StringUtils.hasText(materialCode)) {
      return null;
    }
    return group.candidates().stream()
        .filter(
            candidate ->
                sameText(
                    candidate.materialCode(), materialCode))
        .findFirst()
        .orElse(null);
  }

  private QuoteBomAlternativeSelectionResult result(
      QuoteBomAlternativeSelection row,
      boolean idempotent,
      boolean reviewRequired,
      boolean persisted) {
    return new QuoteBomAlternativeSelectionResult(
        row.getSelectionNo(),
        row.getAlternativeGroupKey(),
        row.getStandardMaterialCode(),
        row.getSelectedMaterialCode(),
        BomChildType.fromSource(row.getSelectedChildType(), true),
        row.getSelectionSource(),
        row.getSelectionVersion(),
        row.getSelectionStatus(),
        idempotent,
        reviewRequired,
        persisted,
        row.getSourceImportBatchId(),
        row.getSourceBuildBatchId());
  }

  private SelectionPosition position(
      QuoteBomAlternativeSelection row) {
    return new SelectionPosition(
        BomChildType.normalize(row.getParentMaterialCode()),
        normalizePath(row.getParentPath()),
        BomChildType.normalize(row.getBomPurpose()),
        row.getChildSeq(),
        BomChildType.normalize(row.getProcessSeq()));
  }

  private SelectionPosition position(ValidatedGroup group) {
    return new SelectionPosition(
        BomChildType.normalize(
            group.group().identity().parentMaterialNo()),
        normalizePath(parentPath(group.standard().path())),
        BomChildType.normalize(group.group().identity().bomPurpose()),
        group.group().identity().childSeq(),
        BomChildType.normalize(group.group().identity().processSeq()));
  }

  private QuoteBomAlternativeSelectionException conflict(String message) {
    return new QuoteBomAlternativeSelectionException(
        ALT_SELECTION_CONFLICT, message);
  }

  private String currentUsername(String fallback) {
    Authentication authentication =
        SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null
        || !StringUtils.hasText(authentication.getName())) {
      return fallback;
    }
    return authentication.getName();
  }

  private static String parentPath(String childPath) {
    if (!StringUtils.hasText(childPath)) {
      return null;
    }
    String normalized = childPath.trim().replace('\\', '/');
    normalized = normalized.replaceAll("/+", "/");
    if (!normalized.startsWith("/")) {
      normalized = "/" + normalized;
    }
    if (!normalized.endsWith("/")) {
      normalized += "/";
    }
    String withoutTrailing = normalized.substring(0, normalized.length() - 1);
    int lastSlash = withoutTrailing.lastIndexOf('/');
    return lastSlash < 0 ? "/" : withoutTrailing.substring(0, lastSlash + 1);
  }

  private static String normalizePath(String path) {
    if (!StringUtils.hasText(path)) {
      return "";
    }
    String normalized = path.trim().replace('\\', '/');
    normalized = normalized.replaceAll("/+", "/");
    if (!normalized.startsWith("/")) {
      normalized = "/" + normalized;
    }
    if (!normalized.endsWith("/")) {
      normalized += "/";
    }
    return normalized.toUpperCase(Locale.ROOT);
  }

  private static boolean sameText(String left, String right) {
    return BomChildType.normalize(left).equals(BomChildType.normalize(right));
  }

  private static String firstText(String first, String second) {
    String normalized = trimToNull(first);
    return normalized == null ? trimToNull(second) : normalized;
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static String required(String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return value.trim();
  }

  private static String display(String value) {
    return StringUtils.hasText(value) ? value.trim() : "（空）";
  }

  private record ValidatedGroup(
      BomAlternativeGroup group,
      String groupKey,
      BomAlternativeCandidate standard) {
  }

  private record SelectionPosition(
      String parentMaterialCode,
      String parentPath,
      String bomPurpose,
      Integer childSeq,
      String processSeq) {
  }
}
