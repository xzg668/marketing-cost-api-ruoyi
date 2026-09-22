package com.sanhua.marketingcost.service.electronicdrawing;

import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingMaterialResolutionException.COMMAND_INVALID;
import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingMaterialResolutionException.MATERIAL_NOT_FOUND;
import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingMaterialResolutionException.SOURCE_NODE_INVALID;
import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingMaterialResolutionException.SOURCE_VERSION_INVALID;
import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingMaterialResolutionException.TASK_NOT_FOUND;
import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingMaterialResolutionException.TASK_VERSION_CONFLICT;

import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialResolutionRequest;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialResolutionResponse;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialSearchResponse;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

/**
 * 在产品任务冻结的物料组织内解析电子图库料号。
 *
 * <p>自动匹配只认图号、规格、型号的严格唯一值；财务报价员的搜索结果只在主动输入后返回。
 */
@Service
public class ElectronicDrawingMaterialResolutionService {
  private static final String BOM_SOURCE = "ELECTRONIC_DRAWING_EXCEL";
  private static final String VERSION_DRAFT = "DRAFT";
  private static final int DEFAULT_SEARCH_LIMIT = 30;
  private static final int MAX_SEARCH_LIMIT = 100;

  private final ElectronicDrawingWorkflowContextPort contextPort;
  private final QuoteBomSupplementVersionMapper versionMapper;
  private final ElectronicDrawingSourceNodeRepository sourceNodeRepository;
  private final ElectronicDrawingMaterialMatcher matcher;
  private final MaterialMasterRawMapper materialMapper;
  private final ElectronicDrawingActorProvider actorProvider;

  public ElectronicDrawingMaterialResolutionService(
      ElectronicDrawingWorkflowContextPort contextPort,
      QuoteBomSupplementVersionMapper versionMapper,
      ElectronicDrawingSourceNodeRepository sourceNodeRepository,
      ElectronicDrawingMaterialMatcher matcher,
      MaterialMasterRawMapper materialMapper,
      ElectronicDrawingActorProvider actorProvider) {
    this.contextPort = contextPort;
    this.versionMapper = versionMapper;
    this.sourceNodeRepository = sourceNodeRepository;
    this.matcher = matcher;
    this.materialMapper = materialMapper;
    this.actorProvider = actorProvider;
  }

  /** 后台编排器在电子图库源版本入库后调用；不借用当前登录人的身份。 */
  @Transactional
  public ElectronicDrawingMaterialResolutionResponse autoMatch(
      Long workflowId, String businessUnitType, String applicableOrgCode, String accountingMonth) {
    ElectronicDrawingWorkContext context = contextPort.load(
        workflowId, businessUnitType, applicableOrgCode, accountingMonth);
    Target target = target(context, true);
    List<ElectronicDrawingSourceNode> pending = sourceNodeRepository
        .findPendingByVersionId(target.version().getId());
    if (pending.isEmpty()) return response(context, target.version(), target.nodes());

    Map<String, ElectronicDrawingSourceNode> nodeBySequence = pending.stream()
        .collect(Collectors.toMap(ElectronicDrawingSourceNode::getSourceSequence,
            Function.identity(), (first, ignored) -> first, LinkedHashMap::new));
    List<ElectronicDrawingExcelParseResult.SourceNode> matchInputs = pending.stream()
        .map(ElectronicDrawingMaterialResolutionService::matchInput)
        .toList();
    List<ElectronicDrawingMaterialMatcher.Match> matches =
        matcher.match(context.materialOrgCode(), matchInputs);
    LocalDateTime now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    boolean changed = false;
    for (ElectronicDrawingMaterialMatcher.Match match : matches) {
      ElectronicDrawingSourceNode node = nodeBySequence.get(match.sourceSequence());
      if (node == null) throw error(SOURCE_NODE_INVALID, "自动匹配结果未对应电子图库源节点");
      String targetStatus = switch (match.status()) {
        case AUTO_MATCHED -> ElectronicDrawingSourceNode.MATCH_AUTO;
        case AMBIGUOUS -> ElectronicDrawingSourceNode.MATCH_AMBIGUOUS;
        case UNMATCHED -> ElectronicDrawingSourceNode.MATCH_UNMATCHED;
        default -> throw error(SOURCE_NODE_INVALID, "自动匹配产生了不允许的状态");
      };
      String materialCode = match.status() == ElectronicDrawingMaterialMatcher.Status.AUTO_MATCHED
          ? required(match.selectedMaterialCode(), "自动匹配料号") : null;
      if (sameResolution(node, targetStatus, materialCode)) continue;
      boolean updated = sourceNodeRepository.updateResolution(
          node.getId(), node.getMatchStatus(), targetStatus, materialCode,
          materialCode == null ? null : "SYSTEM", materialCode == null ? null : now);
      if (!updated) throw conflict();
      changed = true;
    }
    if (changed) touch(context, target.version().getId(), 0L, "SYSTEM", now);
    ElectronicDrawingWorkContext refreshed = changed ? reload(context) : context;
    return response(refreshed, target.version(),
        sourceNodeRepository.findByVersionId(target.version().getId()));
  }

