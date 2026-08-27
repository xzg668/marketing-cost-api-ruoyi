package com.sanhua.marketingcost.service.collaboration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackageCopyRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackageDraftRequest;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackagePriceCheckResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackageSearchResponse;
import com.sanhua.marketingcost.dto.collaboration.TechnicalPackageWorkspaceResponse;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.entity.QuoteBomPackageReference;
import com.sanhua.marketingcost.entity.QuoteBomPackageReferenceDetail;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteCollaborationGap;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPackageReferenceMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationProductTaskMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.security.CollaborationPortalModule;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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

/**
 * QCBP-12 裸品补包装入口。U9 本体全程只读，技术只维护独立包装参考草稿。
 */
@Service
public class TechnicalPackageDraftApplicationService {

  private static final String SCOPE = "BARE_PACKAGE";
  private static final String STATUS_DRAFT = "DRAFT";
  private static final String STATUS_APPROVED = "APPROVED";
  private static final String MODE_QUOTED_PRODUCT = "QUOTED_PRODUCT";
  private static final String MODE_PACKAGE_PARENT = "PACKAGE_PARENT";
  private static final int ACTIVE = 1;
  private static final BigDecimal ONE = BigDecimal.ONE;

  private final QuoteCollaborationTaskRepository repository;
  private final CollaborationCurrentPrincipalProvider principalProvider;
  private final FormalBomReadService formalBomReadService;
  private final QuoteProductBomPreparationService preparationService;
  private final QuoteBomPreparationRecordMapper preparationMapper;
  private final QuoteBomPackageReferenceMapper referenceMapper;
  private final QuoteBomPackageReferenceDetailMapper detailMapper;
  private final PackageComponentSnapshotMapper snapshotMapper;
  private final PackageComponentSnapshotDetailMapper snapshotDetailMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteCollaborationProductTaskMapper productTaskMapper;
  private final TechnicalRealPriceGapScanService priceScanService;
  private final CollaborationProductStateService stateService;
  private final CollaborationPortalAccessPolicy portalAccessPolicy;

  public TechnicalPackageDraftApplicationService(
      QuoteCollaborationTaskRepository repository,
      CollaborationCurrentPrincipalProvider principalProvider,
      FormalBomReadService formalBomReadService,
      QuoteProductBomPreparationService preparationService,
      QuoteBomPreparationRecordMapper preparationMapper,
      QuoteBomPackageReferenceMapper referenceMapper,
      QuoteBomPackageReferenceDetailMapper detailMapper,
      PackageComponentSnapshotMapper snapshotMapper,
      PackageComponentSnapshotDetailMapper snapshotDetailMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteCollaborationProductTaskMapper productTaskMapper,
      TechnicalRealPriceGapScanService priceScanService,
      CollaborationProductStateService stateService,
      CollaborationPortalAccessPolicy portalAccessPolicy) {
    this.repository = repository;
    this.principalProvider = principalProvider;
    this.formalBomReadService = formalBomReadService;
    this.preparationService = preparationService;
    this.preparationMapper = preparationMapper;
    this.referenceMapper = referenceMapper;
    this.detailMapper = detailMapper;
    this.snapshotMapper = snapshotMapper;
    this.snapshotDetailMapper = snapshotDetailMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.productTaskMapper = productTaskMapper;
    this.priceScanService = priceScanService;
    this.stateService = stateService;
    this.portalAccessPolicy = portalAccessPolicy;
  }

  @Transactional(readOnly = true)
  public TechnicalPackageWorkspaceResponse workspace(Long taskId) {
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuoteCollaborationProductTask task = ownTask(taskId, principal);
    return workspace(task);
  }

  @Transactional(readOnly = true)
  public TechnicalPackageSearchResponse searchReferenceProducts(Long taskId, String keyword) {
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuoteCollaborationProductTask task = ownTask(taskId, principal);
    String query = trimToNull(keyword);
    List<QuoteBomPackageReference> rows = referenceMapper.selectList(
        Wrappers.<QuoteBomPackageReference>lambdaQuery()
            .eq(QuoteBomPackageReference::getReferenceStatus, STATUS_APPROVED)
            .eq(QuoteBomPackageReference::getActiveFlag, ACTIVE)
            .orderByDesc(QuoteBomPackageReference::getUpdatedAt)
            .orderByDesc(QuoteBomPackageReference::getId)
            .last("LIMIT 200"));
    List<TechnicalPackageSearchResponse.Candidate> candidates = new ArrayList<>();
    for (QuoteBomPackageReference row : safe(rows)) {
      if (!referenceInScope(row, task) || !matchesReference(row, query)) continue;
      int count = selectedDetailCount(row.getId());
      if (count <= 0) continue;
      OaFormItem item = oaFormItemMapper.selectById(row.getOaFormItemId());
      candidates.add(new TechnicalPackageSearchResponse.Candidate(
          row.getId(), MODE_QUOTED_PRODUCT,
          firstText(row.getQuoteProductCode(), row.getReferenceFinishedCode()),
          item == null ? null : item.getProductName(), row.getSourceTopProductCode(),
          row.getPeriodMonth(), count, true,
          "已通过财务审核的包装方案，可复制后只改差异"));
    }
    return new TechnicalPackageSearchResponse(
        MODE_QUOTED_PRODUCT, candidates.size(), List.copyOf(candidates));
  }

  @Transactional(readOnly = true)
  public TechnicalPackageSearchResponse searchPackageParents(Long taskId, String keyword) {
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuoteCollaborationProductTask task = ownTask(taskId, principal);
    String query = trimToNull(keyword);
    List<PackageComponentSnapshot> rows = snapshotMapper.selectList(
        Wrappers.<PackageComponentSnapshot>lambdaQuery()
            .eq(PackageComponentSnapshot::getPeriodMonth, task.getAccountingMonth())
            .eq(PackageComponentSnapshot::getStatus, "NORMAL")
            .eq(PackageComponentSnapshot::getPriceOrgCode, task.getPriceOrgCode())
            .eq(PackageComponentSnapshot::getBusinessUnitType, task.getBusinessUnitType())
            .orderByDesc(PackageComponentSnapshot::getLockedAt)
            .orderByDesc(PackageComponentSnapshot::getId)
            .last("LIMIT 200"));
    List<TechnicalPackageSearchResponse.Candidate> candidates = safe(rows).stream()
        .filter(row -> matches(query, row.getPackageMaterialCode(), row.getPackageMaterialName(),
            row.getSourceTopProductCode()))
        .map(row -> new TechnicalPackageSearchResponse.Candidate(
            row.getId(), MODE_PACKAGE_PARENT, row.getPackageMaterialCode(),
            row.getPackageMaterialName(), row.getSourceTopProductCode(), row.getPeriodMonth(),
            snapshotLineCount(row), false,
            "现有包装目件结构，将连同其下级包装材料复制为草稿"))
        .toList();
    return new TechnicalPackageSearchResponse(
        MODE_PACKAGE_PARENT, candidates.size(), candidates);
  }

