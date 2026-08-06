package com.sanhua.marketingcost.service.effectivebom;

import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 确认时按结果指纹创建或复用不可变最终有效 BOM。 */
@Service
public class QuoteEffectiveBomPersistenceServiceImpl
    implements QuoteEffectiveBomPersistenceService {

  private static final Comparator<QuoteEffectiveBomNode> NODE_ORDER =
      Comparator.comparing(
              QuoteEffectiveBomNode::getNodePath,
              Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparing(
              QuoteEffectiveBomNode::getNodeKey,
              Comparator.nullsFirst(Comparator.naturalOrder()));

  private final QuoteEffectiveBomRepository repository;
  private final EffectiveBomVariantHasher hasher;
  private final EffectiveBomBuildIdGenerator idGenerator;
  private final Clock clock;

  @Autowired
  public QuoteEffectiveBomPersistenceServiceImpl(
      QuoteEffectiveBomRepository repository,
      EffectiveBomVariantHasher hasher,
      EffectiveBomBuildIdGenerator idGenerator) {
    this(repository, hasher, idGenerator, Clock.systemDefaultZone());
  }

  QuoteEffectiveBomPersistenceServiceImpl(
      QuoteEffectiveBomRepository repository,
      EffectiveBomVariantHasher hasher,
      EffectiveBomBuildIdGenerator idGenerator,
      Clock clock) {
    this.repository = repository;
    this.hasher = hasher;
    this.idGenerator = idGenerator;
    this.clock = clock;
  }

  @Override
  @Transactional(
      propagation = Propagation.REQUIRED,
      rollbackFor = Exception.class)
  public QuoteEffectiveBomPersistenceResult persistConfirmed(
      QuoteEffectiveBomPersistenceRequest request) {
    validateRequest(request);
    EffectiveBomVariantInput input = request.variantInput();
    String variantHash = hasher.hash(input);
    List<QuoteEffectiveBomNode> candidate =
        toEntities(request, variantHash, null);

    List<String> matchingBuilds =
        repository.findBuildBatchIdsByVariantHash(variantHash);
    if (matchingBuilds != null) {
      for (String buildBatchId : matchingBuilds) {
        List<QuoteEffectiveBomNode> existing =
            repository.findNodesByBuildBatchId(buildBatchId);
        if (samePersistedContent(candidate, existing)) {
          return new QuoteEffectiveBomPersistenceResult(
              buildBatchId, variantHash, true, existing.size());
        }
      }
    }

    String buildBatchId = requireBuildBatchId(idGenerator.nextId());
    if (repository.existsBuildBatchId(buildBatchId)) {
      throw new IllegalStateException(
          "最终有效BOM构建编号已存在，拒绝覆盖: " + buildBatchId);
    }
    List<QuoteEffectiveBomNode> newNodes =
        toEntities(request, variantHash, buildBatchId);
    repository.insertAll(newNodes);
    return new QuoteEffectiveBomPersistenceResult(
        buildBatchId, variantHash, false, newNodes.size());
  }

  private List<QuoteEffectiveBomNode> toEntities(
      QuoteEffectiveBomPersistenceRequest request,
      String variantHash,
      String buildBatchId) {
    EffectiveBomVariantInput input = request.variantInput();
    Map<String, Long> selectionIds =
        request.alternativeSelectionIdByGroupKey();
    LocalDateTime createdAt = LocalDateTime.now(clock);
    List<QuoteEffectiveBomNode> result = new ArrayList<>();
    Set<String> nodeKeys = new HashSet<>();
    for (EffectiveBomNodeDraft draft : input.buildResult().nodes()) {
      validateDraft(draft);
      if (!nodeKeys.add(draft.nodeKey())) {
        throw new IllegalArgumentException(
            "最终有效BOM节点键重复: " + draft.nodeKey());
      }
      QuoteEffectiveBomNode node = new QuoteEffectiveBomNode();
      node.setBuildBatchId(buildBatchId);
      node.setOriginMonthlySnapshotId(request.originMonthlySnapshotId());
      node.setEffectiveVariantHash(variantHash);
      node.setTopProductCode(normalize(input.topProductCode()));
      node.setCostPeriodMonth(normalize(input.costPeriodMonth()));
      node.setPriceOrgCode(normalize(input.priceOrgCode()));
      node.setNodeKey(draft.nodeKey());
      node.setParentNodeKey(draft.parentNodeKey());
      node.setNodeLevel(draft.nodeLevel());
      node.setSortSeq(draft.sortSeq());
      node.setNodePath(draft.nodePath());
      node.setMaterialCode(draft.materialCode());
      node.setMaterialName(draft.materialName());
      node.setMaterialSpec(draft.materialSpec());
      node.setQtyPerParent(draft.qtyPerParent());
      node.setQtyPerTop(draft.qtyPerTop());
      node.setSourceMaterialShape(draft.sourceMaterialShape());
      node.setEffectiveMaterialShape(draft.effectiveMaterialShape().name());
      node.setShapeResolutionSource(draft.shapeResolutionSource().name());
      node.setShapePolicyId(draft.shapePolicyId());
      node.setShapePolicyFingerprint(draft.shapePolicyFingerprint());
      node.setSelectedSupplierRatioId(draft.selectedSupplierRatioId());
      node.setSelectedSupplierCode(draft.selectedSupplierCode());
      node.setSelectedSupplierName(draft.selectedSupplierName());
      node.setSelectedSupplyRatio(draft.selectedSupplyRatio());
      node.setAlternativeGroupKey(draft.alternativeGroupKey());
      node.setAlternativeChildType(draft.alternativeChildType());
      node.setAlternativeSelectionId(
          selectionIds.get(draft.alternativeGroupKey()));
      node.setAlternativeSelectionSource(draft.alternativeSelectionSource());
      node.setSourceBomType(draft.sourceBomType());
      node.setSourceBomBatchId(draft.sourceBomBatchId());
      node.setSourceHierarchyId(draft.sourceHierarchyId());
      node.setSourceNodePath(draft.sourceNodePath());
      node.setCreatedAt(createdAt);
      node.setCreatedBy(request.createdBy());
      result.add(node);
    }
    result.sort(NODE_ORDER);
    return List.copyOf(result);
  }

  private static boolean samePersistedContent(
      List<QuoteEffectiveBomNode> candidate,
      List<QuoteEffectiveBomNode> existing) {
    if (existing == null || candidate.size() != existing.size()) {
      return false;
    }
    List<List<Object>> candidateContent = persistedContent(candidate);
    List<List<Object>> existingContent = persistedContent(existing);
    return candidateContent.equals(existingContent);
  }

  private static List<List<Object>> persistedContent(
      List<QuoteEffectiveBomNode> nodes) {
    return nodes.stream()
        .sorted(NODE_ORDER)
        .map(QuoteEffectiveBomPersistenceServiceImpl::persistedNodeContent)
        .toList();
  }

  private static List<Object> persistedNodeContent(QuoteEffectiveBomNode node) {
    return Arrays.asList(
        normalize(node.getTopProductCode()),
        normalize(node.getCostPeriodMonth()),
        normalize(node.getPriceOrgCode()),
        normalize(node.getNodeKey()),
        normalize(node.getParentNodeKey()),
        node.getNodeLevel(),
        node.getSortSeq(),
        normalize(node.getNodePath()),
        normalize(node.getMaterialCode()),
        normalize(node.getMaterialName()),
        normalize(node.getMaterialSpec()),
        normalize(node.getMaterialModel()),
        normalize(node.getMaterialUnit()),
        decimal(node.getQtyPerParent()),
        decimal(node.getQtyPerTop()),
        normalize(node.getSourceMaterialShape()),
        normalize(node.getEffectiveMaterialShape()),
        normalize(node.getShapeResolutionSource()),
        node.getShapePolicyId(),
        normalize(node.getShapePolicyFingerprint()),
        node.getSelectedSupplierRatioId(),
        normalize(node.getSelectedSupplierCode()),
        normalize(node.getSelectedSupplierName()),
        decimal(node.getSelectedSupplyRatio()),
        normalize(node.getAlternativeGroupKey()),
        normalize(node.getAlternativeChildType()),
        normalize(node.getAlternativeSelectionSource()),
        normalize(node.getSourceBomType()),
        normalize(node.getSourceBomBatchId()),
        node.getSourceHierarchyId(),
        normalize(node.getSourceNodePath()));
  }

  private static void validateRequest(
      QuoteEffectiveBomPersistenceRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("最终有效BOM持久化请求不能为空");
    }
    if (request.originMonthlySnapshotId() == null
        || request.originMonthlySnapshotId() <= 0) {
      throw new IllegalArgumentException("首次创建月度卡片ID必须为正数");
    }
    if (request.variantInput() == null) {
      throw new IllegalArgumentException("最终有效BOM指纹输入不能为空");
    }
  }

  private static void validateDraft(EffectiveBomNodeDraft draft) {
    if (draft == null) {
      throw new IllegalArgumentException("最终有效BOM不能包含空节点");
    }
    requireText(draft.nodeKey(), "节点键");
    requireText(draft.nodePath(), "节点路径");
    requireText(draft.materialCode(), "节点料号");
    requireText(draft.sourceBomType(), "原始BOM类型");
    if (draft.nodeLevel() == null
        || draft.sortSeq() == null
        || draft.qtyPerParent() == null
        || draft.qtyPerTop() == null
        || draft.effectiveMaterialShape() == null
        || draft.shapeResolutionSource() == null) {
      throw new IllegalArgumentException(
          "最终有效BOM节点缺少层级、排序、数量或形态: " + draft.nodeKey());
    }
  }

  private static String requireBuildBatchId(String value) {
    String normalized = normalize(value);
    if (normalized == null || normalized.length() > 64) {
      throw new IllegalStateException("最终有效BOM构建编号为空或超过64字符");
    }
    return normalized;
  }

  private static void requireText(String value, String field) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
  }

  private static String decimal(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros().toPlainString();
  }

  private static String normalize(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
