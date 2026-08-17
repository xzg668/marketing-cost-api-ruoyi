package com.sanhua.marketingcost.service.collaboration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomCandidateRow;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomCandidateSearchResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomDraftRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomDraftResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomReferenceRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomWorkspaceResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationProductTaskMapper;
import com.sanhua.marketingcost.mapper.TechnicalBomCandidateMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.impl.BomEffectiveTreePruner;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** QCBP-10：本人任务内的相似 U9 BOM、独立树草稿和三步工作区。 */
@Service
public class TechnicalBomDraftApplicationService {
  private static final String SCOPE = "NON_BARE_FULL_BOM";
  private static final String VERSION_DRAFT = "DRAFT";
  private static final String SOURCE_U9_COPY = "U9_COPY";
  private static final String SOURCE_NEW = "NEW";
  private static final BigDecimal ONE = BigDecimal.ONE.setScale(8, RoundingMode.UNNECESSARY);
  private static final Set<String> EDITABLE_STATUSES = Set.of(
      "BOM_IN_PROGRESS", "TECH_VALIDATION_FAILED", "RETURNED_TO_TECH");

  private final QuoteCollaborationTaskRepository repository;
  private final CollaborationCurrentPrincipalProvider principalProvider;
  private final TechnicalBomCandidateMapper candidateMapper;
  private final BomRawHierarchyMapper rawHierarchyMapper;
  private final MaterialMasterRawMapper materialMasterMapper;
  private final QuoteBomSupplementVersionMapper versionMapper;
  private final QuoteBomSupplementDetailMapper detailMapper;
  private final QuoteCollaborationProductTaskMapper productTaskMapper;
  private final QuoteProductBomPreparationService preparationService;

  public TechnicalBomDraftApplicationService(
      QuoteCollaborationTaskRepository repository,
      CollaborationCurrentPrincipalProvider principalProvider,
      TechnicalBomCandidateMapper candidateMapper,
      BomRawHierarchyMapper rawHierarchyMapper,
      MaterialMasterRawMapper materialMasterMapper,
      QuoteBomSupplementVersionMapper versionMapper,
      QuoteBomSupplementDetailMapper detailMapper,
      QuoteCollaborationProductTaskMapper productTaskMapper,
      QuoteProductBomPreparationService preparationService) {
    this.repository = repository;
    this.principalProvider = principalProvider;
    this.candidateMapper = candidateMapper;
    this.rawHierarchyMapper = rawHierarchyMapper;
    this.materialMasterMapper = materialMasterMapper;
    this.versionMapper = versionMapper;
    this.detailMapper = detailMapper;
    this.productTaskMapper = productTaskMapper;
    this.preparationService = preparationService;
  }

  @Transactional(readOnly = true)
  public TechnicalBomWorkspaceResponse workspace(Long taskId) {
    OwnedTask owned = ownedTask(taskId, false);
    TechnicalBomDraftResponse draft = readDraft(owned.task());
    // 复制出来的结构即使字段齐全，也必须先让技术人员进入第 2 步检查/修改。
    // 只有电子图库已经真实回取过，重进页面时才直接恢复到第 3 步。
    int currentStep = draft == null ? 1
        : trim(owned.task().getElectronicBomFingerprint()) == null ? 2 : 3;
    String action = switch (currentStep) {
      case 1 -> "SELECT_REFERENCE";
      case 2 -> "SAVE_DRAFT";
      default -> "EXPORT_AND_VERIFY";
    };
    String label = switch (currentStep) {
      case 1 -> "复制参考BOM或全新建立";
      case 2 -> "保存并检查完整BOM";
      default -> "下载模板，录入电子图库后回取校验";
    };
    List<TechnicalBomWorkspaceResponse.Step> steps = List.of(
        step(1, "找参考BOM", currentStep),
        step(2, "编辑并导出完整BOM", currentStep),
        step(3, "电子图库录入并校验", currentStep));
    QuoteCollaborationProductTask task = owned.task();
    MaterialMasterRaw targetMaster = formalMaster(task, trim(task.getProductCode()));
    String targetNature = targetMaster == null || trim(targetMaster.getShapeAttr()) == null
        ? null : materialNature(targetMaster.getShapeAttr(), true).code;
    return new TechnicalBomWorkspaceResponse(task.getId(), task.getTaskVersion(),
        new TechnicalBomWorkspaceResponse.TargetProduct(
            task.getProductCode(), task.getTemporaryProductKey(), task.getProductName(),
            task.getProductSpec(), task.getProductModel(),
            targetMaster == null ? null : trim(targetMaster.getDrawingNo()), targetNature,
            task.getPriceOrgCode(), task.getMaterialOrgCode()), currentStep, action, label, steps, draft,
        task.getElectronicBomFingerprint(), task.getLastValidationStatus(),
        verificationIssues(task));
  }

  @Transactional(readOnly = true)
  public ElectronicBomTemplateSnapshot exportSnapshot(Long taskId) {
    OwnedTask owned = ownedTask(taskId, true);
    TechnicalBomDraftResponse draft = readDraft(owned.task());
    if (draft == null) throw invalid("请先建立目标BOM草稿");
    if (!draft.exportReady()) throw invalid("完整BOM仍有未补齐节点，不能导出电子图库模板");
    QuoteCollaborationProductTask task = owned.task();
    return new ElectronicBomTemplateSnapshot(task.getId(), task.getTaskVersion(),
        task.getProductCode(), task.getTemporaryProductKey(), task.getProductName(),
        task.getProductSpec(), task.getProductModel(), task.getMaterialOrgCode(),
        task.getPriceOrgCode(), draft);
  }