  @Transactional
  public TechnicalPackageWorkspaceResponse copy(
      Long taskId, TechnicalPackageCopyRequest request) {
    Integer expectedVersion = requireVersion(
        request == null ? null : request.expectedTaskVersion());
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuoteCollaborationProductTask task = currentWritable(taskId, expectedVersion, principal);
    String mode = requireMode(request == null ? null : request.sourceMode());
    Long sourceId = requireId(request == null ? null : request.sourceId(), "包装来源ID");
    QuoteCollaborationQuoteLink owner = owner(task);
    QuoteProductBomPreparationPreview preparation = preparationService.prepareByOaFormItem(
        owner.getOaFormItemId(), LocalDate.now());
    if (preparation == null || !preparation.bodyBomReady()) {
      throw invalid("U9裸品本体BOM当前不可用，不能只补包装");
    }

    QuoteBomPackageReference sourceReference = null;
    PackageComponentSnapshot sourceSnapshot = null;
    List<QuoteBomPackageReferenceDetail> copied;
    String referenceFinishedCode;
    String sourceTopProductCode;
    String sourcePeriod;
    Long reusedFromId = null;
    if (MODE_QUOTED_PRODUCT.equals(mode)) {
      sourceReference = referenceMapper.selectById(sourceId);
      if (sourceReference == null || !STATUS_APPROVED.equals(sourceReference.getReferenceStatus())
          || !Objects.equals(sourceReference.getActiveFlag(), ACTIVE)
          || !referenceInScope(sourceReference, task)) {
        throw invalid("选择的参考产品包装不存在、未审核或不在当前组织");
      }
      List<QuoteBomPackageReferenceDetail> sourceLines = details(sourceReference.getId());
      if (sourceLines.isEmpty()) throw invalid("选择的参考产品没有可复制包装明细");
      referenceFinishedCode = sourceReference.getReferenceFinishedCode();
      sourceTopProductCode = sourceReference.getSourceTopProductCode();
      sourcePeriod = sourceReference.getPeriodMonth();
      reusedFromId = sourceReference.getId();
      copied = cloneApprovedLines(sourceLines, task, preparation.preparationRecordId(), owner);
    } else {
      sourceSnapshot = snapshotMapper.selectById(sourceId);
      if (!snapshotInScope(sourceSnapshot, task)) {
        throw invalid("选择的包装目件不存在或不在当前月份和组织");
      }
      referenceFinishedCode = sourceSnapshot.getSourceTopProductCode();
      sourceTopProductCode = sourceSnapshot.getSourceTopProductCode();
      sourcePeriod = sourceSnapshot.getPeriodMonth();
      copied = cloneSnapshotGraph(
          sourceSnapshot, task, preparation.preparationRecordId(), owner);
    }

    QuoteBomPackageReference draft = currentReference(task);
    if (draft == null) draft = new QuoteBomPackageReference();
    draft.setPreparationId(preparation.preparationRecordId());
    draft.setOaNo(owner.getOaNo());
    draft.setOaFormItemId(owner.getOaFormItemId());
    draft.setQuoteProductCode(task.getProductCode());
    draft.setBareProductCode(task.getProductCode());
    draft.setReferenceFinishedCode(firstText(referenceFinishedCode, sourceTopProductCode));
    draft.setSourceTopProductCode(sourceTopProductCode);
    draft.setPeriodMonth(sourcePeriod);
    draft.setSnapshotId(sourceSnapshot == null ? null : sourceSnapshot.getId());
    draft.setReferenceStatus(STATUS_DRAFT);
    draft.setSelectedLineCount(copied.size());
    draft.setEditedFlag(0);
    draft.setActiveFlag(ACTIVE);
    draft.setReusedFromReferenceId(reusedFromId);
    draft.setRemark("SOURCE_MODE=" + mode);
    LocalDateTime now = LocalDateTime.now();
    if (draft.getId() == null) {
      draft.setCreatedAt(now);
      draft.setUpdatedAt(now);
      requireOne(referenceMapper.insert(draft), "保存包装草稿");
    } else {
      draft.setUpdatedAt(now);
      requireOne(referenceMapper.updateById(draft), "更新包装草稿");
      detailMapper.delete(Wrappers.<QuoteBomPackageReferenceDetail>lambdaQuery()
          .eq(QuoteBomPackageReferenceDetail::getPackageReferenceId, draft.getId()));
    }
    for (QuoteBomPackageReferenceDetail line : copied) {
      line.setPackageReferenceId(draft.getId());
      requireOne(detailMapper.insert(line), "保存包装草稿明细");
    }
    attach(task, expectedVersion, preparation.preparationRecordId(), draft.getId(), principal);
    return workspace(taskId);
  }

  @Transactional
  public TechnicalPackageWorkspaceResponse save(
      Long taskId, TechnicalPackageDraftRequest request) {
    Integer expectedVersion = requireVersion(
        request == null ? null : request.expectedTaskVersion());
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuoteCollaborationProductTask task = currentWritable(taskId, expectedVersion, principal);
    QuoteBomPackageReference draft = currentReference(task);
    if (draft == null) throw invalid("请先选择一个包装参考来源");
    List<QuoteBomPackageReferenceDetail> before = details(draft.getId());
    Map<Long, QuoteBomPackageReferenceDetail> beforeById = before.stream()
        .filter(row -> row.getId() != null)
        .collect(Collectors.toMap(QuoteBomPackageReferenceDetail::getId, Function.identity()));
    QuoteCollaborationQuoteLink owner = owner(task);
    List<TechnicalPackageDraftRequest.Line> requested =
        request == null ? List.of() : request.lines();
    if (requested.isEmpty()) throw invalid("包装草稿至少保留一条父子关系");
    if (requested.size() > 500) throw invalid("单个包装草稿最多500条父子关系");
    List<QuoteBomPackageReferenceDetail> rows = new ArrayList<>();
    for (int index = 0; index < requested.size(); index++) {
      TechnicalPackageDraftRequest.Line line = requested.get(index);
      QuoteBomPackageReferenceDetail original = line == null
          ? null : beforeById.get(line.draftLineId());
      rows.add(fromRequest(draft, task, owner, line, original, index + 1));
    }
    normalizeGraph(task.getProductCode(), rows);
    detailMapper.delete(Wrappers.<QuoteBomPackageReferenceDetail>lambdaQuery()
        .eq(QuoteBomPackageReferenceDetail::getPackageReferenceId, draft.getId()));
    boolean edited = false;
    for (QuoteBomPackageReferenceDetail row : rows) {
      edited |= Objects.equals(row.getEditedFlag(), 1);
      requireOne(detailMapper.insert(row), "保存包装草稿明细");
    }
    draft.setSelectedLineCount(rows.size());
    draft.setEditedFlag(edited ? 1 : 0);
    draft.setReferenceStatus(STATUS_DRAFT);
    draft.setUpdatedAt(LocalDateTime.now());
    requireOne(referenceMapper.updateById(draft), "更新包装草稿汇总");
    attach(task, expectedVersion, draft.getPreparationId(), draft.getId(), principal);
    return workspace(taskId);
  }

