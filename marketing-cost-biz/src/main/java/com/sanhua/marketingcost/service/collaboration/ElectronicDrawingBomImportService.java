package com.sanhua.marketingcost.service.collaboration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.collaboration.ElectronicDrawingBomImportResponse;
import com.sanhua.marketingcost.dto.collaboration.ElectronicDrawingBomImportResponse.Issue;
import com.sanhua.marketingcost.dto.collaboration.ElectronicDrawingBomImportResponse.MappingItem;
import com.sanhua.marketingcost.dto.collaboration.ElectronicDrawingMaterialMappingRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomDraftResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalBomWorkspaceResponse;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.service.collaboration.ElectronicDrawingMaterialMatcher.Match;
import com.sanhua.marketingcost.service.collaboration.ElectronicDrawingMaterialMatcher.Option;
import com.sanhua.marketingcost.service.collaboration.ElectronicDrawingMaterialMatcher.Status;
import com.sanhua.marketingcost.service.collaboration.TechnicalBomDraftApplicationService.ImportedNode;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
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
import org.springframework.util.StringUtils;

/** 电子图库正式 Excel 的上传、图号匹配、草稿恢复和人工映射编排。 */
@Service
public class ElectronicDrawingBomImportService {
  private static final int MAX_FILE_BYTES = 10 * 1024 * 1024;
  private static final String SOURCE_MODE = "ELECTRONIC_DRAWING_EXCEL";
  private final ElectronicDrawingExcelParser parser;
  private final ElectronicDrawingBomCandidateFactory candidateFactory;
  private final ElectronicDrawingMaterialMatcher matcher;
  private final TechnicalBomDraftApplicationService draftService;
  private final QuoteBomSupplementDetailMapper detailMapper;
  private final MaterialMasterRawMapper materialMapper;
  private final ElectronicDrawingSourceMetadataCodec metadata =
      new ElectronicDrawingSourceMetadataCodec();

  public ElectronicDrawingBomImportService(
      ElectronicDrawingExcelParser parser,
      ElectronicDrawingBomCandidateFactory candidateFactory,
      ElectronicDrawingMaterialMatcher matcher,
      TechnicalBomDraftApplicationService draftService,
      QuoteBomSupplementDetailMapper detailMapper,
      MaterialMasterRawMapper materialMapper) {
    this.parser = parser;
    this.candidateFactory = candidateFactory;
    this.matcher = matcher;
    this.draftService = draftService;
    this.detailMapper = detailMapper;
    this.materialMapper = materialMapper;
  }

