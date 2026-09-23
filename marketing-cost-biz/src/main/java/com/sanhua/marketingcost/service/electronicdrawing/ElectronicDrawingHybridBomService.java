package com.sanhua.marketingcost.service.electronicdrawing;

import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomException.MAPPING_INCOMPLETE;
import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomException.PERSISTENCE_INVALID;
import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomException.SOURCE_VERSION_INVALID;
import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomException.TASK_VERSION_CONFLICT;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.AssembleCommand;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.ElectronicNode;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.HybridBom;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.MaterialSnapshot;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.Node;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingBomSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 把料号已就绪的电子图库源版本合成为同版本的活动混合 BOM 明细。 */
@Service
public class ElectronicDrawingHybridBomService {
  private static final String BOM_SOURCE = "ELECTRONIC_DRAWING_EXCEL";
  private static final String VERSION_DRAFT = "DRAFT";

  private final ElectronicDrawingWorkflowContextPort contextPort;
  private final QuoteBomSupplementVersionMapper versionMapper;
  private final QuoteBomSupplementDetailMapper detailMapper;
  private final ElectronicDrawingSourceNodeRepository sourceNodeRepository;
  private final MaterialMasterRawMapper materialMapper;
  private final ElectronicDrawingHybridBomAssembler assembler;
  private final TechnicalDataManufacturingBomSource manufacturing;

  public ElectronicDrawingHybridBomService(
      ElectronicDrawingWorkflowContextPort contextPort,
      QuoteBomSupplementVersionMapper versionMapper,
      QuoteBomSupplementDetailMapper detailMapper,
      ElectronicDrawingSourceNodeRepository sourceNodeRepository,
      MaterialMasterRawMapper materialMapper,
      ElectronicDrawingHybridBomAssembler assembler, TechnicalDataManufacturingBomSource manufacturing) {
    this.contextPort = contextPort;
    this.versionMapper = versionMapper;
    this.detailMapper = detailMapper;
    this.sourceNodeRepository = sourceNodeRepository;
    this.materialMapper = materialMapper;
    this.assembler = assembler;
    this.manufacturing = manufacturing;
  }

  @Transactional
  public void invalidateDraftComposition(ElectronicDrawingWorkContext context) {
    QuoteBomSupplementVersion version = validateVersion(context);
    LocalDateTime now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    if (versionMapper.updateElectronicDrawingCompositionFingerprint(version.getId(),
        version.getCompositionFingerprint(), null, context.materialOrgCode(), now) != 1) {
      throw invalid(TASK_VERSION_CONFLICT, "电子图库来源已变化，请刷新后保存原材料");
    }
    detailMapper.deleteElectronicDrawingHybridDraft(version.getId());
    contextPort.updateStage(context, ElectronicDrawingWorkflowStage.MATCHED,
        context.assigneeUserId(), context.assigneeName(), now);
  }