  @Transactional
  public TechnicalPackagePriceCheckResponse checkPrice(Long taskId, Integer expectedVersion) {
    Integer version = requireVersion(expectedVersion);
    CollaborationPrincipal principal = principalProvider.currentTechnician();
    QuoteCollaborationProductTask task = currentPackageInProgress(taskId, version, principal);
    QuoteBomPackageReference draft = currentReference(task);
    if (draft == null) throw invalid("请先保存包装草稿");
    List<QuoteBomPackageReferenceDetail> rows = details(draft.getId());
    normalizeGraph(task.getProductCode(), rows);
    QuoteCollaborationQuoteLink owner = owner(task);
    var scan = priceScanService.scan(task, owner);
    if (scan == null || scan.status()
        == com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult.Status.ERROR) {
      throw invalid(firstText(scan == null ? null : scan.message(), "组合BOM价格检查失败"));
    }
    String productCode = task.getProductCode();
    List<GapUpsertCommand> gaps = scan.gaps().stream()
        .map(gap -> CollaborationPriceGapCommandFactory.create(
            owner.getOaFormItemId(), productCode, gap))
        .toList();
    CollaborationScope scope = scope(task);
    repository.synchronizeGaps(task.getId(), scope, gaps, principal.actor());
    int gapCount = gaps.size();
    int affected = productTaskMapper.applyPackagePriceScan(
        task.getId(), task.getTaskVersion(), gapCount > 0 ? 1 : 0, gapCount,
        gapCount > 0 ? "NOT_CHECKED" : "PASSED", principal.userId(),
        scope.businessUnitType(), scope.applicableOrgCode(), principal.userId(),
        principal.userName());
    if (affected != 1) throw versionConflict();
    task = repository.findProductTaskById(task.getId(), scope)
        .orElseThrow(() -> invalid("包装价格检查后无法读取任务"));
    if (gapCount > 0) {
      task = stateService.transition(task.getId(), task.getTaskVersion(), scope,
          ProductAction.CONTINUE_PRICE_AFTER_PACKAGE, principal).task();
    }
    TechnicalPackageWorkspaceResponse current = workspace(task.getId());
    return new TechnicalPackagePriceCheckResponse(
        true, gapCount > 0 ? "PRICE_GAPS" : "READY_FOR_REVIEW",
        gapCount > 0
            ? "包装已保存，发现" + gapCount + "项真实缺价，请继续补价"
            : "包装已保存且当前价格齐全，仍需提交财务审核包装结构和用量",
        task.getTaskVersion(), scan.checkedItemCount(), gapCount, current);
  }

  private TechnicalPackageWorkspaceResponse workspace(QuoteCollaborationProductTask task) {
    FormalBomReadResult body = readBody(task);
    QuoteBomPackageReference reference = currentReference(task);
    List<QuoteBomPackageReferenceDetail> rows = reference == null
        ? List.of() : details(reference.getId());
    List<QuoteCollaborationGap> priceGaps = repository.findGaps(task.getId(), scope(task)).stream()
        .filter(gap -> "PRICE".equals(gap.getGapCategory()))
        .filter(gap -> !"OBSOLETE".equals(gap.getGapStatus()))
        .toList();
    TechnicalPackageWorkspaceResponse.BodySummary summary =
        new TechnicalPackageWorkspaceResponse.BodySummary(
            task.getProductCode(), body != null && body.found(),
            body == null || body.lines() == null ? 0 : body.lines().size(), "U9",
            body != null && body.found()
                ? "U9裸品本体BOM只读保留，不会复制到包装草稿"
                : firstText(body == null ? null : body.gapMessage(), "U9裸品本体BOM不可用"));
    TechnicalPackageWorkspaceResponse.Draft draft = reference == null ? null
        : new TechnicalPackageWorkspaceResponse.Draft(
            reference.getId(), sourceMode(reference), sourceLabel(reference),
            reference.getReferenceStatus(), rows.size(), Objects.equals(reference.getEditedFlag(), 1),
            rows.stream().map(this::toLine).toList(), toTree(task.getProductCode(), rows));
    TechnicalPackageWorkspaceResponse.CombinedBom combined = combinedBom(task, body, rows);
    String guidance = draft == null
        ? "先从已审核报价产品或包装目件复制一份，再只改不同的包装材料和用量"
        : priceGaps.isEmpty()
            ? "确认包装树后保存并检查价格；即使零缺价，包装仍提交财务审核"
            : "包装已保存，只处理系统列出的真实缺价材料";
    return new TechnicalPackageWorkspaceResponse(
        task.getId(), task.getTaskVersion(), task.getTaskStatus(), summary, draft, combined,
        priceGaps.size(), guidance);
  }

  private TechnicalPackageWorkspaceResponse.CombinedBom combinedBom(
      QuoteCollaborationProductTask task,
      FormalBomReadResult body,
      List<QuoteBomPackageReferenceDetail> packageRows) {
    List<TechnicalPackageWorkspaceResponse.CandidateLine> lines = new ArrayList<>();
    List<QuoteBomSourceLineDto> bodyLines = body == null || body.lines() == null
        ? List.of() : body.lines();
    for (QuoteBomSourceLineDto line : bodyLines) {
      lines.add(new TechnicalPackageWorkspaceResponse.CandidateLine(
          "U9_BODY", line.level(), line.parentCode(), line.materialCode(), line.materialName(),
          line.qtyPerParent(), line.qtyPerTop(), line.unit(), line.path()));
    }
    int packageCount = appendPackageCandidateLines(task.getProductCode(), packageRows, lines);
    return new TechnicalPackageWorkspaceResponse.CombinedBom(
        body != null && body.found() && !packageRows.isEmpty(), bodyLines.size(), packageCount,
        lines.size(), lines);
  }

