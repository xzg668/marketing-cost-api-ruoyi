package com.sanhua.marketingcost.service.bomalternative;

import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeKey;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeRepository;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 从冻结最终树恢复选择证据，不读取实时U9候选，不改写第一次OA的选择历史。
 */
@Service
public class QuoteBomAlternativeMonthlyInheritanceServiceImpl
    implements QuoteBomAlternativeMonthlyInheritanceService {

  public static final String ALT_MONTHLY_FROZEN = "ALT_MONTHLY_FROZEN";
  public static final String ALT_MONTHLY_INHERITANCE_INVALID =
      "ALT_MONTHLY_INHERITANCE_INVALID";

  private static final String FREEZE_STATUS_FROZEN = "FROZEN";
  private static final String FREEZE_STATUS_DRAFT = "DRAFT";

  private final QuoteBomMonthlyFreezeRepository monthlyRepository;
  private final QuoteEffectiveBomRepository effectiveBomRepository;
  private final QuoteBomAlternativeSelectionRepository selectionRepository;
  private final Clock clock;

  @Autowired
  public QuoteBomAlternativeMonthlyInheritanceServiceImpl(
      QuoteBomMonthlyFreezeRepository monthlyRepository,
      QuoteEffectiveBomRepository effectiveBomRepository,
      QuoteBomAlternativeSelectionRepository selectionRepository) {
    this(
        monthlyRepository,
        effectiveBomRepository,
        selectionRepository,
        Clock.systemDefaultZone());
  }

  QuoteBomAlternativeMonthlyInheritanceServiceImpl(
      QuoteBomMonthlyFreezeRepository monthlyRepository,
      QuoteEffectiveBomRepository effectiveBomRepository,
      QuoteBomAlternativeSelectionRepository selectionRepository,
      Clock clock) {
    this.monthlyRepository = Objects.requireNonNull(monthlyRepository, "monthlyRepository");
    this.effectiveBomRepository =
        Objects.requireNonNull(effectiveBomRepository, "effectiveBomRepository");
    this.selectionRepository =
        Objects.requireNonNull(selectionRepository, "selectionRepository");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = Exception.class)
  public QuoteBomAlternativeMonthlyInheritanceResult inheritIfFrozen(
      QuoteBomMonthlyFreezeKey monthlyKey,
      QuoteBomAlternativeSelectionScope targetScope) {
    QuoteBomAlternativeSelectionScope scope = validateScope(monthlyKey, targetScope);
    QuoteBomMonthlySnapshot snapshot =
        monthlyRepository.findActiveSuccessForUpdate(monthlyKey).orElse(null);
    if (snapshot == null) {
      return QuoteBomAlternativeMonthlyInheritanceResult.notFrozen();
    }
    String freezeStatus = normalize(snapshot.getFreezeStatus());
    if (freezeStatus == null || FREEZE_STATUS_DRAFT.equals(freezeStatus)) {
      return QuoteBomAlternativeMonthlyInheritanceResult.notFrozen();
    }
    if (!FREEZE_STATUS_FROZEN.equals(freezeStatus)) {
      throw invalid("月度BOM卡片冻结状态非法: " + snapshot.getFreezeStatus());
    }

    String buildBatchId = requireText(
        snapshot.getEffectiveBuildBatchId(), "已冻结月度卡片缺少最终构建编号");
    boolean sourceOa = Objects.equals(
        snapshot.getSourceOaFormItemId(), scope.oaFormItemId());
    boolean confirmed =
        monthlyRepository.hasActiveConfirmation(
                scope.oaNo(),
                scope.oaFormItemId(),
                scope.topProductCode(),
                scope.periodMonth())
            || monthlyRepository.hasActiveConfirmationForBuild(buildBatchId);
    if (!confirmed) {
      if (sourceOa) {
        // 兼容历史上“进入第2步即冻结”的临时版本：查询层会按实时规则重新预览；
        // 真正保存新选择或重新进入第2步时再显式释放旧指针并覆盖暂存。
        return new QuoteBomAlternativeMonthlyInheritanceResult(
            false, false, snapshot.getId(), buildBatchId, List.of());
      }
      return new QuoteBomAlternativeMonthlyInheritanceResult(
          false, false, snapshot.getId(), buildBatchId, List.of());
    }
    List<QuoteEffectiveBomNode> nodes =
        effectiveBomRepository.findNodesByBuildBatchId(buildBatchId);
    if (nodes == null || nodes.isEmpty()) {
      throw invalid("已冻结月度卡片指向的最终BOM不存在: " + buildBatchId);
    }
    validateFrozenTree(monthlyKey, buildBatchId, nodes);
    List<FrozenGroupEvidence> evidences = extractEvidence(nodes);
    Map<Long, QuoteBomAlternativeSelection> sourceById =
        loadSourceSelections(evidences);

    List<QuoteBomAlternativeSelection> currents =
        selectionRepository.findCurrentsForUpdate(scope);
    Map<String, QuoteBomAlternativeSelection> currentByGroup =
        currents.stream()
            .collect(
                Collectors.toMap(
                    row -> normalizeKey(row.getAlternativeGroupKey()),
                    Function.identity(),
                    (left, right) -> {
                      throw invalid("当前OA同一替代组存在两个有效选择");
                    },
                    LinkedHashMap::new));
    Set<String> frozenGroups =
        evidences.stream()
            .map(FrozenGroupEvidence::groupKey)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    if (sourceOa) {
      List<QuoteBomAlternativeSelection> preserved =
          preserveSourceOaSelections(
              snapshot, evidences, sourceById, currentByGroup);
      return new QuoteBomAlternativeMonthlyInheritanceResult(
          true, false, snapshot.getId(), buildBatchId, preserved);
    }

    retireUnreachableCurrents(currents, frozenGroups);
    List<QuoteBomAlternativeSelection> inherited = new ArrayList<>();
    boolean wrote = false;
    for (FrozenGroupEvidence evidence : evidences) {
      QuoteBomAlternativeSelection source = sourceById.get(evidence.selectionId());
      QuoteBomAlternativeSelection current = currentByGroup.get(evidence.groupKey());
      if (isSameInheritance(current, snapshot.getId(), evidence)) {
        inherited.add(current);
        continue;
      }
      if (current != null) {
        if (QuoteBomAlternativeSelection.SOURCE_INHERITED_MONTHLY.equals(
            current.getSelectionSource())) {
          throw invalid("当前OA替代选择已继承自另一张月度卡片，拒绝覆盖");
        }
        if (!QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD.equals(
            current.getSelectionSource())) {
          throw frozen("本月客户场景已冻结，当前OA不能保留或新增人工替代选择");
        }
        transition(current, QuoteBomAlternativeSelection.STATUS_SUPERSEDED);
      }
      QuoteBomAlternativeSelection latest =
          selectionRepository.findLatest(scope, evidence.groupKey());
      int version = latest == null ? 1 : latest.getSelectionVersion() + 1;
      QuoteBomAlternativeSelection created =
          inheritedSelection(scope, snapshot, buildBatchId, evidence, source, version);
      insert(created);
      inherited.add(created);
      wrote = true;
    }
    inherited.sort(Comparator.comparing(QuoteBomAlternativeSelection::getAlternativeGroupKey));
    return new QuoteBomAlternativeMonthlyInheritanceResult(
        true, wrote, snapshot.getId(), buildBatchId, inherited);
  }

  @Override
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = Exception.class)
  public boolean releaseProvisional(
      QuoteBomMonthlyFreezeKey monthlyKey,
      QuoteBomAlternativeSelectionScope targetScope) {
    QuoteBomAlternativeSelectionScope scope = validateScope(monthlyKey, targetScope);
    QuoteBomMonthlySnapshot snapshot =
        monthlyRepository.findActiveSuccessForUpdate(monthlyKey).orElse(null);
    if (snapshot == null) {
      return false;
    }
    String freezeStatus = normalize(snapshot.getFreezeStatus());
    if (freezeStatus == null || FREEZE_STATUS_DRAFT.equals(freezeStatus)) {
      return false;
    }
    if (!FREEZE_STATUS_FROZEN.equals(freezeStatus)) {
      throw invalid("月度BOM卡片冻结状态非法: " + snapshot.getFreezeStatus());
    }
    if (!Objects.equals(snapshot.getSourceOaFormItemId(), scope.oaFormItemId())) {
      throw frozen("本月方案已由其他报价产品确认，当前报价只能沿用");
    }

    String buildBatchId =
        requireText(snapshot.getEffectiveBuildBatchId(), "冻结月度卡片缺少最终构建编号");
    if (monthlyRepository.hasActiveConfirmation(
            scope.oaNo(),
            scope.oaFormItemId(),
            scope.topProductCode(),
            scope.periodMonth())
        || monthlyRepository.hasActiveConfirmationForBuild(buildBatchId)) {
      throw frozen("报价物料明细已经确认，计价方案不能再调整");
    }

    releaseLocked(snapshot, buildBatchId);
    return true;
  }

  private void releaseLocked(
      QuoteBomMonthlySnapshot snapshot, String buildBatchId) {
    LocalDateTime now = LocalDateTime.now(clock);
    int released = monthlyRepository.releaseProvisional(
        snapshot.getId(), buildBatchId, now);
    if (released != 1) {
      throw invalid("待确认计价BOM状态已变化，请刷新后重新选择");
    }
    monthlyRepository.clearStatusBindings(buildBatchId, now);
    effectiveBomRepository.deleteUnreferencedByOriginMonthlySnapshotId(
        snapshot.getId());
  }

  private List<QuoteBomAlternativeSelection> preserveSourceOaSelections(
      QuoteBomMonthlySnapshot snapshot,
      List<FrozenGroupEvidence> evidences,
      Map<Long, QuoteBomAlternativeSelection> sourceById,
      Map<String, QuoteBomAlternativeSelection> currentByGroup) {
    List<QuoteBomAlternativeSelection> result = new ArrayList<>();
    for (FrozenGroupEvidence evidence : evidences) {
      QuoteBomAlternativeSelection current = currentByGroup.get(evidence.groupKey());
      if (current == null) {
        QuoteBomAlternativeSelection source = sourceById.get(evidence.selectionId());
        if (belongsToSourceOa(source, snapshot)) {
          current = source;
        }
      }
      if (current == null || !matchesEvidence(current, evidence)) {
        throw invalid("首次冻结OA的替代选择与最终树证据不一致: " + evidence.groupKey());
      }
      result.add(current);
    }
    result.sort(Comparator.comparing(QuoteBomAlternativeSelection::getAlternativeGroupKey));
    return List.copyOf(result);
  }

  private void retireUnreachableCurrents(
      List<QuoteBomAlternativeSelection> currents, Set<String> frozenGroups) {
    for (QuoteBomAlternativeSelection current : currents) {
      String groupKey = normalizeKey(current.getAlternativeGroupKey());
      if (frozenGroups.contains(groupKey)) {
        continue;
      }
      if (!QuoteBomAlternativeSelection.SOURCE_AUTO_STANDARD.equals(
          current.getSelectionSource())) {
        throw frozen("本月最终树不包含替代组" + groupKey + "，不能保留当前人工选择");
      }
      transition(current, QuoteBomAlternativeSelection.STATUS_STALE);
    }
  }

  private Map<Long, QuoteBomAlternativeSelection> loadSourceSelections(
      List<FrozenGroupEvidence> evidences) {
    List<Long> ids =
        evidences.stream()
            .map(FrozenGroupEvidence::selectionId)
            .distinct()
            .toList();
    Map<Long, QuoteBomAlternativeSelection> result =
        selectionRepository.findByIds(ids).stream()
            .collect(
                Collectors.toMap(
                    QuoteBomAlternativeSelection::getId,
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    for (FrozenGroupEvidence evidence : evidences) {
      QuoteBomAlternativeSelection source = result.get(evidence.selectionId());
      if (source == null || !matchesEvidence(source, evidence)) {
        throw invalid("最终树引用的替代选择证据不存在或不一致: " + evidence.groupKey());
      }
    }
    return result;
  }

  private List<FrozenGroupEvidence> extractEvidence(
      List<QuoteEffectiveBomNode> nodes) {
    Map<String, FrozenGroupEvidence> byGroup = new LinkedHashMap<>();
    nodes.stream()
        .sorted(
            Comparator.comparing(
                    QuoteEffectiveBomNode::getNodeLevel,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(
                    QuoteEffectiveBomNode::getSortSeq,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(
                    QuoteEffectiveBomNode::getNodePath,
                    Comparator.nullsLast(Comparator.naturalOrder())))
        .forEach(
            node -> {
              String groupKey = normalize(node.getAlternativeGroupKey());
              if (groupKey == null) {
                return;
              }
              Long selectionId = node.getAlternativeSelectionId();
              if (selectionId == null || selectionId <= 0) {
                throw invalid("冻结最终树替代组缺少原选择ID: " + groupKey);
              }
              FrozenGroupEvidence evidence =
                  new FrozenGroupEvidence(
                      normalizeKey(groupKey),
                      requireText(node.getMaterialCode(), "冻结最终树替代节点缺少料号"),
                      normalizeChildType(node.getAlternativeChildType()),
                      selectionId);
              FrozenGroupEvidence existing = byGroup.putIfAbsent(evidence.groupKey(), evidence);
              if (existing != null && !existing.equals(evidence)) {
                throw invalid("冻结最终树同一替代组存在冲突证据: " + groupKey);
              }
            });
    return List.copyOf(byGroup.values());
  }

  private QuoteBomAlternativeSelection inheritedSelection(
      QuoteBomAlternativeSelectionScope scope,
      QuoteBomMonthlySnapshot snapshot,
      String buildBatchId,
      FrozenGroupEvidence evidence,
      QuoteBomAlternativeSelection source,
      int version) {
    LocalDateTime now = LocalDateTime.now(clock);
    QuoteBomAlternativeSelection row = new QuoteBomAlternativeSelection();
    row.setSelectionNo(
        "BOM-ALT-"
            + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT));
    row.setOaNo(scope.oaNo());
    row.setOaFormItemId(scope.oaFormItemId());
    row.setTopProductCode(scope.topProductCode());
    row.setPeriodMonth(scope.periodMonth());
    row.setPriceOrgCode(scope.priceOrgCode());
    row.setAlternativeGroupKey(evidence.groupKey());
    row.setParentPath(source.getParentPath());
    row.setParentMaterialCode(source.getParentMaterialCode());
    row.setParentMaterialName(source.getParentMaterialName());
    row.setChildSeq(source.getChildSeq());
    row.setProcessSeq(source.getProcessSeq());
    row.setBomPurpose(source.getBomPurpose());
    row.setBomVersion(source.getBomVersion());
    row.setSourceEffectiveFrom(source.getSourceEffectiveFrom());
    row.setSourceEffectiveTo(source.getSourceEffectiveTo());
    row.setStandardMaterialCode(source.getStandardMaterialCode());
    row.setSelectedMaterialCode(evidence.selectedMaterialCode());
    row.setSelectedChildType(evidence.selectedChildType());
    row.setSelectionSource(QuoteBomAlternativeSelection.SOURCE_INHERITED_MONTHLY);
    row.setSelectionVersion(version);
    row.setSelectionStatus(QuoteBomAlternativeSelection.STATUS_ACTIVE);
    row.setCurrentSlot(QuoteBomAlternativeSelection.CURRENT_SLOT);
    row.setCandidateSnapshotJson(source.getCandidateSnapshotJson());
    row.setSourceImportBatchId(source.getSourceImportBatchId());
    row.setSourceBuildBatchId(source.getSourceBuildBatchId());
    row.setSelectedBy("system");
    row.setSelectedAt(now);
    row.setSelectionRemark(
        "继承月度冻结BOM选择；月度卡片=" + snapshot.getId() + "；最终构建=" + buildBatchId);
    row.setInheritedMonthlySnapshotId(snapshot.getId());
    row.setBusinessUnitType(scope.businessUnitType());
    row.setCreatedAt(now);
    row.setUpdatedAt(now);
    return row;
  }

  private void insert(QuoteBomAlternativeSelection row) {
    try {
      selectionRepository.insert(row);
    } catch (DataAccessException ex) {
      throw new QuoteBomAlternativeSelectionException(
          QuoteBomAlternativeSelectionServiceImpl.ALT_SELECTION_CONFLICT,
          "月度替代选择已被其他请求继承，请刷新后重试",
          ex);
    }
  }

  private void transition(QuoteBomAlternativeSelection row, String status) {
    boolean changed =
        selectionRepository.transitionCurrent(
            row.getId(), row.getSelectionVersion(), status, LocalDateTime.now(clock));
    if (!changed) {
      throw new QuoteBomAlternativeSelectionException(
          QuoteBomAlternativeSelectionServiceImpl.ALT_SELECTION_CONFLICT,
          "当前替代选择已被其他请求更新，请刷新后重试");
    }
    row.setSelectionStatus(status);
    row.setCurrentSlot(null);
  }

  private static QuoteBomAlternativeSelectionScope validateScope(
      QuoteBomMonthlyFreezeKey key,
      QuoteBomAlternativeSelectionScope scope) {
    if (key == null || scope == null) {
      throw new IllegalArgumentException("月度冻结键和选择作用域不能为空");
    }
    requireEqual(scope.periodMonth(), key.costPeriodMonth(), "核算月份");
    requireEqual(scope.topProductCode(), key.productCode(), "产品料号");
    requireEqual(scope.priceOrgCode(), key.priceOrgCode(), "U9价格组织");
    if (!StringUtils.hasText(scope.oaNo())
        || scope.oaFormItemId() == null
        || scope.oaFormItemId() <= 0
        || !StringUtils.hasText(scope.businessUnitType())) {
      throw new IllegalArgumentException("当前OA替代选择作用域不完整");
    }
    return scope;
  }

  private static void validateFrozenTree(
      QuoteBomMonthlyFreezeKey key,
      String buildBatchId,
      List<QuoteEffectiveBomNode> nodes) {
    for (QuoteEffectiveBomNode node : nodes) {
      requireEqual(node.getBuildBatchId(), buildBatchId, "最终构建编号");
      requireEqual(node.getTopProductCode(), key.productCode(), "最终树产品料号");
      requireEqual(node.getCostPeriodMonth(), key.costPeriodMonth(), "最终树核算月份");
      requireEqual(node.getPriceOrgCode(), key.priceOrgCode(), "最终树U9价格组织");
    }
  }

  private static boolean isSameInheritance(
      QuoteBomAlternativeSelection current,
      Long snapshotId,
      FrozenGroupEvidence evidence) {
    return current != null
        && QuoteBomAlternativeSelection.SOURCE_INHERITED_MONTHLY.equals(
            current.getSelectionSource())
        && Objects.equals(current.getInheritedMonthlySnapshotId(), snapshotId)
        && matchesEvidence(current, evidence);
  }

  private static boolean matchesEvidence(
      QuoteBomAlternativeSelection row, FrozenGroupEvidence evidence) {
    return row != null
        && normalizeKey(row.getAlternativeGroupKey()).equals(evidence.groupKey())
        && sameText(row.getSelectedMaterialCode(), evidence.selectedMaterialCode())
        && sameText(row.getSelectedChildType(), evidence.selectedChildType());
  }

  private static boolean belongsToSourceOa(
      QuoteBomAlternativeSelection row, QuoteBomMonthlySnapshot snapshot) {
    return row != null
        && Objects.equals(row.getOaFormItemId(), snapshot.getSourceOaFormItemId());
  }

  private static String normalizeChildType(String value) {
    String normalized = normalizeKey(value);
    if (!QuoteBomAlternativeSelection.CHILD_TYPE_STANDARD.equals(normalized)
        && !QuoteBomAlternativeSelection.CHILD_TYPE_ALTERNATIVE.equals(normalized)) {
      throw invalid("冻结最终树替代节点类型非法: " + value);
    }
    return normalized;
  }

  private static void requireEqual(String actual, String expected, String field) {
    if (!Objects.equals(normalizeKey(actual), normalizeKey(expected))) {
      throw new IllegalArgumentException(field + "与月度冻结场景不一致");
    }
  }

  private static String requireText(String value, String message) {
    String normalized = normalize(value);
    if (normalized == null) {
      throw invalid(message);
    }
    return normalized;
  }

  private static String normalizeKey(String value) {
    String normalized = normalize(value);
    return normalized == null ? "" : normalized.toUpperCase(Locale.ROOT);
  }

  private static boolean sameText(String left, String right) {
    return normalizeKey(left).equals(normalizeKey(right));
  }

  private static String normalize(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static QuoteBomAlternativeSelectionException frozen(String message) {
    return new QuoteBomAlternativeSelectionException(ALT_MONTHLY_FROZEN, message);
  }

  private static QuoteBomAlternativeSelectionException invalid(String message) {
    return new QuoteBomAlternativeSelectionException(
        ALT_MONTHLY_INHERITANCE_INVALID, message);
  }

  private record FrozenGroupEvidence(
      String groupKey,
      String selectedMaterialCode,
      String selectedChildType,
      Long selectionId) {
  }
}