  @Transactional
  public ElectronicDrawingBomImportResponse importFile(
      Long taskId, Integer expectedVersion, String fileName, byte[] fileBytes) {
    TechnicalBomWorkspaceResponse workspace = draftService.workspace(taskId);
    requireVersion(workspace.taskVersion(), expectedVersion);
    if (fileBytes == null || fileBytes.length == 0) {
      return parseFailure(workspace.taskVersion(), fileName, null, null,
          List.of(new ElectronicDrawingExcelParseResult.Issue(
              "FILE_EMPTY", null, null, "上传文件为空")));
    }
    if (fileBytes.length > MAX_FILE_BYTES) {
      return parseFailure(workspace.taskVersion(), fileName, null, null,
          List.of(new ElectronicDrawingExcelParseResult.Issue(
              "FILE_TOO_LARGE", null, null, "电子图库 Excel 不能超过 10 MB")));
    }
    ElectronicDrawingExcelParseResult parsed = parser.parse(
        fileName, new ByteArrayInputStream(fileBytes));
    String sha256 = sha256(fileBytes);
    if (!parsed.valid()) {
      return parseFailure(workspace.taskVersion(), fileName, sha256,
          parsed.sourceSheetName(), parsed.issues());
    }

    TechnicalBomWorkspaceResponse.TargetProduct target = workspace.target();
    ElectronicDrawingBomCandidate candidate = candidateFactory.create(parsed,
        new ElectronicDrawingBomCandidate.RootProduct(
            target.productCode(), target.temporaryProductKey(), target.productName(),
            target.productSpec(), target.productModel(), target.productDrawingNo(),
            firstText(target.materialNature(), "MANUFACTURE"), "件"));
    List<Match> matches = matcher.match(target.materialOrganizationCode(), parsed.nodes());
    Map<String, Match> matchBySequence = matches.stream().collect(Collectors.toMap(
        Match::sourceSequence, Function.identity(), (first, ignored) -> first, LinkedHashMap::new));
    Map<String, ElectronicDrawingExcelParseResult.SourceNode> sourceBySequence = parsed.nodes().stream()
        .collect(Collectors.toMap(ElectronicDrawingExcelParseResult.SourceNode::sourceSequence,
            Function.identity(), (first, ignored) -> first, LinkedHashMap::new));
    Set<String> parentSequences = parsed.nodes().stream()
        .map(ElectronicDrawingExcelParseResult.SourceNode::parentSourceSequence)
        .filter(Objects::nonNull).collect(Collectors.toSet());

    List<ImportedNode> imported = new ArrayList<>();
    ElectronicDrawingBomCandidate.Node root = candidate.nodes().getFirst();
    imported.add(new ImportedNode(root.nodeKey(), null, root.materialCode(), root.materialName(),
        root.materialSpec(), root.materialModel(), root.drawingCode(), root.materialNature(),
        BigDecimal.ONE, firstText(root.unit(), "件"), 1,
        metadata.root(fileName, sha256, parsed.sourceSheetName())));
    int sort = 2;
    for (ElectronicDrawingBomCandidate.Node node : candidate.nodes().subList(1, candidate.nodes().size())) {
      ElectronicDrawingExcelParseResult.SourceNode source = sourceBySequence.get(node.sourceSequence());
      Match match = matchBySequence.get(node.sourceSequence());
      Option selected = selected(match);
      boolean hasChildren = parentSequences.contains(node.sourceSequence());
      imported.add(new ImportedNode(node.nodeKey(), node.parentNodeKey(),
          selected == null ? null : selected.materialCode(),
          selected == null ? node.materialName() : selected.materialName(),
          selected == null ? firstText(node.sourceMaterial(), "电子图库未提供材料") : selected.materialSpec(),
          selected == null ? node.drawingCode() : selected.materialModel(),
          node.drawingCode(), selected == null
              ? (hasChildren ? "MANUFACTURE" : "PURCHASE")
              : materialNature(selected.materialNature(), hasChildren),
          node.quantity(), selected == null ? "件" : firstText(selected.unit(), "件"), sort++,
          metadata.node(source, match == null ? Status.UNMATCHED : match.status())));
    }
    draftService.replaceFromElectronicDrawingExcel(taskId, expectedVersion, imported);
    return current(taskId);
  }