  private int appendPackageCandidateLines(
      String targetProductCode,
      List<QuoteBomPackageReferenceDetail> rows,
      List<TechnicalPackageWorkspaceResponse.CandidateLine> output) {
    if (rows == null || rows.isEmpty()) return 0;
    Map<String, List<QuoteBomPackageReferenceDetail>> byParent = rows.stream()
        .collect(Collectors.groupingBy(QuoteBomPackageReferenceDetail::getPackageParentCode,
            LinkedHashMap::new, Collectors.toList()));
    Set<String> children = rows.stream()
        .map(QuoteBomPackageReferenceDetail::getPackageMaterialCode)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    int before = output.size();
    for (String root : byParent.keySet().stream().filter(code -> !children.contains(code)).toList()) {
      QuoteBomPackageReferenceDetail sample = byParent.get(root).get(0);
      BigDecimal rootQty = currentPackageQty(sample);
      String rootPath = "/" + targetProductCode + "/__PACKAGE__/" + root + "/";
      output.add(new TechnicalPackageWorkspaceResponse.CandidateLine(
          "PACKAGE_DRAFT", 1, targetProductCode, root,
          firstText(sample.getPackageParentName(), root), rootQty, rootQty,
          firstText(sample.getPackageParentUnit(), "件"), rootPath));
      appendPackageChildren(root, rootQty, 2, rootPath, byParent, output,
          new LinkedHashSet<>());
    }
    return output.size() - before;
  }

  private void appendPackageChildren(
      String parentCode,
      BigDecimal parentQty,
      int level,
      String parentPath,
      Map<String, List<QuoteBomPackageReferenceDetail>> byParent,
      List<TechnicalPackageWorkspaceResponse.CandidateLine> output,
      Set<String> ancestors) {
    if (!ancestors.add(parentCode)) throw invalid("包装候选树存在循环：" + parentCode);
    for (QuoteBomPackageReferenceDetail row : byParent.getOrDefault(parentCode, List.of())) {
      BigDecimal perParent = currentChildQty(row);
      BigDecimal perTop = parentQty.multiply(perParent);
      String child = row.getPackageMaterialCode();
      String path = parentPath + child + "/";
      output.add(new TechnicalPackageWorkspaceResponse.CandidateLine(
          "PACKAGE_DRAFT", level, parentCode, child, row.getPackageMaterialName(),
          perParent, perTop, firstText(row.getUnit(), row.getPackageMaterialUnit()), path));
      appendPackageChildren(child, perTop, level + 1, path, byParent, output,
          new LinkedHashSet<>(ancestors));
    }
  }

  private FormalBomReadResult readBody(QuoteCollaborationProductTask task) {
    QuoteCollaborationQuoteLink owner = owner(task);
    return formalBomReadService.read(new QuoteBomReadContext(
        owner.getOaNo(), owner.getOaFormItemId(), task.getProductCode(),
        task.getAccountingMonth(), task.getPriceOrgCode(), task.getMaterialOrgCode(),
        task.getBusinessUnitType(), "主制造", LocalDate.now()));
  }

  private List<QuoteBomPackageReferenceDetail> cloneApprovedLines(
      List<QuoteBomPackageReferenceDetail> source,
      QuoteCollaborationProductTask task,
      Long preparationId,
      QuoteCollaborationQuoteLink owner) {
    List<QuoteBomPackageReferenceDetail> rows = new ArrayList<>();
    int lineNo = 1;
    for (QuoteBomPackageReferenceDetail line : source) {
      if (!Objects.equals(line.getSelectedFlag(), ACTIVE)) continue;
      QuoteBomPackageReferenceDetail target = new QuoteBomPackageReferenceDetail();
      copySourceFields(line, target);
      applyTargetContext(target, task, preparationId, owner, lineNo++);
      target.setSelectedFlag(ACTIVE);
      target.setEditedFlag(0);
      target.setCreatedAt(LocalDateTime.now());
      target.setUpdatedAt(LocalDateTime.now());
      rows.add(target);
    }
    normalizeGraph(task.getProductCode(), rows);
    return rows;
  }

  private List<QuoteBomPackageReferenceDetail> cloneSnapshotGraph(
      PackageComponentSnapshot source,
      QuoteCollaborationProductTask task,
      Long preparationId,
      QuoteCollaborationQuoteLink owner) {
    List<QuoteBomPackageReferenceDetail> rows = new ArrayList<>();
    appendSnapshot(
        source, task, preparationId, owner, rows, new LinkedHashSet<>());
    if (rows.isEmpty()) throw invalid("选择的包装目件没有子件结构");
    normalizeGraph(task.getProductCode(), rows);
    return rows;
  }

