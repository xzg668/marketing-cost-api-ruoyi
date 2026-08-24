package com.sanhua.marketingcost.service.collaboration;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.QuoteBomSupplementDetail;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementDetailMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteCollaborationProductTaskMapper;
import com.sanhua.marketingcost.service.collaboration.CollaborationActions.ProductAction;
import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 电子图库回取后的本地原子写入；远程 HTTP 调用不在本事务中。 */
@Service
public class ElectronicBomVerificationPersistenceService {

  private final QuoteCollaborationTaskRepository repository;
  private final QuoteBomSupplementVersionMapper versionMapper;
  private final QuoteBomSupplementDetailMapper detailMapper;
  private final QuoteCollaborationProductTaskMapper taskMapper;
  private final ApprovedResultFingerprints fingerprints;
  private final CollaborationProductStateService stateService;

  public ElectronicBomVerificationPersistenceService(
      QuoteCollaborationTaskRepository repository,
      QuoteBomSupplementVersionMapper versionMapper,
      QuoteBomSupplementDetailMapper detailMapper,
      QuoteCollaborationProductTaskMapper taskMapper,
      ApprovedResultFingerprints fingerprints,
      CollaborationProductStateService stateService) {
    this.repository = repository;
    this.versionMapper = versionMapper;
    this.detailMapper = detailMapper;
    this.taskMapper = taskMapper;
    this.fingerprints = fingerprints;
    this.stateService = stateService;
  }

  @Transactional
  public FailureResult persistFailure(
      Long taskId,
      Integer expectedVersion,
      CollaborationPrincipal principal,
      CollaborationScope scope,
      List<ElectronicBomValidationIssue> issues) {
    QuoteCollaborationProductTask task = currentOwned(taskId, expectedVersion, principal, scope);
    Long oaFormItemId = ownerItemId(task, scope);
    QuoteCollaborationProductTask fingerprintTask = task;
    List<GapUpsertCommand> commands = safeIssues(issues).stream()
        .map(issue -> toBomGap(fingerprintTask, oaFormItemId, issue)).toList();
    repository.synchronizeGaps(task.getId(), scope, commands, principal.actor());
    task = repository.updateValidationResult(task.getId(), task.getTaskVersion(),
        CollaborationCodes.ValidationStatus.FAILED.code(), principal.userId(), scope,
        principal.actor());
    task = stateService.transition(task.getId(), task.getTaskVersion(), scope,
        ProductAction.FAIL_TECH_VALIDATION, principal).task();
    return new FailureResult(task, commands.size());
  }

  @Transactional
  public VerifiedResult persistVerifiedBom(
      Long taskId,
      Integer expectedVersion,
      CollaborationPrincipal principal,
      CollaborationScope scope,
      ValidatedElectronicBom bom) {
    QuoteCollaborationProductTask task = currentOwned(taskId, expectedVersion, principal, scope);
    if (task.getSupplementVersionId() == null) {
      throw invalid("当前任务没有可替换的BOM草稿版本");
    }
    QuoteBomSupplementVersion version = versionMapper.selectById(task.getSupplementVersionId());
    if (version == null || !Objects.equals(task.getPreparationId(), version.getPreparationId())) {
      throw invalid("当前BOM草稿版本不存在或准备记录不一致");
    }
    List<QuoteBomSupplementDetail> details = buildDetails(version, task, bom);
    String fingerprint = fingerprints.fullBom(details);
    detailMapper.delete(Wrappers.<QuoteBomSupplementDetail>lambdaQuery()
        .eq(QuoteBomSupplementDetail::getSupplementVersionId, version.getId()));
    for (QuoteBomSupplementDetail detail : details) {
      if (detailMapper.insert(detail) != 1) throw invalid("电子图库BOM明细保存失败");
    }
    version.setBomSource("ELECTRONIC_DRAWING");
    version.setVersionStatus("DRAFT");
    version.setEffectiveFrom(bom.effectiveFrom());
    version.setEffectiveTo(bom.effectiveTo());
    version.setUpdatedAt(LocalDateTime.now());
    if (versionMapper.updateById(version) != 1) throw invalid("电子图库BOM版本保存失败");
    int updated = taskMapper.attachVerifiedElectronicBom(task.getId(), task.getTaskVersion(),
        fingerprint, principal.userId(), scope.businessUnitType(), scope.applicableOrgCode(),
        principal.userId(), principal.userName());
    if (updated != 1) throw versionConflict();
    QuoteCollaborationProductTask refreshed = repository.findProductTaskById(task.getId(), scope)
        .orElseThrow(() -> invalid("电子图库BOM保存后无法读取任务"));
    return new VerifiedResult(refreshed, fingerprint, details.size());
  }