  @Transactional(readOnly = true)
  public ElectronicDrawingBomImportResponse current(Long taskId) {
    TechnicalBomWorkspaceResponse workspace = draftService.workspace(taskId);
    TechnicalBomDraftResponse draft = workspace.draft();
    if (draft == null || !SOURCE_MODE.equals(draft.sourceMode())) {
      return new ElectronicDrawingBomImportResponse(false, false, false,
          "当前任务还没有上传电子图库 Excel", workspace.taskVersion(),
          draft == null ? null : draft.supplementVersionId(), null, null, null,
          0, 0, 0, 0, 0, draft, List.of(), List.of());
    }
    List<QuoteBomSupplementDetail> details = details(draft.supplementVersionId());
    if (details.isEmpty()) {
      return new ElectronicDrawingBomImportResponse(false, false, false,
          "电子图库 Excel 草稿明细不存在", workspace.taskVersion(), draft.supplementVersionId(),
          null, null, null, 0, 0, 0, 0, 0, draft, List.of(), List.of(
              new Issue("PERSISTENCE", "DETAIL_EMPTY", null, null, null, "草稿明细不存在")));
    }
    ElectronicDrawingSourceMetadataCodec.RootMetadata rootMetadata =
        metadata.decodeRoot(details.getFirst().getRemark());
    Map<String, TechnicalBomDraftResponse.Node> draftById = draft.flatNodes().stream()
        .collect(Collectors.toMap(TechnicalBomDraftResponse.Node::nodeId, Function.identity()));
    List<SourceState> sourceStates = new ArrayList<>();
    for (QuoteBomSupplementDetail detail : details) {
      String nodeId = "N" + detail.getLineNo();
      if (detail.getLevel() == null || detail.getLevel() == 0) continue;
      ElectronicDrawingSourceMetadataCodec.NodeMetadata source = metadata.decodeNode(detail.getRemark());
      if (source == null) continue;
      TechnicalBomDraftResponse.Node draftNode = draftById.get(nodeId);
      sourceStates.add(new SourceState(nodeId, detail, draftNode, source));
    }
    List<ElectronicDrawingExcelParseResult.SourceNode> sourceNodes = sourceStates.stream()
        .map(state -> new ElectronicDrawingExcelParseResult.SourceNode(
            state.source().sourceSequence(), parentSequence(state.source().sourceSequence()),
            level(state.source().sourceSequence()), state.detail().getDrawingNo(),
            state.detail().getMaterialName(), state.source().sourceMaterial(),
            state.source().importanceClass(), state.source().hsfRiskClass(),
            state.detail().getQtyPerParent(), state.source().referenceWeight(),
            state.source().sourceRemark(), state.source().sourceRowNumber() == null
                ? 0 : state.source().sourceRowNumber()))
        .toList();
    Map<String, Match> currentMatches = matcher.match(
        workspace.target().materialOrganizationCode(), sourceNodes).stream()
        .collect(Collectors.toMap(Match::sourceSequence, Function.identity(),
            (first, ignored) -> first, LinkedHashMap::new));

    List<MappingItem> mappings = new ArrayList<>();
    List<Issue> issues = new ArrayList<>();
    int auto = 0;
    int confirmed = 0;
    int unmatched = 0;
    int ambiguous = 0;
    for (SourceState state : sourceStates) {
      Match rematched = currentMatches.get(state.source().sourceSequence());
      boolean temporary = state.draftNode() == null || state.draftNode().temporaryMaterial();
      String status;
      String selectedCode = temporary ? null : state.draftNode().materialCode();
      if (!temporary) {
        status = Status.AUTO_MATCHED.name().equals(state.source().initialMatchStatus())
            ? Status.AUTO_MATCHED.name() : Status.CONFIRMED.name();
      } else {
        status = rematched == null ? Status.UNMATCHED.name() : rematched.status().name();
      }
      switch (Status.valueOf(status)) {
        case AUTO_MATCHED -> auto++;
        case CONFIRMED -> confirmed++;
        case UNMATCHED -> {
          unmatched++;
          issues.add(new Issue("MAPPING", "MATERIAL_UNMATCHED", state.nodeId(),
              state.source().sourceRowNumber(), state.source().sourceSequence(),
              "图号 " + state.detail().getDrawingNo() + " 在当前 U9 组织没有唯一料号，请搜索并选择"));
        }
        case AMBIGUOUS -> {
          ambiguous++;
          issues.add(new Issue("MAPPING", "MATERIAL_AMBIGUOUS", state.nodeId(),
              state.source().sourceRowNumber(), state.source().sourceSequence(),
              "图号 " + state.detail().getDrawingNo() + " 对应多个当前 U9 料号，请明确选择"));
        }
      }
      mappings.add(new MappingItem(state.nodeId(), state.source().sourceSequence(),
          state.source().sourceRowNumber(), state.detail().getDrawingNo(),
          state.detail().getMaterialName(), state.source().sourceMaterial(),
          state.source().referenceWeight(), state.source().importanceClass(),
          state.source().hsfRiskClass(), state.source().sourceRemark(), status, selectedCode,
          rematched == null ? List.of() : rematched.options()));
    }
    TechnicalBomDraftResponse.Node root = draft.flatNodes().isEmpty() ? null : draft.flatNodes().getFirst();
    if (root == null || root.temporaryMaterial()) {
      issues.add(new Issue("MAPPING", "TARGET_PRODUCT_MAPPING_REQUIRED",
          root == null ? null : root.nodeId(), null, null, "当前报价产品还没有正式料号，不能形成最终计价BOM"));
    }
    for (TechnicalBomDraftResponse.Issue issue : draft.issues()) {
      issues.add(new Issue("STRUCTURE", issue.code(), issue.nodeId(), null, null, issue.message()));
    }
    boolean mappingComplete = unmatched == 0 && ambiguous == 0
        && root != null && !root.temporaryMaterial();
    boolean structureReady = mappingComplete && draft.exportReady();
    String message = structureReady ? "电子图库 Excel 已解析，料号匹配和 BOM 结构检查均通过"
        : "电子图库 Excel 已保存，请处理料号匹配或 BOM 结构问题";
    return new ElectronicDrawingBomImportResponse(true, mappingComplete, structureReady,
        message, workspace.taskVersion(), draft.supplementVersionId(),
        rootMetadata == null ? null : rootMetadata.fileName(),
        rootMetadata == null ? null : rootMetadata.sha256(),
        rootMetadata == null ? null : rootMetadata.sheetName(), mappings.size(), auto, confirmed,
        unmatched, ambiguous, draft, mappings, issues);
  }