  /**
   * 直接消费已锁定的包装快照。快照中的规格/型号属于辅助描述，历史数据允许为空；
   * 父子料号、正数用量、组织、月份、状态和无循环才是可复制结构的硬约束。
   */
  private void appendSnapshot(
      PackageComponentSnapshot snapshot,
      QuoteCollaborationProductTask task,
      Long preparationId,
      QuoteCollaborationQuoteLink owner,
      List<QuoteBomPackageReferenceDetail> rows,
      Set<String> ancestors) {
    String parentCode = requiredText(snapshot.getPackageMaterialCode(), "包装父件料号");
    if (!ancestors.add(parentCode)) throw invalid("包装结构存在循环：" + parentCode);
    List<PackageComponentSnapshotDetail> details = snapshotDetailMapper.selectList(
        Wrappers.<PackageComponentSnapshotDetail>lambdaQuery()
            .eq(PackageComponentSnapshotDetail::getSnapshotId, snapshot.getId())
            .orderByAsc(PackageComponentSnapshotDetail::getLineNo)
            .orderByAsc(PackageComponentSnapshotDetail::getId));
    if (details == null || details.isEmpty()) {
      throw invalid("包装目件 " + parentCode + " 没有子件结构");
    }
    for (PackageComponentSnapshotDetail line : details) {
      QuoteBomPackageReferenceDetail target = new QuoteBomPackageReferenceDetail();
      applyTargetContext(target, task, preparationId, owner, rows.size() + 1);
      target.setReferenceFinishedCode(snapshot.getSourceTopProductCode());
      target.setSourceTopProductCode(snapshot.getSourceTopProductCode());
      target.setSnapshotId(snapshot.getId());
      target.setSnapshotDetailId(line.getId());
      target.setPackageParentCode(parentCode);
      target.setPackageParentName(firstText(snapshot.getPackageMaterialName(), parentCode));
      target.setPackageParentUnit("件");
      target.setPackageQtyPerParent(firstNonNull(snapshot.getPackageQtyPerParent(), ONE));
      target.setPackageQtyPerTop(firstNonNull(snapshot.getPackageQtyPerTop(), ONE));
      target.setPackageParentBaseQty(firstNonNull(snapshot.getPackageParentBaseQty(), ONE));
      target.setPackageMaterialCode(requiredText(line.getChildMaterialCode(), "包装子件料号"));
      target.setPackageMaterialName(firstText(line.getChildMaterialName(), line.getChildMaterialCode()));
      target.setPackageMaterialSpec(trimToNull(line.getChildMaterialSpec()));
      target.setPackageMaterialShapeAttr(trimToNull(line.getChildShapeAttr()));
      target.setPackageMaterialUnit("件");
      target.setChildQtyPerParent(positive(line.getQtyPerParent(), "包装子件相对用量"));
      target.setChildQtyPerTop(line.getQtyPerTop());
      target.setChildParentBaseQty(firstNonNull(line.getChildParentBaseQty(), ONE));
      target.setQtyPerTop(line.getQtyPerTop());
      target.setUnit("件");
      target.setSourceRawHierarchyId(line.getSourceHierarchyId());
      target.setSourceParentCode(firstText(line.getSourceParentCode(), parentCode));
      target.setSourcePath(trimToNull(line.getSourcePath()));
      target.setSelectedFlag(ACTIVE);
      target.setEditedFlag(0);
      target.setCreatedAt(LocalDateTime.now());
      target.setUpdatedAt(LocalDateTime.now());
      rows.add(target);

      PackageComponentSnapshot childSnapshot = findChildSnapshot(
          snapshot, line.getChildMaterialCode(), task);
      if (childSnapshot != null) {
        appendSnapshot(childSnapshot, task, preparationId, owner, rows,
            new LinkedHashSet<>(ancestors));
      }
    }
  }

  private PackageComponentSnapshot findChildSnapshot(
      PackageComponentSnapshot parent,
      String childCode,
      QuoteCollaborationProductTask task) {
    List<PackageComponentSnapshot> rows = snapshotMapper.selectList(
        Wrappers.<PackageComponentSnapshot>lambdaQuery()
            .eq(PackageComponentSnapshot::getPackageMaterialCode, childCode)
            .eq(PackageComponentSnapshot::getPeriodMonth, parent.getPeriodMonth())
            .eq(PackageComponentSnapshot::getStatus, "NORMAL")
            .eq(PackageComponentSnapshot::getPriceOrgCode, task.getPriceOrgCode())
            .eq(PackageComponentSnapshot::getBusinessUnitType, task.getBusinessUnitType())
            .eq(PackageComponentSnapshot::getSourceTopProductCode, parent.getSourceTopProductCode())
            .orderByDesc(PackageComponentSnapshot::getLockedAt)
            .orderByDesc(PackageComponentSnapshot::getId)
            .last("LIMIT 1"));
    return rows == null || rows.isEmpty() ? null : rows.get(0);
  }

  private QuoteBomPackageReferenceDetail fromRequest(
      QuoteBomPackageReference reference,
      QuoteCollaborationProductTask task,
      QuoteCollaborationQuoteLink owner,
      TechnicalPackageDraftRequest.Line request,
      QuoteBomPackageReferenceDetail original,
      int lineNo) {
    if (request == null) throw invalid("包装明细第" + lineNo + "行为空");
    QuoteBomPackageReferenceDetail row = new QuoteBomPackageReferenceDetail();
    if (original != null) copySourceFields(original, row);
    row.setPackageReferenceId(reference.getId());
    applyTargetContext(row, task, reference.getPreparationId(), owner, lineNo);
    row.setReferenceFinishedCode(reference.getReferenceFinishedCode());
    row.setSourceTopProductCode(reference.getSourceTopProductCode());
    row.setPackageParentCode(requiredText(request.packageParentCode(), "包装父件料号"));
    row.setPackageParentName(firstText(request.packageParentName(), row.getPackageParentName(), row.getPackageParentCode()));
    row.setPackageParentSpec(firstText(request.packageParentSpec(), row.getPackageParentSpec()));
    row.setPackageParentModel(firstText(request.packageParentModel(), row.getPackageParentModel()));
    row.setPackageParentUnit(firstText(request.packageParentUnit(), row.getPackageParentUnit(), "件"));
    row.setPackageMaterialCode(requiredText(request.packageMaterialCode(), "包装子件料号"));
    row.setPackageMaterialName(firstText(request.packageMaterialName(), row.getPackageMaterialName(), row.getPackageMaterialCode()));
    row.setPackageMaterialSpec(firstText(request.packageMaterialSpec(), row.getPackageMaterialSpec()));
    row.setPackageMaterialModel(firstText(request.packageMaterialModel(), row.getPackageMaterialModel()));
    row.setPackageMaterialUnit(firstText(request.packageMaterialUnit(), row.getPackageMaterialUnit(), "件"));
    BigDecimal parentQty = positive(request.packageQtyPerTop(), "包装父件累计用量");
    BigDecimal childQty = positive(request.childQtyPerParent(), "包装子件相对用量");
    row.setAdjustedPackageQtyPerTop(changed(parentQty, row.getPackageQtyPerTop()) ? parentQty : null);
    row.setAdjustedChildQtyPerParent(changed(childQty, row.getChildQtyPerParent()) ? childQty : null);
    row.setPackageQtyPerTop(firstNonNull(row.getPackageQtyPerTop(), parentQty));
    row.setPackageQtyPerParent(firstNonNull(row.getPackageQtyPerParent(), parentQty));
    row.setPackageParentBaseQty(firstNonNull(row.getPackageParentBaseQty(), ONE));
    row.setChildQtyPerParent(firstNonNull(row.getChildQtyPerParent(), childQty));
    row.setChildParentBaseQty(firstNonNull(row.getChildParentBaseQty(), ONE));
    row.setUnit(row.getPackageMaterialUnit());
    row.setRemark(trimToNull(request.remark()));
    row.setSelectedFlag(ACTIVE);
    row.setEditedFlag(original == null || requestChanged(request, original) ? 1 : original.getEditedFlag());
    row.setCreatedAt(original == null ? LocalDateTime.now() : original.getCreatedAt());
    row.setUpdatedAt(LocalDateTime.now());
    return row;
  }