  // 组树缺口发生在任何明细写入之前；持久化失败仍须回滚整个替换事务。
  @Transactional(noRollbackFor = ElectronicDrawingHybridBomValidationException.class)
  public CompositionResult compose(
      Long workflowId, String businessUnitType, String applicableOrgCode, String accountingMonth) {
    ElectronicDrawingWorkContext context = contextPort.load(
        workflowId, businessUnitType, applicableOrgCode, accountingMonth);
    QuoteBomSupplementVersion version = validateVersion(context);
    List<ElectronicDrawingSourceNode> sourceNodes =
        sourceNodeRepository.findByVersionId(version.getId());
    if (sourceNodes.isEmpty()) {
      throw invalid(SOURCE_VERSION_INVALID, "电子图库源版本没有原始节点");
    }
    ensureMappingComplete(sourceNodes);

    Set<String> materialCodes = sourceNodes.stream()
        .map(ElectronicDrawingSourceNode::getResolvedMaterialCode)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    String rootCode = rootMaterialCode(context, version);
    materialCodes.add(rootCode);
    Map<String, MaterialMasterRaw> materials = currentMaterials(
        materialCodes, context.materialOrgCode());
    MaterialMasterRaw root = materials.get(normalize(rootCode));
    List<ElectronicNode> electronicNodes = sourceNodes.stream()
        .map(source -> electronicNode(source, materials))
        .toList();
    HybridBom hybrid = assembler.assemble(new AssembleCommand(
        version.getOaNo(), version.getOaFormItemId(), material(root),
        context.accountingMonth(), context.priceOrgCode(), context.materialOrgCode(),
        context.businessUnitType(), null, effectiveDate(version, context), electronicNodes, manufacturing.load(context)));

    List<QuoteBomSupplementDetail> existing = details(version.getId());
    if (Objects.equals(version.getCompositionFingerprint(), hybrid.compositionFingerprint())) {
      if (!sameDetails(existing, hybrid.nodes(), version)) {
        throw invalid(PERSISTENCE_INVALID,
            "混合 BOM 指纹与已保存明细不一致，禁止静默覆盖");
      }
      return result(context, version, hybrid, true);
    }

    LocalDateTime now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    List<QuoteBomSupplementDetail> next = toDetails(version, hybrid.nodes(), now);
    detailMapper.deleteElectronicDrawingHybridDraft(version.getId());
    if (detailMapper.insertElectronicDrawingHybridBatch(next) != next.size()) {
      throw invalid(PERSISTENCE_INVALID, "混合 BOM 明细批量写入数量不一致");
    }
    if (versionMapper.updateElectronicDrawingCompositionFingerprint(
        version.getId(), version.getCompositionFingerprint(), hybrid.compositionFingerprint(),
        context.materialOrgCode(), now) != 1) {
      throw invalid(TASK_VERSION_CONFLICT, "电子图库源版本已变化，请重新合成");
    }
    ElectronicDrawingWorkContext refreshed;
    try {
      refreshed = contextPort.touch(context, version.getId(), 0L, "SYSTEM", now);
    } catch (ElectronicDrawingWorkflowRetryException exception) {
      throw invalid(TASK_VERSION_CONFLICT, "产品任务版本已变化，请重新合成");
    }
    version.setCompositionFingerprint(hybrid.compositionFingerprint());
    return result(refreshed, version, hybrid, false);
  }

  private QuoteBomSupplementVersion validateVersion(ElectronicDrawingWorkContext context) {
    if (context == null
        || context.workflowId() == null
        || !context.active()
        || !context.bomRequired()
        || context.preparationId() == null
        || context.sourceVersionId() == null
        || context.revision() == null
        || text(context.materialOrgCode()) == null
        || text(context.priceOrgCode()) == null) {
      throw invalid(SOURCE_VERSION_INVALID, "当前报价产品没有可合成的电子图库源版本");
    }
    QuoteBomSupplementVersion version = versionMapper.selectById(context.sourceVersionId());
    boolean valid = version != null
        && Objects.equals(version.getPreparationId(), context.preparationId())
        && same(version.getTaskNo(), context.taskNo())
        && same(version.getQuoteProductCode(), context.quoteProductCode())
        && same(version.getPeriodMonth(), context.accountingMonth())
        && same(version.getMaterialOrgCode(), context.materialOrgCode())
        && BOM_SOURCE.equals(version.getBomSource())
        && VERSION_DRAFT.equals(version.getVersionStatus())
        && Objects.equals(version.getActiveFlag(), 1)
        && text(version.getOaNo()) != null
        && version.getOaFormItemId() != null;
    if (!valid) {
      throw invalid(SOURCE_VERSION_INVALID, "当前电子图库源版本与产品任务不一致或不可编辑");
    }
    return version;
  }

  private void ensureMappingComplete(List<ElectronicDrawingSourceNode> sourceNodes) {
    for (ElectronicDrawingSourceNode node : sourceNodes) {
      boolean resolved = (ElectronicDrawingSourceNode.MATCH_AUTO.equals(node.getMatchStatus())
          || ElectronicDrawingSourceNode.MATCH_MANUAL.equals(node.getMatchStatus()))
          && text(node.getResolvedMaterialCode()) != null
          && text(node.getResolvedBy()) != null
          && node.getResolvedAt() != null;
      if (!resolved) {
        throw invalid(MAPPING_INCOMPLETE,
            "电子图库仍有待确认物料：" + text(node.getDrawingCode()));
      }
    }
  }