  @Transactional(readOnly = true)
  public TechnicalBomCandidateSearchResponse search(
      Long taskId, String keyword, String spec, String model) {
    OwnedTask owned = ownedTask(taskId, true);
    QuoteCollaborationProductTask task = owned.task();
    String resolvedSpec = firstText(spec, task.getProductSpec());
    String resolvedModel = firstText(model, task.getProductModel());
    String resolvedKeyword = firstText(keyword,
        resolvedSpec == null && resolvedModel == null ? task.getProductName() : null);
    List<TechnicalBomCandidateRow> rows = candidateMapper.selectCandidates(
        required(task.getPriceOrgCode(), "任务缺少U9报价组织"),
        materialOrganization(task), LocalDate.now(), resolvedKeyword,
        resolvedSpec, resolvedModel, null, null, 30);
    List<TechnicalBomCandidateSearchResponse.Candidate> candidates = rows.stream()
        .filter(row -> !Objects.equals(trim(row.getProductCode()), trim(task.getProductCode())))
        .map(row -> new TechnicalBomCandidateSearchResponse.Candidate(
            row.getProductCode(), row.getProductName(), row.getProductSpec(), row.getProductModel(),
            row.getBomPurpose(), row.getBomVersion(), value(row.getBomNodeCount()),
            value(row.getMatchScore()), matchReason(row, resolvedSpec, resolvedModel)))
        .toList();
    return new TechnicalBomCandidateSearchResponse(task.getPriceOrgCode(),
        materialOrganization(task), resolvedSpec, resolvedModel, candidates.size(), candidates);
  }

  @Transactional(readOnly = true)
  public TechnicalBomDraftResponse candidateTree(
      Long taskId, String productCode, String bomPurpose) {
    OwnedTask owned = ownedTask(taskId, true);
    List<BomRawHierarchy> rows = currentReferenceRows(
        owned.task(), required(productCode, "参考料号不能为空"), trim(bomPurpose));
    return response(null, owned.task().getTaskVersion(), SOURCE_U9_COPY, productCode,
        toDraftNodes(rows, owned.task(), false), List.of());
  }

  @Transactional
  public TechnicalBomDraftResponse copyReference(
      Long taskId, TechnicalBomReferenceRequest request) {
    OwnedTask owned = ownedTask(taskId, true);
    requireVersion(owned.task(), request == null ? null : request.expectedTaskVersion());
    String referenceCode = required(
        request == null ? null : request.referenceProductCode(), "请选择参考料号");
    List<BomRawHierarchy> referenceRows = currentReferenceRows(
        owned.task(), referenceCode, trim(request == null ? null : request.bomPurpose()));
    List<DraftNode> nodes = copyAsTarget(referenceRows, owned.task(), request);
    return persist(owned, nodes, SOURCE_U9_COPY, referenceCode);
  }

  @Transactional
  public TechnicalBomDraftResponse createNew(
      Long taskId, TechnicalBomReferenceRequest request) {
    OwnedTask owned = ownedTask(taskId, true);
    requireVersion(owned.task(), request == null ? null : request.expectedTaskVersion());
    QuoteCollaborationProductTask task = owned.task();
    String targetCode = targetMaterialCode(task);
    MaterialMasterRaw master = formalMaster(task, trim(task.getProductCode()));
    MaterialNature nature = copiedRootNature(request, master);
    DraftNode root = new DraftNode("ROOT", null, targetCode,
        firstText(request == null ? null : request.rootMaterialName(),
            firstText(task.getProductName(), master == null ? null : master.getMaterialName())),
        firstText(request == null ? null : request.rootMaterialSpec(),
            firstText(task.getProductSpec(), master == null ? null : master.getMaterialSpec())),
        firstText(request == null ? null : request.rootMaterialModel(),
            firstText(task.getProductModel(), master == null ? null : master.getMaterialModel())),
        firstText(request == null ? null : request.rootDrawingNo(),
            master == null ? null : trim(master.getDrawingNo())), nature,
        BigDecimal.ONE, master == null ? null : trim(master.getUnit()), 1, true,
        null, null, null, null);
    validateNodeFields(List.of(root), task);
    return persist(owned, List.of(root), SOURCE_NEW, null);
  }

  @Transactional
  public TechnicalBomDraftResponse save(
      Long taskId, TechnicalBomDraftRequest request) {
    OwnedTask owned = ownedTask(taskId, true);
    requireVersion(owned.task(), request == null ? null : request.expectedTaskVersion());
    if (owned.task().getSupplementVersionId() == null) {
      throw invalid("请先复制参考BOM或全新建立草稿");
    }
    List<QuoteBomSupplementDetail> existing = details(owned.task().getSupplementVersionId());
    Map<String, QuoteBomSupplementDetail> existingByNode = existing.stream()
        .collect(Collectors.toMap(row -> "N" + row.getLineNo(), Function.identity()));
    List<DraftNode> nodes = fromRequest(request, existingByNode, owned.task());
    validateStructure(nodes);
    validateNodeFields(nodes, owned.task());
    return persist(owned, nodes, sourceMode(existing), referenceProduct(existing));
  }