  private void normalizeGraph(
      String targetProductCode, List<QuoteBomPackageReferenceDetail> rows) {
    if (rows == null || rows.isEmpty()) throw invalid("包装草稿没有父子关系");
    Map<String, QuoteBomPackageReferenceDetail> incomingByChild = new LinkedHashMap<>();
    Map<String, List<QuoteBomPackageReferenceDetail>> childrenByParent = new LinkedHashMap<>();
    Set<String> edgeKeys = new HashSet<>();
    for (QuoteBomPackageReferenceDetail row : rows) {
      String parent = requiredText(row.getPackageParentCode(), "包装父件料号");
      String child = requiredText(row.getPackageMaterialCode(), "包装子件料号");
      if (parent.equals(child)) throw invalid("包装结构不能自己挂自己：" + parent);
      positive(currentChildQty(row), "包装子件相对用量");
      if (!edgeKeys.add(parent + "->" + child)) {
        throw invalid("包装父子关系重复：" + parent + " → " + child);
      }
      if (incomingByChild.put(child, row) != null) {
        throw invalid("当前包装编辑器不允许同一包装节点挂在两个父件下：" + child);
      }
      childrenByParent.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(row);
    }
    Set<String> children = incomingByChild.keySet();
    List<String> roots = childrenByParent.keySet().stream()
        .filter(parent -> !children.contains(parent)).toList();
    if (roots.isEmpty()) throw invalid("包装结构存在闭环，没有可挂到裸品的根包装");
    Set<String> visitedEdges = new HashSet<>();
    int[] sequence = {1};
    for (String root : roots) {
      QuoteBomPackageReferenceDetail first = childrenByParent.get(root).get(0);
      BigDecimal rootQty = firstNonNull(
          first.getAdjustedPackageQtyPerTop(), first.getPackageQtyPerTop(), ONE);
      String rootPath = "/" + requiredText(targetProductCode, "目标裸品料号")
          + "/__PACKAGE__/" + root + "/";
      walkGraph(root, rootQty, rootPath, childrenByParent, new LinkedHashSet<>(),
          visitedEdges, sequence);
    }
    if (visitedEdges.size() != rows.size()) {
      throw invalid("包装结构存在无法从根包装到达的循环或孤立关系");
    }
  }

  private void walkGraph(
      String parent,
      BigDecimal parentQty,
      String parentPath,
      Map<String, List<QuoteBomPackageReferenceDetail>> childrenByParent,
      Set<String> ancestors,
      Set<String> visitedEdges,
      int[] sequence) {
    if (!ancestors.add(parent)) throw invalid("包装结构存在循环：" + parent);
    for (QuoteBomPackageReferenceDetail row : childrenByParent.getOrDefault(parent, List.of())) {
      String child = row.getPackageMaterialCode();
      String edge = parent + "->" + child;
      if (!visitedEdges.add(edge)) throw invalid("包装结构存在重复遍历：" + edge);
      BigDecimal qty = parentQty.multiply(currentChildQty(row));
      row.setLineNo(sequence[0]++);
      row.setPackageQtyPerTop(firstNonNull(row.getPackageQtyPerTop(), parentQty));
      if (changed(parentQty, row.getPackageQtyPerTop())) {
        row.setAdjustedPackageQtyPerTop(parentQty);
      }
      row.setChildQtyPerTop(firstNonNull(row.getChildQtyPerTop(), qty));
      row.setQtyPerTop(qty);
      row.setSourceParentCode(parent);
      row.setSourcePath(parentPath + child + "-" + row.getLineNo() + "/");
      walkGraph(child, qty, row.getSourcePath(), childrenByParent,
          new LinkedHashSet<>(ancestors), visitedEdges, sequence);
    }
  }

  private TechnicalPackageWorkspaceResponse.Line toLine(QuoteBomPackageReferenceDetail row) {
    return new TechnicalPackageWorkspaceResponse.Line(
        row.getId(), row.getLineNo(), row.getPackageParentCode(), row.getPackageParentName(),
        row.getPackageParentSpec(), row.getPackageParentModel(), row.getPackageParentUnit(),
        currentPackageQty(row), row.getPackageMaterialCode(), row.getPackageMaterialName(),
        row.getPackageMaterialSpec(), row.getPackageMaterialModel(),
        firstText(row.getUnit(), row.getPackageMaterialUnit()), currentChildQty(row),
        row.getQtyPerTop(), row.getSourcePath(), Objects.equals(row.getEditedFlag(), 1));
  }

  private List<TechnicalPackageWorkspaceResponse.TreeNode> toTree(
      String targetProductCode, List<QuoteBomPackageReferenceDetail> rows) {
    if (rows.isEmpty()) return List.of();
    Map<String, List<QuoteBomPackageReferenceDetail>> byParent = rows.stream()
        .collect(Collectors.groupingBy(QuoteBomPackageReferenceDetail::getPackageParentCode,
            LinkedHashMap::new, Collectors.toList()));
    Set<String> children = rows.stream().map(QuoteBomPackageReferenceDetail::getPackageMaterialCode)
        .collect(Collectors.toSet());
    List<TechnicalPackageWorkspaceResponse.TreeNode> roots = new ArrayList<>();
    for (String root : byParent.keySet().stream().filter(code -> !children.contains(code)).toList()) {
      QuoteBomPackageReferenceDetail sample = byParent.get(root).get(0);
      roots.add(treeNode(root, sample.getPackageParentName(), currentPackageQty(sample),
          sample.getPackageParentUnit(), byParent, new HashSet<>()));
    }
    return List.copyOf(roots);
  }

  private TechnicalPackageWorkspaceResponse.TreeNode treeNode(
      String code,
      String name,
      BigDecimal quantity,
      String unit,
      Map<String, List<QuoteBomPackageReferenceDetail>> byParent,
      Set<String> ancestors) {
    if (!ancestors.add(code)) throw invalid("包装树存在循环：" + code);
    List<TechnicalPackageWorkspaceResponse.TreeNode> children = new ArrayList<>();
    for (QuoteBomPackageReferenceDetail row : byParent.getOrDefault(code, List.of())) {
      children.add(treeNode(row.getPackageMaterialCode(), row.getPackageMaterialName(),
          currentChildQty(row), firstText(row.getUnit(), row.getPackageMaterialUnit()),
          byParent, new HashSet<>(ancestors)));
    }
    return new TechnicalPackageWorkspaceResponse.TreeNode(
        code, code, firstText(name, code), quantity, unit, byParent.containsKey(code), children);
  }

  private void attach(
      QuoteCollaborationProductTask task,
      Integer expectedVersion,
      Long preparationId,
      Long referenceId,
      CollaborationPrincipal principal) {
    CollaborationScope scope = scope(task);
    int updated = productTaskMapper.attachPackageDraft(
        task.getId(), expectedVersion, preparationId, referenceId, principal.userId(),
        scope.businessUnitType(), scope.applicableOrgCode(), principal.userId(),
        principal.userName());
    if (updated != 1) throw versionConflict();
  }