  @Transactional
  public ElectronicDrawingBomImportResponse applyMappings(
      Long taskId, ElectronicDrawingMaterialMappingRequest request) {
    if (request == null || request.selections().isEmpty()) {
      throw invalid("请选择至少一个需要确认的节点料号");
    }
    TechnicalBomWorkspaceResponse workspace = draftService.workspace(taskId);
    requireVersion(workspace.taskVersion(), request.expectedVersion());
    TechnicalBomDraftResponse draft = workspace.draft();
    if (draft == null || !SOURCE_MODE.equals(draft.sourceMode())) {
      throw invalid("当前任务没有可确认的电子图库 Excel 草稿");
    }
    Map<String, String> selections = new LinkedHashMap<>();
    for (ElectronicDrawingMaterialMappingRequest.Selection selection : request.selections()) {
      String nodeId = text(selection == null ? null : selection.nodeId());
      String code = text(selection == null ? null : selection.materialCode());
      if (nodeId == null || code == null) throw invalid("节点和正式料号不能为空");
      if (selections.put(nodeId, code) != null) throw invalid("同一个节点不能重复选择料号：" + nodeId);
    }
    Set<String> validNodeIds = draft.flatNodes().stream().map(TechnicalBomDraftResponse.Node::nodeId)
        .collect(Collectors.toSet());
    if (!validNodeIds.containsAll(selections.keySet())) throw invalid("选择中包含不属于当前草稿的节点");
    if (!draft.flatNodes().isEmpty() && selections.containsKey(draft.flatNodes().getFirst().nodeId())) {
      throw invalid("电子图库导入不能替换当前报价产品根节点");
    }
    Map<String, MaterialMasterRaw> masters = materialMapper.selectByLatestBatchAndCodes(
            new LinkedHashSet<>(selections.values()), null,
            workspace.target().materialOrganizationCode()).stream()
        .filter(row -> text(row.getMaterialCode()) != null)
        .collect(Collectors.toMap(row -> text(row.getMaterialCode()), Function.identity(),
            (first, ignored) -> first, LinkedHashMap::new));
    Set<String> missing = new LinkedHashSet<>(selections.values());
    missing.removeAll(masters.keySet());
    if (!missing.isEmpty()) throw invalid("所选料号不在当前 U9 组织：" + String.join("、", missing));

    Map<String, QuoteBomSupplementDetail> detailByNode = details(draft.supplementVersionId()).stream()
        .collect(Collectors.toMap(row -> "N" + row.getLineNo(), Function.identity()));
    Set<String> parents = draft.flatNodes().stream().map(TechnicalBomDraftResponse.Node::parentNodeId)
        .filter(Objects::nonNull).collect(Collectors.toSet());
    List<ImportedNode> imported = new ArrayList<>();
    for (TechnicalBomDraftResponse.Node node : draft.flatNodes()) {
      MaterialMasterRaw selected = masters.get(selections.get(node.nodeId()));
      QuoteBomSupplementDetail stored = detailByNode.get(node.nodeId());
      boolean hasChildren = parents.contains(node.nodeId());
      imported.add(new ImportedNode(node.nodeId(), node.parentNodeId(),
          selected == null ? node.materialCode() : selected.getMaterialCode(),
          selected == null ? node.materialName() : selected.getMaterialName(),
          selected == null ? node.materialSpec() : selected.getMaterialSpec(),
          selected == null ? node.materialModel() : selected.getMaterialModel(),
          selected == null ? node.drawingNo() : firstText(selected.getDrawingNo(), node.drawingNo()),
          selected == null ? node.materialNature() : materialNature(selected.getShapeAttr(), hasChildren),
          node.quantity(), selected == null ? node.unit() : firstText(selected.getUnit(), "件"),
          node.sortSeq(), stored == null ? null : stored.getRemark()));
    }
    draftService.replaceFromElectronicDrawingExcel(
        taskId, request.expectedVersion(), imported);
    return current(taskId);
  }