  private Map<String, MaterialMasterRaw> currentMaterials(
      Collection<String> codes, String materialOrganizationCode) {
    List<MaterialMasterRaw> rows = materialMapper.selectByLatestBatchAndCodes(
        codes, null, materialOrganizationCode);
    Map<String, List<MaterialMasterRaw>> grouped = rows.stream()
        .filter(row -> text(row.getMaterialCode()) != null)
        .collect(Collectors.groupingBy(
            row -> normalize(row.getMaterialCode()), LinkedHashMap::new, Collectors.toList()));
    Set<String> requested = codes.stream().map(ElectronicDrawingHybridBomService::normalize)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (!grouped.keySet().equals(requested)
        || grouped.values().stream().anyMatch(group -> group.size() != 1)) {
      throw invalid(MAPPING_INCOMPLETE,
          "电子图库映射料号或顶层产品不是当前物料组织中的唯一正常料品");
    }
    return grouped.entrySet().stream().collect(Collectors.toMap(
        Map.Entry::getKey, entry -> entry.getValue().getFirst(),
        (first, ignored) -> first, LinkedHashMap::new));
  }

  /** 无正式料号的报价保留内部核算身份，组树时按已选图号和来源型号找到真实顶层料品。 */
  private String rootMaterialCode(ElectronicDrawingWorkContext context, QuoteBomSupplementVersion version) {
    String quoteCode = required(context.quoteProductCode(), "报价产品标识");
    String temporaryCode = QuoteProductIdentityUtils.resolveCostingCode(
        null, context.productModel(), version.getElectronicDrawingNo());
    if (!same(quoteCode, temporaryCode)) return quoteCode;
    var direct = materialMapper.selectByLatestBatchAndCodes(Set.of(quoteCode), null, context.materialOrgCode());
    if (!direct.isEmpty()) return quoteCode;
    var candidates = materialMapper.selectByDrawingIdentities(Set.of(normalize(version.getElectronicDrawingNo())),
        null, context.materialOrgCode(), 1000).stream()
        .filter(row -> same(row.getDrawingNo(), version.getElectronicDrawingNo())
            && (text(context.productModel()) == null || same(row.getMaterialModel(), context.productModel())))
        .map(MaterialMasterRaw::getMaterialCode).filter(Objects::nonNull).distinct().toList();
    if (candidates.size() != 1) throw invalid(MAPPING_INCOMPLETE,
        "已取得图库，但图号和型号未对应唯一顶层 U9 料品，请财务核实料品档案后重新检查");
    return candidates.getFirst();
  }

  private ElectronicNode electronicNode(
      ElectronicDrawingSourceNode source, Map<String, MaterialMasterRaw> materials) {
    MaterialMasterRaw material = materials.get(normalize(source.getResolvedMaterialCode()));
    return new ElectronicNode(
        source.getId(), source.getSourceRowNo(), source.getSourceSequence(),
        source.getParentSourceSequence(), source.getDrawingCode(), source.getSourceName(),
        source.getQty(), source.getMatchStatus(), material(material));
  }

  private MaterialSnapshot material(MaterialMasterRaw row) {
    if (row == null) throw invalid(MAPPING_INCOMPLETE, "当前组织料品档案不存在");
    return new MaterialSnapshot(
        text(row.getMaterialCode()), text(row.getMaterialName()), text(row.getMaterialSpec()),
        text(row.getMaterialModel()), text(row.getDrawingNo()), text(row.getShapeAttr()),
        text(row.getMainCategoryCode()), text(row.getProductionCategory()),
        text(row.getCostElement()), text(row.getUnit()));
  }