  @Transactional(readOnly = true)
  public ElectronicDrawingMaterialResolutionResponse state(Long workflowId, String accountingMonth) {
    ElectronicDrawingWorkContext context = userContext(workflowId, accountingMonth);
    Target target = target(context, false);
    return response(context, target.version(), target.nodes());
  }

  @Transactional(readOnly = true)
  public ElectronicDrawingMaterialSearchResponse search(
      Long workflowId,
      Long expectedSourceVersionId,
      String searchType,
      String keyword,
      Integer limit, String accountingMonth) {
    ElectronicDrawingWorkContext context = userContext(workflowId, accountingMonth);
    Target target = target(context, true);
    if (!Objects.equals(target.version().getId(), expectedSourceVersionId)) {
      throw error(SOURCE_VERSION_INVALID, "电子图库源版本已变化，请刷新页面后重新搜索");
    }
    SearchType type = SearchType.parse(searchType);
    String query = text(keyword);
    int safeLimit = limit == null ? DEFAULT_SEARCH_LIMIT : Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT));
    List<ElectronicDrawingMaterialSearchResponse.Option> options = query == null ? List.of()
        : materialMapper.selectElectronicDrawingOptions(
                type.name(), query, null, context.materialOrgCode(), safeLimit).stream()
            .map(ElectronicDrawingMaterialResolutionService::searchOption)
            .toList();
    return new ElectronicDrawingMaterialSearchResponse(
        context.workflowId(), target.version().getId(), type.name(), query, options);
  }

  @Transactional
  public ElectronicDrawingMaterialResolutionResponse apply(
      Long workflowId, ElectronicDrawingMaterialResolutionRequest request, String accountingMonth) {
    ElectronicDrawingWorkContext context = userContext(workflowId, accountingMonth);
    ValidRequest valid = validate(request);
    if (!Objects.equals(context.revision(), valid.expectedTaskVersion())) throw conflict();
    Target target = target(context, true);
    if (!Objects.equals(target.version().getId(), valid.expectedSourceVersionId())) {
      throw error(SOURCE_VERSION_INVALID, "电子图库源版本已变化，请刷新页面后重新选择");
    }

    // 全部料号已确认后允许重试下级 BOM 检查，不重写已保存的选择和确认人。
    if (valid.selections().isEmpty()) {
      if (target.nodes().stream().anyMatch(node -> isPending(node.getMatchStatus()))) {
        throw error(COMMAND_INVALID, "仍有待确认物料，请至少选择一个 U9 料号");
      }
      return response(context, target.version(), target.nodes());
    }

    Map<Long, ElectronicDrawingSourceNode> nodes = target.nodes().stream()
        .collect(Collectors.toMap(ElectronicDrawingSourceNode::getId, Function.identity()));
    Set<Long> selectedNodeIds = new LinkedHashSet<>();
    Set<String> requestedCodes = new LinkedHashSet<>();
    for (ValidSelection selection : valid.selections()) {
      if (!selectedNodeIds.add(selection.sourceNodeId())) {
        throw error(COMMAND_INVALID, "同一个电子图库物料不能在一次保存中重复选择");
      }
      ElectronicDrawingSourceNode node = nodes.get(selection.sourceNodeId());
      if (node == null || !isPending(node.getMatchStatus())) {
        throw error(SOURCE_NODE_INVALID, "待确认物料已变化，请刷新页面后重新选择");
      }
      requestedCodes.add(selection.normalizedMaterialCode());
    }

    List<MaterialMasterRaw> materialRows = materialMapper.selectByLatestBatchAndCodes(
        valid.selections().stream().map(ValidSelection::materialCode).toList(),
        null, context.materialOrgCode());
    Map<String, List<MaterialMasterRaw>> materialGroups = materialRows.stream()
        .filter(row -> text(row.getMaterialCode()) != null)
        .collect(Collectors.groupingBy(
            row -> normalize(row.getMaterialCode()), LinkedHashMap::new, Collectors.toList()));
    if (!materialGroups.keySet().equals(requestedCodes)
        || materialGroups.values().stream().anyMatch(rows -> rows.size() != 1)) {
      throw error(MATERIAL_NOT_FOUND, "所选 U9 料号不是当前物料组织中的唯一正常料品，请重新搜索");
    }

    ElectronicDrawingActor actor = actorProvider.current();
    String resolvedBy = resolvedBy(actor);
    LocalDateTime now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    for (ValidSelection selection : valid.selections()) {
      ElectronicDrawingSourceNode node = nodes.get(selection.sourceNodeId());
      String actualCode = materialGroups.get(selection.normalizedMaterialCode())
          .getFirst().getMaterialCode().trim();
      if (!sourceNodeRepository.updateResolution(
          node.getId(), node.getMatchStatus(), ElectronicDrawingSourceNode.MATCH_MANUAL,
          actualCode, resolvedBy, now)) {
        throw conflict();
      }
    }
    touch(context, target.version().getId(), actor.userId(), actor.userName(), now);
    ElectronicDrawingWorkContext refreshed = reload(context);
    return response(refreshed, target.version(),
        sourceNodeRepository.findByVersionId(target.version().getId()));
  }

  private ElectronicDrawingWorkContext userContext(Long workflowId, String accountingMonth) {
    actorProvider.current();
    try {
      return contextPort.loadForCurrentBusinessUnit(workflowId, accountingMonth);
    } catch (IllegalArgumentException exception) {
      throw error(TASK_NOT_FOUND, "电子图库对应的报价产品不存在");
    }
  }

  private Target target(ElectronicDrawingWorkContext context, boolean requireDraft) {
    if (context == null
        || context.workflowId() == null
        || !context.active()
        || !context.bomRequired()
        || context.preparationId() == null
        || context.sourceVersionId() == null
        || context.revision() == null
        || text(context.materialOrgCode()) == null) {
      throw error(SOURCE_VERSION_INVALID, "当前报价产品没有可用的电子图库源版本");
    }
    QuoteBomSupplementVersion version = versionMapper.selectById(context.sourceVersionId());
    boolean valid = version != null
        && Objects.equals(version.getId(), context.sourceVersionId())
        && Objects.equals(version.getPreparationId(), context.preparationId())
        && same(version.getTaskNo(), context.taskNo())
        && same(version.getQuoteProductCode(), context.quoteProductCode())
        && same(version.getPeriodMonth(), context.accountingMonth())
        && same(version.getMaterialOrgCode(), context.materialOrgCode())
        && BOM_SOURCE.equals(version.getBomSource())
        && Objects.equals(version.getActiveFlag(), 1)
        && (!requireDraft || VERSION_DRAFT.equals(version.getVersionStatus()));
    if (!valid) throw error(SOURCE_VERSION_INVALID, "当前电子图库源版本与产品任务不一致或不可编辑");
    List<ElectronicDrawingSourceNode> nodes = sourceNodeRepository.findByVersionId(version.getId());
    if (nodes.isEmpty()) throw error(SOURCE_VERSION_INVALID, "当前电子图库源版本没有明细节点");
    return new Target(version, nodes);
  }

  private void touch(
      ElectronicDrawingWorkContext context,
      Long sourceVersionId,
      Long updatedBy,
      String updatedByName,
      LocalDateTime updatedAt) {
    try {
      contextPort.touch(context, sourceVersionId, updatedBy,
          required(updatedByName, "操作人名称"), updatedAt);
    } catch (ElectronicDrawingWorkflowRetryException exception) {
      throw conflict();
    }
  }

  private ElectronicDrawingWorkContext reload(ElectronicDrawingWorkContext context) {
    return contextPort.load(context.workflowId(), context.businessUnitType(),
        context.applicableOrgCode(), context.accountingMonth());
  }

  private ElectronicDrawingMaterialResolutionResponse response(
      ElectronicDrawingWorkContext context,
      QuoteBomSupplementVersion version,
      List<ElectronicDrawingSourceNode> nodes) {
    Set<String> resolvedCodes = nodes.stream()
        .map(ElectronicDrawingSourceNode::getResolvedMaterialCode)
        .map(ElectronicDrawingMaterialResolutionService::text)
        .filter(Objects::nonNull)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    Map<String, MaterialMasterRaw> materials = resolvedCodes.isEmpty() ? Map.of()
        : materialMapper.selectByLatestBatchAndCodes(
                resolvedCodes, null, context.materialOrgCode()).stream()
            .filter(row -> text(row.getMaterialCode()) != null)
            .collect(Collectors.toMap(row -> normalize(row.getMaterialCode()), Function.identity(),
                (first, ignored) -> first, LinkedHashMap::new));
    int auto = 0;
    int manual = 0;
    int unmatched = 0;
    int ambiguous = 0;
    List<ElectronicDrawingMaterialResolutionResponse.Item> items = new ArrayList<>();
    for (ElectronicDrawingSourceNode node : nodes) {
      switch (node.getMatchStatus()) {
        case ElectronicDrawingSourceNode.MATCH_AUTO -> auto++;
        case ElectronicDrawingSourceNode.MATCH_MANUAL -> manual++;
        case ElectronicDrawingSourceNode.MATCH_AMBIGUOUS -> ambiguous++;
        default -> unmatched++;
      }
      String materialCode = normalize(node.getResolvedMaterialCode());
      MaterialMasterRaw material = materialCode == null ? null : materials.get(materialCode);
      items.add(item(node, material));
    }
    return new ElectronicDrawingMaterialResolutionResponse(
        context.workflowId(), context.revision(), version.getId(), version.getVersionNo(),
        version.getVersionStatus(), version.getElectronicDrawingNo(), context.materialOrgCode(),
        nodes.size(), auto, manual, unmatched, ambiguous, unmatched + ambiguous == 0, items,
        context.accountingMonth(), context.workflowStage(), version.getCompositionFingerprint() != null,
        context.published());
  }

  private static ElectronicDrawingMaterialResolutionResponse.Item item(
      ElectronicDrawingSourceNode node, MaterialMasterRaw material) {
    return new ElectronicDrawingMaterialResolutionResponse.Item(
        node.getId(), node.getSourceRowNo(), node.getSourceSequence(),
        node.getParentSourceSequence(), node.getDrawingCode(), node.getSourceName(), node.getQty(),
        node.getMaterial(), node.getMatchStatus(), isPending(node.getMatchStatus()),
        node.getResolvedMaterialCode(), material == null ? null : material.getMaterialName(),
        material == null ? null : material.getMaterialSpec(),
        material == null ? null : material.getMaterialModel(),
        material == null ? null : material.getDrawingNo(),
        material == null ? null : material.getShapeAttr(),
        material == null ? null : material.getUnit(), node.getResolvedBy(), node.getResolvedAt());
  }

  private static ElectronicDrawingMaterialSearchResponse.Option searchOption(MaterialMasterRaw row) {
    return new ElectronicDrawingMaterialSearchResponse.Option(
        text(row.getMaterialCode()), text(row.getMaterialName()), text(row.getMaterialSpec()),
        text(row.getMaterialModel()), text(row.getDrawingNo()), text(row.getShapeAttr()),
        text(row.getUnit()), text(row.getMainCategoryCode()), text(row.getMainCategoryName()));
  }

  private static ElectronicDrawingExcelParseResult.SourceNode matchInput(
      ElectronicDrawingSourceNode node) {
    return new ElectronicDrawingExcelParseResult.SourceNode(
        node.getSourceSequence(), node.getParentSourceSequence(), 0, node.getDrawingCode(),
        node.getSourceName(), node.getMaterial(), node.getImportanceClass(), node.getHsfRiskClass(),
        node.getQty(), node.getReferenceWeight(), node.getReferenceWeightUnit(),
        node.getSourceRemark(), node.getSourceRowNo());
  }

  private static ValidRequest validate(ElectronicDrawingMaterialResolutionRequest request) {
    if (request == null || request.expectedTaskVersion() == null
        || request.expectedTaskVersion() < 0 || request.expectedSourceVersionId() == null
        || request.expectedSourceVersionId() <= 0) {
      throw error(COMMAND_INVALID, "任务版本和电子图库源版本不能为空");
    }
    List<ValidSelection> selections = request.selections().stream().map(selection -> {
      if (selection == null || selection.sourceNodeId() == null || selection.sourceNodeId() <= 0) {
        throw error(COMMAND_INVALID, "电子图库源节点ID不能为空");
      }
      String code = required(selection.materialCode(), "U9料号");
      return new ValidSelection(selection.sourceNodeId(), code, normalize(code));
    }).toList();
    return new ValidRequest(
        request.expectedTaskVersion(), request.expectedSourceVersionId(), selections);
  }

  private static String resolvedBy(ElectronicDrawingActor actor) {
    if (actor == null || actor.userId() == null || actor.userId() <= 0) {
      throw error(COMMAND_INVALID, "当前报价人员身份无效");
    }
    return "USER:" + actor.userId() + ":" + required(actor.userName(), "操作人名称");
  }

  private static boolean sameResolution(
      ElectronicDrawingSourceNode node, String status, String materialCode) {
    return Objects.equals(node.getMatchStatus(), status)
        && Objects.equals(text(node.getResolvedMaterialCode()), text(materialCode));
  }

  private static boolean isPending(String status) {
    return ElectronicDrawingSourceNode.MATCH_UNMATCHED.equals(status)
        || ElectronicDrawingSourceNode.MATCH_AMBIGUOUS.equals(status);
  }

  private static boolean same(String left, String right) {
    String a = text(left);
    String b = text(right);
    return a != null && b != null && a.equalsIgnoreCase(b);
  }

  private static String required(String value, String label) {
    String normalized = text(value);
    if (normalized == null) throw error(COMMAND_INVALID, label + "不能为空");
    return normalized;
  }

  private static String normalize(String value) {
    String normalized = text(value);
    return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
  }

  private static String text(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static ElectronicDrawingMaterialResolutionException conflict() {
    return error(TASK_VERSION_CONFLICT, "产品任务或待确认物料已变化，请刷新页面后重试");
  }

  private static ElectronicDrawingMaterialResolutionException error(String code, String message) {
    return new ElectronicDrawingMaterialResolutionException(code, message);
  }

  public enum SearchType {
    DRAWING_NO,
    MATERIAL_CODE,
    MATERIAL_NAME;

    static SearchType parse(String value) {
      String normalized = normalize(value);
      if (normalized == null) throw error(COMMAND_INVALID, "搜索类型不能为空");
      try {
        return valueOf(normalized);
      } catch (IllegalArgumentException exception) {
        throw error(COMMAND_INVALID, "搜索类型仅支持图号、U9料号或物料名称");
      }
    }
  }

  private record Target(
      QuoteBomSupplementVersion version, List<ElectronicDrawingSourceNode> nodes) {}

  private record ValidRequest(
      Integer expectedTaskVersion,
      Long expectedSourceVersionId,
      List<ValidSelection> selections) {}

  private record ValidSelection(
      Long sourceNodeId, String materialCode, String normalizedMaterialCode) {}
}