  @Transactional(readOnly = true)
  public List<Option> searchMaterialOptions(Long taskId, String keyword, int limit) {
    TechnicalBomWorkspaceResponse workspace = draftService.workspace(taskId);
    return matcher.search(workspace.target().materialOrganizationCode(), keyword, limit);
  }

  private ElectronicDrawingBomImportResponse parseFailure(
      Integer taskVersion,
      String fileName,
      String sha256,
      String sheetName,
      List<ElectronicDrawingExcelParseResult.Issue> parseIssues) {
    List<Issue> issues = parseIssues.stream().map(issue -> new Issue(
        "PARSE", issue.code(), null, issue.sourceRowNumber(), issue.sourceSequence(), issue.message())).toList();
    return new ElectronicDrawingBomImportResponse(false, false, false,
        issues.isEmpty() ? "电子图库 Excel 解析失败" : issues.getFirst().message(), taskVersion,
        null, fileName, sha256, sheetName, 0, 0, 0, 0, 0, null, List.of(), issues);
  }

  private List<QuoteBomSupplementDetail> details(Long versionId) {
    if (versionId == null) return List.of();
    return detailMapper.selectList(Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
        .eq(QuoteBomSupplementDetail::getSupplementVersionId, versionId)
        .orderByAsc(QuoteBomSupplementDetail::getLineNo));
  }

  private Option selected(Match match) {
    if (match == null || match.status() != Status.AUTO_MATCHED) return null;
    return match.options().stream()
        .filter(option -> Objects.equals(option.materialCode(), match.selectedMaterialCode()))
        .findFirst().orElse(null);
  }

  private static String materialNature(String value, boolean hasChildren) {
    String normalized = text(value);
    if (normalized == null) return hasChildren ? "MANUFACTURE" : "PURCHASE";
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (upper.contains("PURCHASE") || normalized.contains("采购")) return "PURCHASE";
    if (upper.contains("OUTSOURCE") || normalized.contains("委外")) return "OUTSOURCE";
    if (upper.contains("VIRTUAL") || upper.contains("PACKAGE")
        || normalized.contains("虚拟") || normalized.contains("包装")) return "VIRTUAL_PACKAGE";
    return "MANUFACTURE";
  }

  private static String parentSequence(String sequence) {
    if (sequence == null) return null;
    int index = sequence.lastIndexOf('.');
    return index < 0 ? null : sequence.substring(0, index);
  }

  private static int level(String sequence) {
    return sequence == null ? 1 : sequence.split("\\.").length;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("JVM 不支持 SHA-256", exception);
    }
  }

  private static void requireVersion(Integer actual, Integer expected) {
    if (!Objects.equals(actual, expected)) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_VERSION_CONFLICT, "任务版本已变化，请刷新后重试");
    }
  }

  private static String firstText(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  private static String text(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static CollaborationDomainException invalid(String message) {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.STATE_TRANSITION_INVALID, message);
  }

  private record SourceState(
      String nodeId,
      QuoteBomSupplementDetail detail,
      TechnicalBomDraftResponse.Node draftNode,
      ElectronicDrawingSourceMetadataCodec.NodeMetadata source) {}
}