  private List<QuoteBomSupplementDetail> toDetails(
      QuoteBomSupplementVersion version, List<Node> nodes, LocalDateTime now) {
    Map<String, Node> byKey = nodes.stream().collect(Collectors.toMap(
        Node::nodeKey, Function.identity()));
    List<QuoteBomSupplementDetail> result = new java.util.ArrayList<>(nodes.size());
    for (int index = 0; index < nodes.size(); index++) {
      Node node = nodes.get(index);
      Node parent = node.parentNodeKey() == null ? null : byKey.get(node.parentNodeKey());
      QuoteBomSupplementDetail detail = new QuoteBomSupplementDetail();
      detail.setSupplementVersionId(version.getId());
      detail.setPreparationId(version.getPreparationId());
      detail.setOaNo(version.getOaNo());
      detail.setOaFormItemId(version.getOaFormItemId());
      detail.setQuoteProductCode(version.getQuoteProductCode());
      detail.setSupplementScope(version.getSupplementScope());
      detail.setLineNo(index + 1);
      detail.setLevel(node.level());
      detail.setParentCode(parent == null ? null : parent.materialCode());
      detail.setMaterialCode(node.materialCode());
      detail.setMaterialName(node.materialName());
      detail.setMaterialSpec(node.materialSpec());
      detail.setMaterialModel(node.materialModel());
      detail.setDrawingNo(node.drawingNo());
      detail.setShapeAttr(node.shapeAttr());
      detail.setMainCategoryCode(node.mainCategoryCode());
      detail.setSourceCategory(node.sourceCategory());
      detail.setCostElementCode(node.costElementCode());
      detail.setBomPurpose(node.bomPurpose());
      detail.setBomVersion(node.bomVersion());
      detail.setQtyPerParent(node.qtyPerParent());
      detail.setQtyPerTop(node.qtyPerTop());
      detail.setParentBaseQty(node.parentBaseQty());
      detail.setUnit(node.unit());
      detail.setPath(node.path());
      detail.setSortSeq(node.sortSeq());
      detail.setSourceRawHierarchyId(node.sourceRawHierarchyId());
      detail.setSourceU9BomId(node.sourceU9BomId());
      detail.setNodeSourceType(node.nodeSourceType());
      detail.setSourceElectronicNodeId(node.sourceElectronicNodeId());
      detail.setMappingStatus(node.mappingStatus());
      detail.setManualFlag(ElectronicDrawingSourceNode.MATCH_MANUAL.equals(node.mappingStatus())
          || ElectronicDrawingHybridBomAssembler.SOURCE_TECHNICAL_RAW.equals(node.nodeSourceType()) ? 1 : 0);
      detail.setRemark(node.nodeSourceType() + ":" + node.nodeKey());
      detail.setCreatedAt(now);
      detail.setUpdatedAt(now);
      result.add(detail);
    }
    return List.copyOf(result);
  }

  private List<QuoteBomSupplementDetail> details(Long versionId) {
    return detailMapper.selectList(Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
        .eq(QuoteBomSupplementDetail::getSupplementVersionId, versionId)
        .orderByAsc(QuoteBomSupplementDetail::getLineNo));
  }