  @Transactional
  public PriceScanResult persistPriceScan(
      Long taskId,
      Integer expectedVersion,
      CollaborationPrincipal principal,
      CollaborationScope scope,
      CollaborationPriceScanResult scan) {
    QuoteCollaborationProductTask task = currentOwned(taskId, expectedVersion, principal, scope);
    if (!StringUtils.hasText(task.getElectronicBomFingerprint())) {
      throw invalid("请先完成电子图库BOM回取校验");
    }
    if (scan == null || scan.status() == CollaborationPriceScanResult.Status.ERROR) {
      return new PriceScanResult(task, 0, false);
    }
    if (scan.status() != CollaborationPriceScanResult.Status.READY
        && scan.status() != CollaborationPriceScanResult.Status.GAPS) {
      throw invalid("电子图库BOM完成后的价格检查状态不正确：" + scan.status());
    }
    String productCode = task.getProductCode();
    Long oaFormItemId = ownerItemId(task, scope);
    List<GapUpsertCommand> priceGaps = scan.status() == CollaborationPriceScanResult.Status.GAPS
        ? scan.gaps().stream()
            .map(gap -> CollaborationPriceGapCommandFactory.create(
                oaFormItemId, productCode, gap))
            .toList()
        : List.of();
    repository.synchronizeGaps(task.getId(), scope, priceGaps, principal.actor());
    int gapCount = priceGaps.size();
    int updated = taskMapper.applyElectronicBomPriceScan(task.getId(), task.getTaskVersion(),
        gapCount > 0 ? 1 : 0, gapCount,
        gapCount > 0 ? CollaborationCodes.ValidationStatus.NOT_CHECKED.code()
            : CollaborationCodes.ValidationStatus.PASSED.code(),
        principal.userId(), scope.businessUnitType(), scope.applicableOrgCode(),
        principal.userId(), principal.userName());
    if (updated != 1) throw versionConflict();
    task = repository.findProductTaskById(task.getId(), scope)
        .orElseThrow(() -> invalid("价格检查后无法读取任务"));
    if (gapCount > 0) {
      task = stateService.transition(task.getId(), task.getTaskVersion(), scope,
          ProductAction.CONTINUE_PRICE_AFTER_BOM, principal).task();
    }
    return new PriceScanResult(task, gapCount, gapCount > 0);
  }