  private QuoteCollaborationProductTask ownTask(
      Long taskId, CollaborationPrincipal principal) {
    if (taskId == null || taskId <= 0) throw notFound();
    QuoteCollaborationProductTask task = repository.findMineById(
        taskId, principal.userId(), currentBusinessUnit()).orElseThrow(this::notFound);
    portalAccessPolicy.requireTask(task, CollaborationPortalModule.BOM);
    if (!SCOPE.equals(task.getPrimaryScope()) || !Objects.equals(task.getNeedPackage(), 1)) {
      throw invalid("当前任务不是裸品补包装任务");
    }
    return task;
  }

  private QuoteCollaborationProductTask currentWritable(
      Long taskId, Integer expectedVersion, CollaborationPrincipal principal) {
    QuoteCollaborationProductTask task = ownTask(taskId, principal);
    requireSameVersion(task, expectedVersion);
    if (!Set.of("PACKAGE_IN_PROGRESS", "TECH_VALIDATION_FAILED", "RETURNED_TO_TECH")
        .contains(task.getTaskStatus())) {
      throw invalid("请先开始裸品补包装任务");
    }
    return task;
  }

  private QuoteCollaborationProductTask currentPackageInProgress(
      Long taskId, Integer expectedVersion, CollaborationPrincipal principal) {
    QuoteCollaborationProductTask task = ownTask(taskId, principal);
    requireSameVersion(task, expectedVersion);
    if (!Set.of("PACKAGE_IN_PROGRESS", "RETURNED_TO_TECH").contains(task.getTaskStatus())) {
      throw invalid("当前任务不在可检查包装价格的处理阶段");
    }
    return task;
  }

  private QuoteCollaborationQuoteLink owner(QuoteCollaborationProductTask task) {
    return repository.findLinksByProductTask(task.getId(), scope(task)).stream()
        .filter(link -> "OWNER".equals(link.getLinkType()))
        .max(Comparator.comparing(QuoteCollaborationQuoteLink::getId))
        .orElseThrow(() -> invalid("产品任务没有报价来源行"));
  }

  private QuoteBomPackageReference currentReference(QuoteCollaborationProductTask task) {
    if (task.getPackageReferenceId() == null) return null;
    QuoteBomPackageReference reference = referenceMapper.selectById(task.getPackageReferenceId());
    if (reference == null || !Objects.equals(reference.getActiveFlag(), ACTIVE)) {
      throw invalid("产品任务关联的包装草稿不存在或已失效");
    }
    return reference;
  }

  private List<QuoteBomPackageReferenceDetail> details(Long referenceId) {
    if (referenceId == null) return List.of();
    return safe(detailMapper.selectList(
        Wrappers.<QuoteBomPackageReferenceDetail>lambdaQuery()
            .eq(QuoteBomPackageReferenceDetail::getPackageReferenceId, referenceId)
            .eq(QuoteBomPackageReferenceDetail::getSelectedFlag, ACTIVE)
            .orderByAsc(QuoteBomPackageReferenceDetail::getLineNo)
            .orderByAsc(QuoteBomPackageReferenceDetail::getId)));
  }

  private boolean referenceInScope(
      QuoteBomPackageReference reference, QuoteCollaborationProductTask task) {
    if (reference == null || reference.getPreparationId() == null) return false;
    QuoteBomPreparationRecord preparation = preparationMapper.selectById(reference.getPreparationId());
    if (preparation == null
        || !Objects.equals(trimToNull(preparation.getPriceOrgCode()), trimToNull(task.getPriceOrgCode()))
        || !Objects.equals(trimToNull(preparation.getMaterialOrganizationCode()), trimToNull(task.getMaterialOrgCode()))) {
      return false;
    }
    OaFormItem item = oaFormItemMapper.selectById(reference.getOaFormItemId());
    return item != null && Objects.equals(
        trimToNull(item.getBusinessUnitType()), trimToNull(task.getBusinessUnitType()));
  }

  private boolean snapshotInScope(
      PackageComponentSnapshot snapshot, QuoteCollaborationProductTask task) {
    return snapshot != null
        && "NORMAL".equals(snapshot.getStatus())
        && Objects.equals(trimToNull(snapshot.getPeriodMonth()), trimToNull(task.getAccountingMonth()))
        && Objects.equals(trimToNull(snapshot.getPriceOrgCode()), trimToNull(task.getPriceOrgCode()))
        && Objects.equals(trimToNull(snapshot.getBusinessUnitType()), trimToNull(task.getBusinessUnitType()));
  }

  private int snapshotLineCount(PackageComponentSnapshot snapshot) {
    if (snapshot == null || snapshot.getId() == null) return 0;
    Long value = snapshotDetailMapper.selectCount(
        Wrappers.<com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail>lambdaQuery()
            .eq(com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail::getSnapshotId,
                snapshot.getId()));
    return value == null ? 0 : Math.toIntExact(value);
  }

  private int selectedDetailCount(Long referenceId) {
    Long value = detailMapper.selectCount(
        Wrappers.<QuoteBomPackageReferenceDetail>lambdaQuery()
            .eq(QuoteBomPackageReferenceDetail::getPackageReferenceId, referenceId)
            .eq(QuoteBomPackageReferenceDetail::getSelectedFlag, ACTIVE));
    return value == null ? 0 : Math.toIntExact(value);
  }

  private void applyTargetContext(
      QuoteBomPackageReferenceDetail row,
      QuoteCollaborationProductTask task,
      Long preparationId,
      QuoteCollaborationQuoteLink owner,
      int lineNo) {
    row.setPreparationId(preparationId);
    row.setOaNo(owner.getOaNo());
    row.setOaFormItemId(owner.getOaFormItemId());
    row.setBareProductCode(task.getProductCode());
    row.setLineNo(lineNo);
  }