  private boolean sameDetails(
      List<QuoteBomSupplementDetail> stored,
      List<Node> assembled,
      QuoteBomSupplementVersion version) {
    if (stored.size() != assembled.size()) return false;
    Map<String, Node> byKey = assembled.stream().collect(Collectors.toMap(
        Node::nodeKey, Function.identity()));
    for (int index = 0; index < stored.size(); index++) {
      QuoteBomSupplementDetail left = stored.get(index);
      Node right = assembled.get(index);
      Node parent = right.parentNodeKey() == null ? null : byKey.get(right.parentNodeKey());
      String expectedRemark = right.nodeSourceType() + ":" + right.nodeKey();
      int expectedManual = ElectronicDrawingSourceNode.MATCH_MANUAL.equals(right.mappingStatus())
          || ElectronicDrawingHybridBomAssembler.SOURCE_TECHNICAL_RAW.equals(right.nodeSourceType()) ? 1 : 0;
      // 导入行 ID 仅是当时的来源记录，不参与跨批次业务内容比较。
      if (!Objects.equals(left.getSupplementVersionId(), version.getId())
          || !Objects.equals(left.getPreparationId(), version.getPreparationId())
          || !Objects.equals(left.getOaNo(), version.getOaNo())
          || !Objects.equals(left.getOaFormItemId(), version.getOaFormItemId())
          || !Objects.equals(left.getQuoteProductCode(), version.getQuoteProductCode())
          || !Objects.equals(left.getSupplementScope(), version.getSupplementScope())
          || !Objects.equals(left.getLineNo(), index + 1)
          || !Objects.equals(left.getLevel(), right.level())
          || !Objects.equals(left.getParentCode(), parent == null ? null : parent.materialCode())
          || !Objects.equals(left.getMaterialCode(), right.materialCode())
          || !Objects.equals(left.getMaterialName(), right.materialName())
          || !Objects.equals(left.getMaterialSpec(), right.materialSpec())
          || !Objects.equals(left.getMaterialModel(), right.materialModel())
          || !Objects.equals(left.getDrawingNo(), right.drawingNo())
          || !Objects.equals(left.getShapeAttr(), right.shapeAttr())
          || !Objects.equals(left.getMainCategoryCode(), right.mainCategoryCode())
          || !Objects.equals(left.getSourceCategory(), right.sourceCategory())
          || !Objects.equals(left.getCostElementCode(), right.costElementCode())
          || !Objects.equals(left.getBomPurpose(), right.bomPurpose())
          || !Objects.equals(left.getBomVersion(), right.bomVersion())
          || !decimal(left.getQtyPerParent(), right.qtyPerParent())
          || !decimal(left.getQtyPerTop(), right.qtyPerTop())
          || !decimal(left.getParentBaseQty(), right.parentBaseQty())
          || !Objects.equals(left.getUnit(), right.unit())
          || !Objects.equals(left.getPath(), right.path())
          || !Objects.equals(left.getSortSeq(), right.sortSeq())
          || !Objects.equals(left.getNodeSourceType(), right.nodeSourceType())
          || !Objects.equals(left.getSourceElectronicNodeId(), right.sourceElectronicNodeId())
          || !Objects.equals(left.getMappingStatus(), right.mappingStatus())
          || !Objects.equals(left.getManualFlag(), expectedManual)
          || !Objects.equals(left.getRemark(), expectedRemark)) {
        return false;
      }
    }
    return true;
  }

  private CompositionResult result(
      ElectronicDrawingWorkContext context,
      QuoteBomSupplementVersion version,
      HybridBom hybrid,
      boolean idempotent) {
    return new CompositionResult(
        context.workflowId(), context.revision(), version.getId(), version.getVersionNo(),
        hybrid.compositionFingerprint(), hybrid.nodes().size(), hybrid.u9PurchaseLeafCount(),
        hybrid.electronicDrawingPurchaseLeafCount(), hybrid.quotationLeafCount(),
        hybrid.replacedElectronicDescendantCount(), hybrid.u9QueryCount(), idempotent,
        hybrid.nodes());
  }

  private static LocalDate effectiveDate(
      QuoteBomSupplementVersion version, ElectronicDrawingWorkContext context) {
    if (version.getEffectiveFrom() != null) return version.getEffectiveFrom();
    try {
      return YearMonth.parse(required(context.accountingMonth(), "核算月份")).atDay(1);
    } catch (RuntimeException exception) {
      throw invalid(SOURCE_VERSION_INVALID, "核算月份格式必须为 YYYY-MM");
    }
  }

  private static boolean decimal(BigDecimal left, BigDecimal right) {
    return left == null ? right == null : right != null && left.compareTo(right) == 0;
  }

  private static boolean same(String left, String right) {
    String a = text(left);
    String b = text(right);
    return a != null && b != null && a.equalsIgnoreCase(b);
  }

  private static String required(String value, String label) {
    String normalized = text(value);
    if (normalized == null) throw invalid(SOURCE_VERSION_INVALID, label + "不能为空");
    return normalized;
  }

  private static String normalize(String value) {
    return required(value, "料号").toUpperCase(Locale.ROOT);
  }

  private static String text(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static ElectronicDrawingHybridBomException invalid(String code, String message) {
    return new ElectronicDrawingHybridBomException(code, message);
  }

  public record CompositionResult(
      Long productTaskId,
      Integer taskVersion,
      Long sourceVersionId,
      Integer sourceVersionNo,
      String compositionFingerprint,
      int mixedNodeCount,
      int u9PurchaseLeafCount,
      int electronicDrawingPurchaseLeafCount,
      int quotationLeafCount,
      int replacedElectronicDescendantCount,
      int u9QueryCount,
      boolean idempotent,
      List<Node> nodes) {

    public CompositionResult {
      nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
  }
}