  private Long ownerItemId(
      QuoteCollaborationProductTask task, CollaborationScope scope) {
    Long linkedItemId = repository.findLinksByProductTask(task.getId(), scope).stream()
        .filter(link -> Integer.valueOf(1).equals(link.getActiveFlag()))
        .map(com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink::getOaFormItemId)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);
    if (linkedItemId != null) {
      return linkedItemId;
    }
    QuoteBomSupplementVersion version = task.getSupplementVersionId() == null
        ? null : versionMapper.selectById(task.getSupplementVersionId());
    if (version != null && version.getOaFormItemId() != null) {
      return version.getOaFormItemId();
    }
    throw invalid("产品协作任务缺少活动报价关联");
  }

  private List<QuoteBomSupplementDetail> buildDetails(
      QuoteBomSupplementVersion version,
      QuoteCollaborationProductTask task,
      ValidatedElectronicBom bom) {
    LocalDateTime now = LocalDateTime.now();
    List<QuoteBomSupplementDetail> result = new ArrayList<>();
    for (int index = 0; index < bom.nodes().size(); index++) {
      ValidatedElectronicBom.Node node = bom.nodes().get(index);
      QuoteBomSupplementDetail detail = new QuoteBomSupplementDetail();
      detail.setSupplementVersionId(version.getId());
      detail.setPreparationId(version.getPreparationId());
      // 旧 task_id 专指 lp_bom_supplement_task，QCBP 产品任务只通过 supplement_version_id 关联。
      detail.setTaskId(null);
      detail.setOaNo(version.getOaNo());
      detail.setOaFormItemId(version.getOaFormItemId());
      detail.setQuoteProductCode(version.getQuoteProductCode());
      detail.setSupplementScope(version.getSupplementScope());
      detail.setLineNo(index + 1);
      detail.setLevel(node.level());
      detail.setParentCode(node.parentMaterialCode());
      detail.setMaterialCode(node.materialCode());
      detail.setMaterialName(node.materialName());
      detail.setMaterialSpec(node.materialSpec());
      detail.setMaterialModel(node.materialModel());
      detail.setDrawingNo(node.drawingNo());
      detail.setShapeAttr(storageNature(node.materialNature()));
      detail.setBomPurpose(bom.bomPurpose());
      detail.setBomVersion(bom.sourceVersion());
      detail.setQtyPerParent(node.quantityPerParent());
      detail.setQtyPerTop(node.quantityToTop());
      detail.setParentBaseQty(java.math.BigDecimal.ONE);
      detail.setUnit(node.unit());
      detail.setPath(node.path());
      detail.setSortSeq(node.sortSeq());
      detail.setManualFlag(0);
      detail.setRemark("ELECTRONIC_DRAWING:" + bom.sourceSystem());
      detail.setCreatedAt(now);
      detail.setUpdatedAt(now);
      result.add(detail);
    }
    return List.copyOf(result);
  }

  private QuoteCollaborationProductTask currentOwned(
      Long taskId,
      Integer expectedVersion,
      CollaborationPrincipal principal,
      CollaborationScope scope) {
    QuoteCollaborationProductTask task = repository.findProductTaskById(taskId, scope)
        .orElseThrow(() -> new CollaborationDomainException(
            CollaborationDomainErrorCode.TASK_NOT_FOUND, "技术任务不存在"));
    if (!Objects.equals(task.getCurrentAssigneeUserId(), principal.userId())) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_ASSIGNEE_MISMATCH, "当前任务不由你处理");
    }
    if (!Objects.equals(task.getTaskVersion(), expectedVersion)) throw versionConflict();
    if (!List.of("BOM_IN_PROGRESS", "RETURNED_TO_TECH").contains(task.getTaskStatus())) {
      throw invalid("当前任务不在可回取BOM的处理阶段");
    }
    return task;
  }

  private GapUpsertCommand toBomGap(
      QuoteCollaborationProductTask task,
      Long oaFormItemId,
      ElectronicBomValidationIssue issue) {
    String businessPosition = StringUtils.hasText(issue.bomPath())
        ? issue.bomPath() : issue.nodeKey();
    String fingerprint = CollaborationGapFingerprintFactory.create(
        oaFormItemId,
        task.getAccountingMonth(),
        "BOM",
        "ELECTRONIC_BOM_VALIDATION",
        task.getProductCode(),
        businessPosition,
        "ELECTRONIC_DRAWING|" + text(issue.code()));
    return new GapUpsertCommand("BOM", "ELECTRONIC_BOM_VALIDATION",
        "ELECTRONIC_DRAWING", null, fingerprint, issue.nodeKey(), issue.bomPath(),
        task.getProductCode(), task.getProductName(), task.getProductSpec(),
        task.getProductModel(), "NORMAL", null, issue.code(), issue.message(),
        null, null, task.getAccountingMonth(), task.getApplicableOrgCode());
  }

  private static List<ElectronicBomValidationIssue> safeIssues(
      List<ElectronicBomValidationIssue> issues) {
    return issues == null || issues.isEmpty()
        ? List.of(new ElectronicBomValidationIssue(null, null,
            "ELECTRONIC_BOM_VALIDATION_FAILED", "电子图库BOM校验失败"))
        : List.copyOf(issues);
  }

  private static String storageNature(String nature) {
    return switch (nature == null ? "" : nature) {
      case "PURCHASE" -> "采购件";
      case "MANUFACTURE" -> "制造件";
      case "OUTSOURCE" -> "委外件";
      case "VIRTUAL_PACKAGE" -> "虚拟件（包装）";
      default -> nature;
    };
  }

  private static String firstText(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static CollaborationDomainException invalid(String message) {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.STATE_TRANSITION_INVALID, message);
  }

  private static CollaborationDomainException versionConflict() {
    return new CollaborationDomainException(
        CollaborationDomainErrorCode.TASK_VERSION_CONFLICT, "任务版本已变化，请刷新后重试");
  }

  public record FailureResult(QuoteCollaborationProductTask task, int issueCount) {}
  public record VerifiedResult(
      QuoteCollaborationProductTask task, String fingerprint, int nodeCount) {}
  public record PriceScanResult(
      QuoteCollaborationProductTask task, int gapCount, boolean continuedToPrice) {}
}