  private void copySourceFields(
      QuoteBomPackageReferenceDetail source,
      QuoteBomPackageReferenceDetail target) {
    target.setReferenceFinishedCode(source.getReferenceFinishedCode());
    target.setSourceTopProductCode(source.getSourceTopProductCode());
    target.setSnapshotId(source.getSnapshotId());
    target.setSnapshotDetailId(source.getSnapshotDetailId());
    target.setPackageParentCode(source.getPackageParentCode());
    target.setPackageParentName(source.getPackageParentName());
    target.setPackageParentSpec(source.getPackageParentSpec());
    target.setPackageParentModel(source.getPackageParentModel());
    target.setPackageParentDrawingNo(source.getPackageParentDrawingNo());
    target.setPackageParentShapeAttr(source.getPackageParentShapeAttr());
    target.setPackageParentMainCategoryCode(source.getPackageParentMainCategoryCode());
    target.setPackageParentUnit(source.getPackageParentUnit());
    target.setPackageParentCodeInReferenceBom(source.getPackageParentCodeInReferenceBom());
    target.setPackageQtyPerParent(currentPackageQty(source));
    target.setPackageQtyPerTop(currentPackageQty(source));
    target.setPackageParentBaseQty(firstNonNull(
        source.getAdjustedPackageParentBaseQty(), source.getPackageParentBaseQty(), ONE));
    target.setPackageMaterialCode(source.getPackageMaterialCode());
    target.setPackageMaterialName(source.getPackageMaterialName());
    target.setPackageMaterialSpec(source.getPackageMaterialSpec());
    target.setPackageMaterialModel(source.getPackageMaterialModel());
    target.setPackageMaterialDrawingNo(source.getPackageMaterialDrawingNo());
    target.setPackageMaterialShapeAttr(source.getPackageMaterialShapeAttr());
    target.setPackageMaterialMainCategoryCode(source.getPackageMaterialMainCategoryCode());
    target.setPackageMaterialUnit(source.getPackageMaterialUnit());
    target.setChildQtyPerParent(currentChildQty(source));
    target.setChildQtyPerTop(source.getQtyPerTop());
    target.setChildParentBaseQty(firstNonNull(
        source.getAdjustedChildParentBaseQty(), source.getChildParentBaseQty(), ONE));
    target.setQtyPerTop(source.getQtyPerTop());
    target.setUnit(firstText(source.getUnit(), source.getPackageMaterialUnit()));
    target.setSourceRawHierarchyId(source.getSourceRawHierarchyId());
    target.setSourceU9BomId(source.getSourceU9BomId());
    target.setSourceParentCode(source.getSourceParentCode());
    target.setSourcePath(source.getSourcePath());
    target.setRemark(source.getRemark());
  }

  private boolean requestChanged(
      TechnicalPackageDraftRequest.Line request,
      QuoteBomPackageReferenceDetail source) {
    return !Objects.equals(trimToNull(request.packageParentCode()), trimToNull(source.getPackageParentCode()))
        || !Objects.equals(trimToNull(request.packageMaterialCode()), trimToNull(source.getPackageMaterialCode()))
        || changed(request.packageQtyPerTop(), currentPackageQty(source))
        || changed(request.childQtyPerParent(), currentChildQty(source));
  }

  private String sourceMode(QuoteBomPackageReference reference) {
    String remark = trimToNull(reference.getRemark());
    if (remark != null && remark.contains("SOURCE_MODE=" + MODE_PACKAGE_PARENT)) {
      return MODE_PACKAGE_PARENT;
    }
    return MODE_QUOTED_PRODUCT;
  }

  private String sourceLabel(QuoteBomPackageReference reference) {
    return MODE_PACKAGE_PARENT.equals(sourceMode(reference))
        ? "包装目件 " + firstText(reference.getSourceTopProductCode(), reference.getReferenceFinishedCode())
        : "已审核报价产品 " + firstText(reference.getReferenceFinishedCode(), reference.getSourceTopProductCode());
  }

  private boolean matchesReference(QuoteBomPackageReference row, String query) {
    return matches(query, row.getQuoteProductCode(), row.getReferenceFinishedCode(),
        row.getSourceTopProductCode(), row.getBareProductCode());
  }

  private boolean matches(String query, String... values) {
    if (query == null) return true;
    String needle = query.toLowerCase(Locale.ROOT);
    for (String value : values) {
      if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) return true;
    }
    return false;
  }

  private String requireMode(String value) {
    String mode = requiredText(value, "包装来源方式").toUpperCase(Locale.ROOT);
    if (!Set.of(MODE_QUOTED_PRODUCT, MODE_PACKAGE_PARENT).contains(mode)) {
      throw invalid("不支持的包装来源方式：" + value);
    }
    return mode;
  }

  private Integer requireVersion(Integer value) {
    if (value == null || value <= 0) throw invalid("expectedTaskVersion不能为空");
    return value;
  }

  private void requireSameVersion(QuoteCollaborationProductTask task, Integer version) {
    if (!Objects.equals(task.getTaskVersion(), version)) throw versionConflict();
  }

  private Long requireId(Long value, String label) {
    if (value == null || value <= 0) throw invalid(label + "必须为正数");
    return value;
  }

  private String requiredText(String value, String label) {
    String normalized = trimToNull(value);
    if (normalized == null) throw invalid(label + "不能为空");
    return normalized;
  }

  private BigDecimal positive(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) throw invalid(label + "必须大于0");
    return value.stripTrailingZeros();
  }

  private BigDecimal currentPackageQty(QuoteBomPackageReferenceDetail row) {
    return firstNonNull(row.getAdjustedPackageQtyPerTop(), row.getPackageQtyPerTop(), ONE);
  }

  private BigDecimal currentChildQty(QuoteBomPackageReferenceDetail row) {
    return firstNonNull(row.getAdjustedChildQtyPerParent(), row.getChildQtyPerParent());
  }

  private boolean changed(BigDecimal first, BigDecimal second) {
    return first != null && (second == null || first.compareTo(second) != 0);
  }

  private BigDecimal firstNonNull(BigDecimal... values) {
    for (BigDecimal value : values) if (value != null) return value;
    return null;
  }

  private String firstText(String... values) {
    for (String value : values) if (StringUtils.hasText(value)) return value.trim();
    return null;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private CollaborationScope scope(QuoteCollaborationProductTask task) {
    return new CollaborationScope(task.getBusinessUnitType(), task.getApplicableOrgCode());
  }

  private String currentBusinessUnit() {
    return CollaborationScope.requireBusinessUnit(BusinessUnitContext.getCurrentBusinessUnitType());
  }

  private void requireOne(int affected, String action) {
    if (affected != 1) throw invalid(action + "失败");
  }

  private <T> List<T> safe(List<T> rows) {
    return rows == null ? List.of() : rows;
  }

  private CollaborationDomainException notFound() {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.TASK_NOT_FOUND, "技术任务不存在或不属于当前登录人");
  }

  private CollaborationDomainException invalid(String message) {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.STATE_TRANSITION_INVALID, message);
  }

  private CollaborationDomainException versionConflict() {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.TASK_VERSION_CONFLICT, "任务版本已变化，请刷新页面后重试");
  }
}