  private TechnicalBomDraftResponse persist(
      OwnedTask owned, List<DraftNode> inputNodes, String sourceMode, String referenceCode) {
    QuoteCollaborationProductTask task = owned.task();
    NormalizedTree normalized = normalize(inputNodes);
    List<TechnicalBomDraftResponse.Issue> issues = completionIssues(normalized.nodes());
    Long preparationId = ensurePreparation(task);
    QuoteBomSupplementVersion version = loadOrCreateVersion(
        task, owned.principal(), owned.ownerLink(), preparationId);
    replaceDetails(version, task, owned.ownerLink(), normalized.nodes());
    int updated = productTaskMapper.attachBomDraft(task.getId(), task.getTaskVersion(),
        preparationId, version.getId(), owned.principal().userId(), task.getBusinessUnitType(),
        task.getApplicableOrgCode(), owned.principal().userId(), owned.principal().userName());
    if (updated != 1) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_VERSION_CONFLICT, "任务版本已变化，请刷新后重试");
    }
    QuoteCollaborationProductTask refreshed = repository.findMineById(
        task.getId(), owned.principal().userId(), currentBusinessUnit()).orElseThrow(
            () -> invalid("保存后无法读取技术任务"));
    List<DraftNode> savedNodes = fromStored(details(version.getId()));
    return response(version.getId(), refreshed.getTaskVersion(), sourceMode, referenceCode,
        savedNodes, completionIssues(savedNodes));
  }

  private TechnicalBomDraftResponse readDraft(QuoteCollaborationProductTask task) {
    if (task.getSupplementVersionId() == null) return null;
    QuoteBomSupplementVersion version = versionMapper.selectById(task.getSupplementVersionId());
    if (version == null || !Objects.equals(targetCode(task), version.getQuoteProductCode())) {
      throw invalid("产品任务关联的BOM草稿不存在或目标产品不匹配");
    }
    List<QuoteBomSupplementDetail> rows = details(version.getId());
    List<DraftNode> nodes = fromStored(rows);
    return response(version.getId(), task.getTaskVersion(), sourceMode(rows),
        referenceProduct(rows), nodes, completionIssues(nodes));
  }

  private QuoteBomSupplementVersion loadOrCreateVersion(
      QuoteCollaborationProductTask task,
      CollaborationPrincipal principal,
      QuoteCollaborationQuoteLink ownerLink,
      Long preparationId) {
    QuoteBomSupplementVersion version = task.getSupplementVersionId() == null
        ? null : versionMapper.selectById(task.getSupplementVersionId());
    LocalDateTime now = LocalDateTime.now();
    if (version == null) {
      version = new QuoteBomSupplementVersion();
      version.setPreparationId(preparationId);
      // 新产品协作任务仅保存 version.id；不占用旧 lp_bom_supplement_task 的 task_id 命名空间。
      version.setTaskId(null);
      version.setTaskNo(task.getProductTaskNo());
      version.setOaNo(ownerLink.getOaNo());
      version.setOaFormItemId(ownerLink.getOaFormItemId());
      version.setQuoteProductCode(targetCode(task));
      version.setProductType("NON_BARE");
      version.setSupplementScope(SCOPE);
      version.setBomSource("TECH_SUPPLEMENT");
      version.setVersionNo(1);
      version.setVersionStatus(VERSION_DRAFT);
      version.setActiveFlag(1);
      version.setPeriodMonth(task.getAccountingMonth());
      version.setEffectiveFrom(LocalDate.now());
      version.setCreatedAt(now);
      version.setUpdatedAt(now);
      if (versionMapper.insert(version) != 1) throw invalid("BOM草稿版本创建失败");
    } else {
      if (!Objects.equals(targetCode(task), version.getQuoteProductCode())) {
        throw invalid("BOM草稿目标产品与当前任务不一致");
      }
      version.setVersionStatus(VERSION_DRAFT);
      version.setSubmittedBy(null);
      version.setSubmittedByName(null);
      version.setSubmittedAt(null);
      version.setUpdatedAt(now);
      if (versionMapper.updateById(version) != 1) throw invalid("BOM草稿版本更新失败");
    }
    return version;
  }

  private void replaceDetails(
      QuoteBomSupplementVersion version,
      QuoteCollaborationProductTask task,
      QuoteCollaborationQuoteLink ownerLink,
      List<DraftNode> nodes) {
    detailMapper.delete(Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
        .eq(QuoteBomSupplementDetail::getSupplementVersionId, version.getId()));
    LocalDateTime now = LocalDateTime.now();
    for (int index = 0; index < nodes.size(); index++) {
      DraftNode node = nodes.get(index);
      QuoteBomSupplementDetail detail = new QuoteBomSupplementDetail();
      detail.setSupplementVersionId(version.getId());
      detail.setPreparationId(version.getPreparationId());
      detail.setTaskId(null);
      detail.setOaNo(ownerLink.getOaNo());
      detail.setOaFormItemId(ownerLink.getOaFormItemId());
      detail.setQuoteProductCode(targetCode(task));
      detail.setSupplementScope(SCOPE);
      detail.setLineNo(index + 1);
      detail.setLevel(node.level());
      detail.setParentCode(node.parentMaterialCode());
      detail.setMaterialCode(node.internalMaterialCode());
      detail.setMaterialName(node.materialName());
      detail.setMaterialSpec(node.materialSpec());
      detail.setMaterialModel(node.materialModel());
      detail.setDrawingNo(node.drawingNo());
      detail.setShapeAttr(node.nature().storageValue);
      detail.setQtyPerParent(node.level() == 0 ? BigDecimal.ONE : node.quantity());
      detail.setQtyPerTop(node.quantityToTop());
      detail.setParentBaseQty(BigDecimal.ONE);
      detail.setUnit(node.unit());
      detail.setPath(node.path());
      detail.setSortSeq(node.sortSeq());
      detail.setSourceRawHierarchyId(node.sourceRawHierarchyId());
      detail.setSourceU9BomId(node.sourceU9BomId());
      detail.setManualFlag(node.changed() ? 1 : 0);
      detail.setRemark(node.changed() ? "CHANGED" : "SOURCE");
      detail.setCreatedAt(now);
      detail.setUpdatedAt(now);
      if (detailMapper.insert(detail) != 1) throw invalid("BOM草稿明细保存失败");
    }
  }

  private List<DraftNode> copyAsTarget(
      List<BomRawHierarchy> rows,
      QuoteCollaborationProductTask task,
      TechnicalBomReferenceRequest request) {
    Set<String> materialCodes = rows.stream().map(BomRawHierarchy::getMaterialCode)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (trim(task.getProductCode()) != null) materialCodes.add(trim(task.getProductCode()));
    Map<String, MaterialMasterRaw> masters = masters(task, materialCodes);
    MaterialMasterRaw targetMaster = masters.get(trim(task.getProductCode()));
    Map<String, String> nodeIdByPath = new LinkedHashMap<>();
    List<DraftNode> result = new ArrayList<>();
    String referenceRootPath = normalizePath(rows.get(0).getPath());
    int sequence = 1;
    for (BomRawHierarchy row : rows) {
      String sourcePath = normalizePath(row.getPath());
      String nodeId = "R" + sequence++;
      String parentId = row.getLevel() == null || row.getLevel() == 0
          ? null : nodeIdByPath.get(parentPath(sourcePath));
      nodeIdByPath.put(sourcePath, nodeId);
      boolean root = parentId == null;
      MaterialMasterRaw master = masters.get(trim(row.getMaterialCode()));
      String code = root ? targetMaterialCode(task) : trim(row.getMaterialCode());
      result.add(new DraftNode(nodeId, parentId, code,
          root ? firstText(request == null ? null : request.rootMaterialName(),
              firstText(task.getProductName(), targetMaster == null ? null : targetMaster.getMaterialName()))
              : firstText(row.getMaterialName(), master == null ? null : master.getMaterialName()),
          root ? firstText(request == null ? null : request.rootMaterialSpec(),
              firstText(task.getProductSpec(), targetMaster == null ? null : targetMaster.getMaterialSpec()))
              : firstText(row.getMaterialSpec(), master == null ? null : master.getMaterialSpec()),
          root ? firstText(request == null ? null : request.rootMaterialModel(),
              firstText(task.getProductModel(), targetMaster == null ? null : targetMaster.getMaterialModel()))
              : master == null ? null : trim(master.getMaterialModel()),
          root ? firstText(request == null ? null : request.rootDrawingNo(),
              targetMaster == null ? null : trim(targetMaster.getDrawingNo()))
              : master == null ? null : trim(master.getDrawingNo()),
          root ? copiedRootNature(request, targetMaster)
              : copiedNodeNature(row, master),
          root ? BigDecimal.ONE : positiveOrOne(row.getQtyPerParent()),
          master == null ? null : trim(master.getUnit()),
          row.getSortSeq(), root, row.getId(), row.getSourceU9RowId(), null, null));
    }
    if (result.isEmpty() || referenceRootPath == null) throw invalid("参考BOM没有有效根节点");
    validateStructure(result);
    validateNodeFields(result, task);
    return result;
  }

  private List<DraftNode> toDraftNodes(
      List<BomRawHierarchy> rows, QuoteCollaborationProductTask task, boolean targetRoot) {
    Map<String, MaterialMasterRaw> masters = masters(task, rows.stream()
        .map(BomRawHierarchy::getMaterialCode).collect(Collectors.toSet()));
    Map<String, String> idByPath = new HashMap<>();
    List<DraftNode> result = new ArrayList<>();
    int index = 1;
    for (BomRawHierarchy row : rows) {
      String nodeId = "R" + index++;
      String path = normalizePath(row.getPath());
      String parentId = value(row.getLevel()) == 0 ? null : idByPath.get(parentPath(path));
      idByPath.put(path, nodeId);
      MaterialMasterRaw master = masters.get(trim(row.getMaterialCode()));
      result.add(new DraftNode(nodeId, parentId,
          targetRoot && parentId == null ? targetMaterialCode(task) : trim(row.getMaterialCode()),
          firstText(row.getMaterialName(), master == null ? null : master.getMaterialName()),
          firstText(row.getMaterialSpec(), master == null ? null : master.getMaterialSpec()),
          master == null ? null : trim(master.getMaterialModel()),
          master == null ? null : trim(master.getDrawingNo()),
          copiedNodeNature(row, master),
          value(row.getLevel()) == 0 ? BigDecimal.ONE : positiveOrOne(row.getQtyPerParent()),
          master == null ? null : trim(master.getUnit()), row.getSortSeq(), false,
          row.getId(), row.getSourceU9RowId(), null, null));
    }
    return normalize(result).nodes();
  }

  private List<DraftNode> fromRequest(
      TechnicalBomDraftRequest request,
      Map<String, QuoteBomSupplementDetail> existing,
      QuoteCollaborationProductTask task) {
    if (request == null || request.nodes().isEmpty()) throw invalid("完整BOM不能为空");
    List<DraftNode> nodes = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (TechnicalBomDraftRequest.Node row : request.nodes()) {
      String nodeId = required(row == null ? null : row.nodeId(), "节点ID不能为空");
      if (!ids.add(nodeId)) throw invalid("存在重复节点ID：" + nodeId);
      QuoteBomSupplementDetail old = existing.get(nodeId);
      String requestedCode = trim(row.materialCode());
      String internalCode = requestedCode != null ? requestedCode
          : old != null && temporary(old.getMaterialCode()) ? old.getMaterialCode()
          : temporaryCode(task.getId());
      nodes.add(new DraftNode(nodeId, trim(row.parentNodeId()), internalCode,
          trim(row.materialName()), trim(row.materialSpec()), trim(row.materialModel()),
          trim(row.drawingNo()), materialNature(row.materialNature(), true),
          row.quantity(), trim(row.unit()), row.sortSeq(), row.changed(),
          old == null ? null : old.getSourceRawHierarchyId(),
          old == null ? null : old.getSourceU9BomId(), null, null));
    }
    return nodes;
  }

  private List<DraftNode> fromStored(List<QuoteBomSupplementDetail> rows) {
    Map<String, String> nodeByPath = new HashMap<>();
    List<DraftNode> nodes = new ArrayList<>();
    for (QuoteBomSupplementDetail row : rows) {
      String nodeId = "N" + row.getLineNo();
      String path = normalizePath(row.getPath());
      String parentId = value(row.getLevel()) == 0 ? null : nodeByPath.get(parentPath(path));
      nodeByPath.put(path, nodeId);
      nodes.add(new DraftNode(nodeId, parentId, row.getMaterialCode(), row.getMaterialName(),
          row.getMaterialSpec(), row.getMaterialModel(), row.getDrawingNo(),
          materialNature(firstText(row.getShapeAttr(), row.getSourceCategory()), false),
          value(row.getLevel()) == 0 ? BigDecimal.ONE : positiveOrOne(row.getQtyPerParent()),
          row.getUnit(), row.getSortSeq(), value(row.getManualFlag()) == 1,
          row.getSourceRawHierarchyId(), row.getSourceU9BomId(), row.getPath(),
          row.getQtyPerTop()));
    }
    return nodes;
  }

  private NormalizedTree normalize(List<DraftNode> input) {
    validateStructure(input);
    Map<String, DraftNode> byId = input.stream()
        .collect(Collectors.toMap(DraftNode::nodeId, Function.identity()));
    Map<String, List<DraftNode>> children = input.stream()
        .filter(node -> node.parentNodeId() != null)
        .collect(Collectors.groupingBy(DraftNode::parentNodeId, LinkedHashMap::new,
            Collectors.toCollection(ArrayList::new)));
    children.values().forEach(list -> list.sort(Comparator
        .comparing((DraftNode node) -> node.sortSeq() == null ? Integer.MAX_VALUE : node.sortSeq())
        .thenComparing(DraftNode::nodeId)));
    DraftNode root = input.stream().filter(node -> node.parentNodeId() == null).findFirst().orElseThrow();
    List<DraftNode> normalized = new ArrayList<>();
    normalizeNode(root, null, 0, BigDecimal.ONE, children, normalized, new HashSet<>());
    if (normalized.size() != byId.size()) throw invalid("BOM存在孤儿节点或循环关系");
    return new NormalizedTree(List.copyOf(normalized));
  }

  private void normalizeNode(
      DraftNode node,
      DraftNode parent,
      int level,
      BigDecimal parentToTop,
      Map<String, List<DraftNode>> children,
      List<DraftNode> output,
      Set<String> visiting) {
    if (!visiting.add(node.nodeId())) throw invalid("BOM存在循环关系：" + node.nodeId());
    BigDecimal quantity = level == 0 ? BigDecimal.ONE : positiveOrOne(node.quantity());
    BigDecimal toTop = level == 0 ? BigDecimal.ONE : parentToTop.multiply(quantity);
    String path = (parent == null ? "/" : parent.path()) + node.internalMaterialCode() + "/";
    DraftNode normalized = new DraftNode(node.nodeId(), node.parentNodeId(),
        node.internalMaterialCode(), node.materialName(), node.materialSpec(), node.materialModel(),
        node.drawingNo(), node.nature(), quantity, node.unit(), node.sortSeq(), node.changed(),
        node.sourceRawHierarchyId(), node.sourceU9BomId(), path, toTop,
        parent == null ? node.internalMaterialCode() : parent.internalMaterialCode(), level);
    output.add(normalized);
    for (DraftNode child : children.getOrDefault(node.nodeId(), List.of())) {
      normalizeNode(child, normalized, level + 1, toTop, children, output, visiting);
    }
    visiting.remove(node.nodeId());
  }

  private void validateStructure(List<DraftNode> nodes) {
    if (nodes == null || nodes.isEmpty()) throw invalid("完整BOM不能为空");
    Map<String, DraftNode> byId = new LinkedHashMap<>();
    for (DraftNode node : nodes) {
      if (node == null || trim(node.nodeId()) == null) throw invalid("节点ID不能为空");
      if (byId.put(node.nodeId(), node) != null) throw invalid("存在重复节点ID：" + node.nodeId());
    }
    List<DraftNode> roots = nodes.stream().filter(node -> node.parentNodeId() == null).toList();
    if (roots.size() != 1) throw invalid("完整BOM必须且只能有一个根节点");
    Map<String, Set<String>> childKeys = new HashMap<>();
    for (DraftNode node : nodes) {
      if (node.parentNodeId() != null && !byId.containsKey(node.parentNodeId())) {
        throw invalid("存在孤儿节点：" + node.nodeId());
      }
      if (Objects.equals(node.nodeId(), node.parentNodeId())) {
        throw invalid("节点不能把自己作为上级：" + node.nodeId());
      }
      String key = duplicateKey(node);
      if (!childKeys.computeIfAbsent(String.valueOf(node.parentNodeId()), ignored -> new HashSet<>())
          .add(key)) {
        throw invalid("同一上级下存在重复物料：" + displayMaterial(node));
      }
    }
    detectCycle(roots.get(0).nodeId(), byId, new HashSet<>(), new HashSet<>());
  }

  private void detectCycle(
      String id, Map<String, DraftNode> byId, Set<String> visiting, Set<String> visited) {
    if (!visiting.add(id)) throw invalid("BOM存在循环关系：" + id);
    if (visited.add(id)) {
      for (DraftNode node : byId.values()) {
        if (Objects.equals(id, node.parentNodeId())) {
          detectCycle(node.nodeId(), byId, visiting, visited);
        }
      }
    }
    visiting.remove(id);
  }

  private void validateNodeFields(List<DraftNode> nodes, QuoteCollaborationProductTask task) {
    Set<String> formalCodes = nodes.stream().map(DraftNode::internalMaterialCode)
        .filter(code -> !temporary(code)).collect(Collectors.toCollection(LinkedHashSet::new));
    Map<String, MaterialMasterRaw> masters = masters(task, formalCodes);
    Set<String> missingFormal = new LinkedHashSet<>(formalCodes);
    missingFormal.removeAll(masters.keySet());
    if (!missingFormal.isEmpty()) {
      throw invalid("以下正式料号未在当前U9组织找到：" + String.join("、", missingFormal));
    }
    Map<String, Long> childCount = nodes.stream().filter(node -> node.parentNodeId() != null)
        .collect(Collectors.groupingBy(DraftNode::parentNodeId, Collectors.counting()));
    for (DraftNode node : nodes) {
      if (temporary(node.internalMaterialCode())) {
        if (trim(node.materialName()) == null || trim(node.materialSpec()) == null
            || trim(node.materialModel()) == null || trim(node.drawingNo()) == null) {
          throw invalid("新品无料号时，名称、规格、型号/图号和物料性质必须填写：" + node.nodeId());
        }
      }
      if (node.parentNodeId() != null && (node.quantity() == null
          || node.quantity().compareTo(BigDecimal.ZERO) <= 0)) {
        throw invalid("用量必须大于0：" + displayMaterial(node));
      }
      if (node.nature() == MaterialNature.PURCHASE
          && childCount.getOrDefault(node.nodeId(), 0L) > 0) {
        throw invalid("采购件不能继续挂下级：" + displayMaterial(node));
      }
    }
    String expectedRoot = targetMaterialCode(task);
    DraftNode root = nodes.stream().filter(node -> node.parentNodeId() == null).findFirst().orElseThrow();
    if (!Objects.equals(expectedRoot, root.internalMaterialCode())) {
      throw invalid("根节点必须是当前目标产品，不能替换为参考产品");
    }
  }

  private List<TechnicalBomDraftResponse.Issue> completionIssues(List<DraftNode> nodes) {
    Map<String, Long> childCount = nodes.stream().filter(node -> node.parentNodeId() != null)
        .collect(Collectors.groupingBy(DraftNode::parentNodeId, Collectors.counting()));
    List<TechnicalBomDraftResponse.Issue> issues = new ArrayList<>();
    for (DraftNode node : nodes) {
      if (node.nature().requiresChildren && childCount.getOrDefault(node.nodeId(), 0L) == 0) {
        issues.add(new TechnicalBomDraftResponse.Issue(node.nodeId(), "CHILD_REQUIRED",
            node.nature().label + "必须继续补下级：" + displayMaterial(node)));
      }
    }
    return List.copyOf(issues);
  }

  private List<BomRawHierarchy> currentReferenceRows(
      QuoteCollaborationProductTask task, String productCode, String bomPurpose) {
    String resolvedPurpose = bomPurpose;
    if (resolvedPurpose == null) {
      List<TechnicalBomCandidateRow> candidates = candidateMapper.selectCandidates(
          task.getPriceOrgCode(), materialOrganization(task), LocalDate.now(), null, null, null,
          productCode, null, 2);
      if (candidates.isEmpty()) throw invalid("当前组织没有找到该料号的有效U9 BOM");
      if (candidates.size() > 1) throw invalid("该料号存在多个有效BOM用途，请明确选择参考版本");
      resolvedPurpose = trim(candidates.get(0).getBomPurpose());
    }
    List<BomRawHierarchy> rows = rawHierarchyMapper.selectList(
        Wrappers.<BomRawHierarchy>lambdaQuery()
            .eq(BomRawHierarchy::getPriceOrgCode, task.getPriceOrgCode())
            .eq(BomRawHierarchy::getTopProductCode, productCode)
            .eq(BomRawHierarchy::getSourceType, "U9")
            .eq(resolvedPurpose != null, BomRawHierarchy::getBomPurpose, resolvedPurpose)
            .le(BomRawHierarchy::getEffectiveFrom, LocalDate.now())
            .and(wrapper -> wrapper.isNull(BomRawHierarchy::getEffectiveTo).or()
                .ge(BomRawHierarchy::getEffectiveTo, LocalDate.now()))
            .orderByAsc(BomRawHierarchy::getLevel)
            .orderByAsc(BomRawHierarchy::getPath)
            .orderByAsc(BomRawHierarchy::getSortSeq)
            .orderByAsc(BomRawHierarchy::getId));
    rows = BomEffectiveTreePruner.prune(rows, productCode);
    if (rows.size() < 2) throw invalid("当前组织没有找到该料号的完整有效U9 BOM");
    return rows;
  }

  private OwnedTask ownedTask(Long taskId, boolean requireEditable) {
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuoteCollaborationProductTask task = repository.findMineById(
        requireId(taskId), principal.userId(), currentBusinessUnit()).orElseThrow(
            () -> new CollaborationDomainException(
                CollaborationDomainErrorCode.TASK_NOT_FOUND, "技术任务不存在"));
    if (value(task.getNeedBom()) != 1) throw invalid("当前任务不是BOM补录任务");
    if (requireEditable && (!EDITABLE_STATUSES.contains(task.getTaskStatus())
        || !Objects.equals(task.getCurrentAssigneeUserId(), principal.userId()))) {
      throw invalid("当前任务未开始、已提交或不再由你处理");
    }
    QuoteCollaborationQuoteLink owner = repository.findLinksByProductTask(
            task.getId(), new CollaborationScope(task.getBusinessUnitType(), task.getApplicableOrgCode()))
        .stream().filter(link -> "OWNER".equals(link.getLinkType()))
        .max(Comparator.comparing(QuoteCollaborationQuoteLink::getId))
        .orElseThrow(() -> invalid("产品任务缺少报价来源关联"));
    return new OwnedTask(task, principal, owner);
  }

  private Long ensurePreparation(QuoteCollaborationProductTask task) {
    if (task.getPreparationId() != null) return task.getPreparationId();
    QuoteCollaborationQuoteLink owner = repository.findLinksByProductTask(
            task.getId(), new CollaborationScope(task.getBusinessUnitType(), task.getApplicableOrgCode()))
        .stream().filter(link -> "OWNER".equals(link.getLinkType())).findFirst()
        .orElseThrow(() -> invalid("产品任务缺少报价来源关联"));
    QuoteProductBomPreparationPreview preview = preparationService.prepareByOaFormItem(
        owner.getOaFormItemId(), LocalDate.now());
    if (preview == null || preview.preparationRecordId() == null) {
      throw invalid("无法建立当前报价产品的BOM准备记录");
    }
    return preview.preparationRecordId();
  }

  private Map<String, MaterialMasterRaw> masters(
      QuoteCollaborationProductTask task, Set<String> codes) {
    Set<String> normalized = codes == null ? new LinkedHashSet<>() : codes.stream()
        .map(TechnicalBomDraftApplicationService::trim).filter(Objects::nonNull)
        .filter(code -> !temporary(code)).collect(Collectors.toCollection(LinkedHashSet::new));
    if (normalized.isEmpty()) return Map.of();
    return materialMasterMapper.selectByLatestBatchAndCodes(
            normalized, null, materialOrganization(task)).stream()
        .filter(row -> trim(row.getMaterialCode()) != null)
        .collect(Collectors.toMap(row -> trim(row.getMaterialCode()), Function.identity(),
            (first, ignored) -> first, LinkedHashMap::new));
  }

  private MaterialMasterRaw formalMaster(QuoteCollaborationProductTask task, String code) {
    if (code == null) return null;
    return masters(task, Set.of(code)).get(code);
  }

  private List<QuoteBomSupplementDetail> details(Long versionId) {
    return detailMapper.selectList(Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
        .eq(QuoteBomSupplementDetail::getSupplementVersionId, versionId)
        .orderByAsc(QuoteBomSupplementDetail::getLineNo));
  }

  private List<TechnicalBomWorkspaceResponse.VerificationIssue> verificationIssues(
      QuoteCollaborationProductTask task) {
    return repository.findGaps(task.getId(), new CollaborationScope(
            task.getBusinessUnitType(), task.getApplicableOrgCode())).stream()
        .filter(gap -> "BOM".equals(gap.getGapCategory()))
        .filter(gap -> !"OBSOLETE".equals(gap.getGapStatus()))
        .map(gap -> new TechnicalBomWorkspaceResponse.VerificationIssue(
            gap.getId(), gap.getBomNodeKey(), gap.getBomPath(),
            gap.getReasonCode(), gap.getReasonMessage()))
        .toList();
  }

  private TechnicalBomDraftResponse response(
      Long versionId,
      Integer taskVersion,
      String sourceMode,
      String referenceCode,
      List<DraftNode> input,
      List<TechnicalBomDraftResponse.Issue> issues) {
    NormalizedTree normalized = normalize(input);
    Map<String, List<DraftNode>> children = normalized.nodes().stream()
        .filter(node -> node.parentNodeId() != null)
        .collect(Collectors.groupingBy(DraftNode::parentNodeId, LinkedHashMap::new,
            Collectors.toList()));
    List<TechnicalBomDraftResponse.Node> flat = normalized.nodes().stream()
        .map(node -> responseNode(node, List.of())).toList();
    List<TechnicalBomDraftResponse.Node> tree = normalized.nodes().stream()
        .filter(node -> node.parentNodeId() == null)
        .map(node -> treeNode(node, children)).toList();
    return new TechnicalBomDraftResponse(versionId, taskVersion, sourceMode, referenceCode,
        issues == null || issues.isEmpty(), issues, tree, flat);
  }

  private TechnicalBomDraftResponse.Node treeNode(
      DraftNode node, Map<String, List<DraftNode>> children) {
    return responseNode(node, children.getOrDefault(node.nodeId(), List.of()).stream()
        .map(child -> treeNode(child, children)).toList());
  }

  private TechnicalBomDraftResponse.Node responseNode(
      DraftNode node, List<TechnicalBomDraftResponse.Node> children) {
    return new TechnicalBomDraftResponse.Node(node.nodeId(), node.parentNodeId(), node.level(),
        temporary(node.internalMaterialCode()) ? null : node.internalMaterialCode(),
        temporary(node.internalMaterialCode()), node.materialName(), node.materialSpec(),
        node.materialModel(), node.drawingNo(), node.nature().code, node.quantity(),
        node.quantityToTop(), node.unit(), node.sortSeq(), node.changed(), children);
  }

  private String sourceMode(List<QuoteBomSupplementDetail> rows) {
    return rows.stream().anyMatch(row -> row.getSourceRawHierarchyId() != null)
        ? SOURCE_U9_COPY : SOURCE_NEW;
  }

  private String referenceProduct(List<QuoteBomSupplementDetail> rows) {
    Long sourceId = rows.stream().map(QuoteBomSupplementDetail::getSourceRawHierarchyId)
        .filter(Objects::nonNull).findFirst().orElse(null);
    if (sourceId == null) return null;
    BomRawHierarchy source = rawHierarchyMapper.selectById(sourceId);
    return source == null ? null : trim(source.getTopProductCode());
  }

  private static TechnicalBomWorkspaceResponse.Step step(int step, String title, int current) {
    return new TechnicalBomWorkspaceResponse.Step(step, title,
        step < current ? "COMPLETED" : step == current ? "CURRENT" : "PENDING");
  }

  private static String matchReason(
      TechnicalBomCandidateRow row, String spec, String model) {
    boolean sameSpec = spec != null && spec.equals(trim(row.getProductSpec()));
    boolean sameModel = model != null && model.equals(trim(row.getProductModel()));
    if (sameSpec && sameModel) return "规格、型号相同";
    if (sameModel) return "型号相同";
    if (sameSpec) return "规格相同";
    return "相似料号";
  }

  private static MaterialNature materialNature(String value, boolean required) {
    String normalized = trim(value);
    if (normalized == null) {
      if (required) throw invalid("请选择物料性质");
      return MaterialNature.MANUFACTURE;
    }
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (upper.contains("PURCHASE") || normalized.contains("采购")) return MaterialNature.PURCHASE;
    if (upper.contains("OUTSOURCE") || normalized.contains("委外")) return MaterialNature.OUTSOURCE;
    if (upper.contains("VIRTUAL") || upper.contains("PACKAGE")
        || normalized.contains("虚拟") || normalized.contains("包装")) return MaterialNature.VIRTUAL;
    if (upper.contains("MANUFACTURE") || normalized.contains("制造")
        || normalized.contains("自制")
        || normalized.contains("半成品")) return MaterialNature.MANUFACTURE;
    throw invalid("物料性质仅支持采购件、制造件、委外件、虚拟件（包装）");
  }

  /**
   * EasyData/U9 的物料性质落在 shape_attr；production_category/source_category 是产品分类，
   * 不能拿来判断采购、制造、委外或虚拟。已有料号优先采用目标物料主档，缺失时才要求技术选择。
   */
  private static MaterialNature copiedRootNature(
      TechnicalBomReferenceRequest request, MaterialMasterRaw targetMaster) {
    String requested = request == null ? null : trim(request.rootMaterialNature());
    if (requested != null) return materialNature(requested, true);
    String masterNature = targetMaster == null ? null : trim(targetMaster.getShapeAttr());
    return materialNature(masterNature, true);
  }

  /** EasyData 层级行优先，其次物料主档；极少数旧数据缺失时按是否叶子安全兜底。 */
  private static MaterialNature copiedNodeNature(
      BomRawHierarchy row, MaterialMasterRaw master) {
    String hierarchyNature = row == null ? null : trim(row.getShapeAttr());
    if (hierarchyNature != null) return materialNature(hierarchyNature, true);
    String masterNature = master == null ? null : trim(master.getShapeAttr());
    if (masterNature != null) return materialNature(masterNature, true);
    return row != null && Integer.valueOf(1).equals(row.getIsLeaf())
        ? MaterialNature.PURCHASE : MaterialNature.MANUFACTURE;
  }

  private static String materialOrganization(QuoteCollaborationProductTask task) {
    String value = trim(task.getMaterialOrgCode());
    if (value != null) return MaterialOrganization.normalize(value);
    return MaterialOrganization.fromPriceOrgCode(task.getPriceOrgCode()).getCode();
  }

  private static String targetCode(QuoteCollaborationProductTask task) {
    return required(firstText(task.getProductCode(), task.getTemporaryProductKey()),
        "当前产品缺少料号或稳定临时键");
  }

  private static String targetMaterialCode(QuoteCollaborationProductTask task) {
    String formal = trim(task.getProductCode());
    return formal == null ? "TMP-ROOT-" + task.getId() : formal;
  }

  private static String temporaryCode(Long taskId) {
    return "TMP-" + taskId + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }

  private static boolean temporary(String code) {
    return trim(code) != null && trim(code).startsWith("TMP-");
  }

  private static String duplicateKey(DraftNode node) {
    if (!temporary(node.internalMaterialCode())) return node.internalMaterialCode();
    return String.join("|", value(node.materialName()), value(node.materialSpec()),
        value(node.materialModel()), value(node.drawingNo())).toUpperCase(Locale.ROOT);
  }

  private static String displayMaterial(DraftNode node) {
    return firstText(node.materialName(), temporary(node.internalMaterialCode())
        ? "新品无料号" : node.internalMaterialCode());
  }

  private static String normalizePath(String path) {
    String value = trim(path);
    if (value == null) return null;
    return value.endsWith("/") ? value : value + "/";
  }

  private static String parentPath(String path) {
    String value = normalizePath(path);
    if (value == null) return null;
    int index = value.lastIndexOf('/', value.length() - 2);
    return index <= 0 ? null : value.substring(0, index + 1);
  }

  private static BigDecimal positiveOrOne(BigDecimal value) {
    return value == null || value.compareTo(BigDecimal.ZERO) <= 0 ? BigDecimal.ONE : value;
  }

  private static void requireVersion(QuoteCollaborationProductTask task, Integer expected) {
    if (expected == null || !expected.equals(task.getTaskVersion())) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_VERSION_CONFLICT, "任务版本已变化，请刷新后重试");
    }
  }

  private static Long requireId(Long value) {
    if (value == null || value <= 0) throw new CollaborationDomainException(
        CollaborationDomainErrorCode.TASK_NOT_FOUND, "技术任务不存在");
    return value;
  }

  private static String currentBusinessUnit() {
    return CollaborationScope.requireBusinessUnit(BusinessUnitContext.getCurrentBusinessUnitType());
  }

  private static String required(String value, String message) {
    String result = trim(value);
    if (result == null) throw invalid(message);
    return result;
  }

  private static int value(Integer value) { return value == null ? 0 : value; }
  private static String value(String value) { return value == null ? "" : value; }
  private static String trim(String value) { return StringUtils.hasText(value) ? value.trim() : null; }
  private static String firstText(String first, String second) {
    String value = trim(first);
    return value == null ? trim(second) : value;
  }

  private static CollaborationDomainException invalid(String message) {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.STATE_TRANSITION_INVALID, message);
  }

  private enum MaterialNature {
    PURCHASE("PURCHASE", "采购件", "采购件", false),
    MANUFACTURE("MANUFACTURE", "制造件", "制造件", true),
    OUTSOURCE("OUTSOURCE", "委外件", "委外件", true),
    VIRTUAL("VIRTUAL_PACKAGE", "虚拟件（包装）", "虚拟件（包装）", true);

    private final String code;
    private final String label;
    private final String storageValue;
    private final boolean requiresChildren;

    MaterialNature(
        String code, String label, String storageValue, boolean requiresChildren) {
      this.code = code;
      this.label = label;
      this.storageValue = storageValue;
      this.requiresChildren = requiresChildren;
    }
  }

  private record OwnedTask(
      QuoteCollaborationProductTask task,
      CollaborationPrincipal principal,
      QuoteCollaborationQuoteLink ownerLink) {}

  public record ElectronicBomTemplateSnapshot(
      Long taskId,
      Integer taskVersion,
      String productCode,
      String temporaryProductKey,
      String productName,
      String productSpec,
      String productModel,
      String materialOrganizationCode,
      String priceOrganizationCode,
      TechnicalBomDraftResponse draft) {}

  private record NormalizedTree(List<DraftNode> nodes) {}

  private record DraftNode(
      String nodeId,
      String parentNodeId,
      String internalMaterialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String drawingNo,
      MaterialNature nature,
      BigDecimal quantity,
      String unit,
      Integer sortSeq,
      boolean changed,
      Long sourceRawHierarchyId,
      Long sourceU9BomId,
      String path,
      BigDecimal quantityToTop,
      String parentMaterialCode,
      Integer level) {

    DraftNode(
        String nodeId,
        String parentNodeId,
        String internalMaterialCode,
        String materialName,
        String materialSpec,
        String materialModel,
        String drawingNo,
        MaterialNature nature,
        BigDecimal quantity,
        String unit,
        Integer sortSeq,
        boolean changed,
        Long sourceRawHierarchyId,
        Long sourceU9BomId,
        String path,
        BigDecimal quantityToTop) {
      this(nodeId, parentNodeId, internalMaterialCode, materialName, materialSpec,
          materialModel, drawingNo, nature, quantity, unit, sortSeq, changed,
          sourceRawHierarchyId, sourceU9BomId, path, quantityToTop, null, null);
    }
  }
}
