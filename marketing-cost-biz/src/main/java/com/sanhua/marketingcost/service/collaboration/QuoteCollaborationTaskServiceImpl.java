package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCollaborationApprovedResult;
import com.sanhua.marketingcost.entity.QuoteCollaborationProductTask;
import com.sanhua.marketingcost.entity.QuoteCollaborationQuoteLink;
import com.sanhua.marketingcost.entity.QuoteCollaborationTask;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.GapCategory;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.MaterialRole;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.PrimaryScope;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ProductTaskStatus;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.QuoteLinkStatus;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.QuoteLinkType;
import com.sanhua.marketingcost.service.collaboration.CollaborationCodes.ValidationStatus;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanAction;
import com.sanhua.marketingcost.service.collaboration.scan.QuoteCollaborationScanResult;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.time.LocalDateTime;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 发起补录的事务入口：重新扫描后只创建一个活动技术任务，其余报价只建立关联。 */
@Service
public class QuoteCollaborationTaskServiceImpl {

  private static final String SOURCE_SYSTEM = "QUOTE_COST";

  private final QuoteCollaborationScanService scanService;
  private final QuoteCollaborationTaskRepository repository;
  private final QuoteCollaborationReviewRepository reviewRepository;
  private final OaFormItemMapper itemMapper;
  private final OaFormMapper formMapper;
  private final CollaborationTaskLogService taskLogService;

  public QuoteCollaborationTaskServiceImpl(
      QuoteCollaborationScanService scanService,
      QuoteCollaborationTaskRepository repository,
      QuoteCollaborationReviewRepository reviewRepository,
      OaFormItemMapper itemMapper,
      OaFormMapper formMapper,
      CollaborationTaskLogService taskLogService) {
    this.scanService = scanService;
    this.repository = repository;
    this.reviewRepository = reviewRepository;
    this.itemMapper = itemMapper;
    this.formMapper = formMapper;
    this.taskLogService = taskLogService;
  }

  @Transactional(isolation = Isolation.READ_COMMITTED)
  public QuoteCollaborationStartResult start(QuoteCollaborationStartCommand command) {
    return start(command, false);
  }

  /** 一键核算后台入口；允许用系统账号(0)记录自动派单，但仍必须明确匹配技术负责人。 */
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public QuoteCollaborationStartResult startAutomatically(
      QuoteCollaborationStartCommand command) {
    return start(command, true);
  }

  private QuoteCollaborationStartResult start(
      QuoteCollaborationStartCommand command, boolean allowSystemActor) {
    requireBaseCommand(command, allowSystemActor);
    QuoteCollaborationScanResult scan = StringUtils.hasText(command.accountingMonth())
        ? scanService.scanQuoteItem(command.oaFormItemId(), command.accountingMonth())
        : scanService.scanQuoteItem(command.oaFormItemId());
    if (scan.action() == QuoteCollaborationScanAction.NO_COLLABORATION_REQUIRED) {
      return noCollaboration(scan);
    }
    if (scan.action() == QuoteCollaborationScanAction.REUSE_APPROVED_RESULT) {
      return reuseApprovedResult(scan, command);
    }
    if (scan.action() != QuoteCollaborationScanAction.CREATE_COLLABORATION
        && scan.action() != QuoteCollaborationScanAction.LINK_ACTIVE_TASK) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          firstText(scan.message(), "当前扫描结果不允许发起补录"));
    }

    QuoteSource source = loadSource(command.oaFormItemId(), scan);
    CollaborationScope scope = new CollaborationScope(
        scan.businessUnitType(), scan.priceOrgCode());
    String activeLockKey = CollaborationActiveLockKeyFactory.create(
        scan.productCode(), source.item().getSunlModel(), temporaryProductKey(source.item(), scan),
        scope);

    Optional<QuoteCollaborationProductTask> active = findScannedOrLockedActiveTask(
        scan, activeLockKey, scope);
    if (active.isPresent()) {
      return linkOrReplay(source, scan, scope, active.get(), command);
    }
    if (scan.action() == QuoteCollaborationScanAction.LINK_ACTIVE_TASK) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.IDEMPOTENCY_CONFLICT,
          "原活动任务状态已经变化，请刷新后重试");
    }
    requireTechnician(command);

    QuoteCollaborationTask master = getOrCreateMaster(source, scan, command);
    QuoteCollaborationProductTask productTask = newProductTask(
        master, source.item(), scan, activeLockKey, command);
    try {
      productTask = repository.saveProductTask(productTask);
    } catch (DataIntegrityViolationException conflict) {
      QuoteCollaborationProductTask concurrent = repository
          .findActiveProductTaskByLockKey(activeLockKey, scope)
          .orElseThrow(() -> conflict);
      return linkOrReplay(source, scan, scope, concurrent, command);
    }

    QuoteLinkType ownerType = scan.approvedResultId() != null
            && scan.requiredScope() == PrimaryScope.PRICE_ONLY
        ? QuoteLinkType.APPROVED_RESULT_REUSE : QuoteLinkType.OWNER;
    QuoteCollaborationQuoteLink owner = repository.saveQuoteLink(
        newLink(source, scan, master, productTask, ownerType));
    repository.incrementOwnedProductCount(master.getId(), master.getBusinessUnitType(), command.actor());
    repository.synchronizeGaps(
        productTask.getId(), scope, initialGaps(scan), command.actor());
    CollaborationNextAction nextAction = initialNextAction(productTask);
    taskLogService.record(productTask, "TECH_TASK_CREATED", "技术协作任务已创建");
    return result(
        CollaborationStartAction.CREATED, productTask, owner, nextAction, false,
        "已生成技术协作任务，由" + command.technicianName().trim() + "处理");
  }

  /** 报价行入口只能编辑自己拥有的技术任务，关联等待行永远只读。 */
  @Transactional(readOnly = true)
  public void requireOwnerQuoteItem(
      Long productTaskId, Long oaFormItemId, CollaborationScope scope) {
    QuoteCollaborationQuoteLink link = repository
        .findActiveLinksByQuoteItem(oaFormItemId, scope).stream().findFirst()
        .orElseThrow(() -> new CollaborationDomainException(
            CollaborationDomainErrorCode.TASK_NOT_FOUND, "当前报价产品没有活动协作关联"));
    if (!productTaskId.equals(link.getProductTaskId())) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.QUOTE_LINK_READ_ONLY,
          "当前报价仅关联原技术任务，不能修改原任务草稿");
    }
    if (QuoteLinkType.OWNER.code().equals(link.getLinkType())) {
      return;
    }
    if (!QuoteLinkType.APPROVED_RESULT_REUSE.code().equals(link.getLinkType())) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.QUOTE_LINK_READ_ONLY,
          "当前报价仅关联原技术任务，不能修改原任务草稿");
    }
    QuoteCollaborationProductTask task = repository.findProductTaskById(
        productTaskId, scope).orElseThrow(() -> new CollaborationDomainException(
            CollaborationDomainErrorCode.TASK_NOT_FOUND, "产品任务不存在"));
    if (PrimaryScope.PRICE_ONLY.code().equals(task.getPrimaryScope())
        && task.getOriginCollaborationId().equals(link.getCollaborationId())) {
      return;
    }
    throw new CollaborationDomainException(
        CollaborationDomainErrorCode.QUOTE_LINK_READ_ONLY,
        "当前报价只复用已审核BOM或包装，不能修改来源任务草稿");
  }

  private QuoteCollaborationStartResult reuseApprovedResult(
      QuoteCollaborationScanResult scan,
      QuoteCollaborationStartCommand command) {
    if (scan.approvedResultId() == null) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          "扫描结果缺少可复用审核结果");
    }
    QuoteSource source = loadSource(command.oaFormItemId(), scan);
    CollaborationScope scope = new CollaborationScope(
        scan.businessUnitType(), scan.priceOrgCode());
    QuoteCollaborationApprovedResult approved = reviewRepository.findApprovedResultById(
        scan.approvedResultId(), scope).orElseThrow(() -> new CollaborationDomainException(
            CollaborationDomainErrorCode.TASK_NOT_FOUND,
            "可复用审核结果不存在或不在当前业务范围"));
    if (!"ACTIVE".equals(approved.getResultStatus())
        || !same(scan.productCode(), approved.getProductCode())
        || !same(scan.priceOrgCode(), approved.getApplicableOrgCode())) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.IDEMPOTENCY_CONFLICT,
          "可复用审核结果在扫描后已变化，请重新检查");
    }
    QuoteCollaborationProductTask sourceTask = repository.findProductTaskById(
        approved.getSourceProductTaskId(), scope).orElseThrow(() ->
        new CollaborationDomainException(
            CollaborationDomainErrorCode.TASK_NOT_FOUND, "可复用结果的来源任务不存在"));
    Optional<QuoteCollaborationQuoteLink> existing = repository.findActiveLinksByQuoteItem(
        source.item().getId(), scope).stream().findFirst();
    if (existing.isPresent()) {
      QuoteCollaborationQuoteLink replay = existing.get();
      if (!QuoteLinkType.APPROVED_RESULT_REUSE.code().equals(replay.getLinkType())
          || !approved.getId().equals(replay.getApprovedResultId())) {
        throw new CollaborationDomainException(
            CollaborationDomainErrorCode.IDEMPOTENCY_CONFLICT,
            "当前报价产品已关联其他协作结果，请刷新后重试");
      }
      return reusedResult(sourceTask, replay, true,
          "已复用审核通过的BOM或包装，本次价格检查通过");
    }

    QuoteCollaborationTask master = getOrCreateMaster(source, scan, command);
    QuoteCollaborationQuoteLink link = newLink(
        source, scan, master, sourceTask, QuoteLinkType.APPROVED_RESULT_REUSE);
    link.setLinkStatus(QuoteLinkStatus.READY.code());
    link.setReadyAt(LocalDateTime.now());
    try {
      link = repository.saveQuoteLink(link);
    } catch (DataIntegrityViolationException conflict) {
      QuoteCollaborationQuoteLink concurrent = repository.findActiveLinksByQuoteItem(
          source.item().getId(), scope).stream().findFirst().orElseThrow(() -> conflict);
      if (!approved.getId().equals(concurrent.getApprovedResultId())) {
        throw new CollaborationDomainException(
            CollaborationDomainErrorCode.IDEMPOTENCY_CONFLICT,
            "并发请求已将当前报价产品关联到其他结果");
      }
      return reusedResult(sourceTask, concurrent, true,
          "已复用审核通过的BOM或包装，本次价格检查通过");
    }
    taskLogService.record(sourceTask, "APPROVED_RESULT_REUSED", "当前报价已复用审核通过的结果");
    return reusedResult(sourceTask, link, false,
        "已复用审核通过的BOM或包装，本次价格检查通过");
  }

  private QuoteCollaborationStartResult linkOrReplay(
      QuoteSource source,
      QuoteCollaborationScanResult scan,
      CollaborationScope scope,
      QuoteCollaborationProductTask active,
      QuoteCollaborationStartCommand command) {
    Optional<QuoteCollaborationQuoteLink> existing = repository
        .findActiveLinksByQuoteItem(source.item().getId(), scope).stream().findFirst();
    if (existing.isPresent()) {
      QuoteCollaborationQuoteLink link = existing.get();
      if (!active.getId().equals(link.getProductTaskId())) {
        throw new CollaborationDomainException(
            CollaborationDomainErrorCode.IDEMPOTENCY_CONFLICT,
            "当前报价产品已关联其他活动任务，请刷新后重试");
      }
      CollaborationStartAction replayAction = QuoteLinkType.OWNER.code().equals(link.getLinkType())
          ? CollaborationStartAction.CREATED : CollaborationStartAction.LINKED_ACTIVE_TASK;
      return result(
          replayAction, active, link, CollaborationNextAction.NONE, true,
          linkedMessage(active));
    }

    QuoteCollaborationTask currentMaster = getOrCreateMaster(source, scan, command);
    QuoteCollaborationQuoteLink linked;
    try {
      linked = repository.saveQuoteLink(newLink(
          source, scan, currentMaster, active, QuoteLinkType.ACTIVE_TASK_LINK));
    } catch (DataIntegrityViolationException conflict) {
      linked = repository.findActiveLinksByQuoteItem(source.item().getId(), scope).stream().findFirst()
          .orElseThrow(() -> conflict);
      if (!active.getId().equals(linked.getProductTaskId())) {
        throw new CollaborationDomainException(
            CollaborationDomainErrorCode.IDEMPOTENCY_CONFLICT,
            "并发请求已将当前报价产品关联到其他任务");
      }
      return result(
          CollaborationStartAction.LINKED_ACTIVE_TASK, active, linked,
          CollaborationNextAction.NONE, true, linkedMessage(active));
    }
    taskLogService.record(active, "TECH_TASK_LINKED", "当前报价已关联正在处理的技术任务");
    return result(
        CollaborationStartAction.LINKED_ACTIVE_TASK, active, linked,
        CollaborationNextAction.NONE, false, linkedMessage(active));
  }

  private QuoteCollaborationTask getOrCreateMaster(
      QuoteSource source,
      QuoteCollaborationScanResult scan,
      QuoteCollaborationStartCommand command) {
    Optional<QuoteCollaborationTask> latest = repository.findLatestTaskByForm(
        source.form().getId(), scan.businessUnitType());
    if (latest.isPresent() && "WAIT_TECH".equals(latest.get().getMasterStatus())) {
      return latest.get();
    }
    int nextRound = latest.map(task -> task.getRoundNo() + 1).orElse(1);
    QuoteCollaborationTask task = new QuoteCollaborationTask();
    task.setOaFormId(source.form().getId());
    task.setOaNo(scan.oaNo());
    task.setRoundNo(nextRound);
    task.setBusinessUnitType(scan.businessUnitType());
    task.setAccountingMonth(scan.accountingMonth());
    task.setSourceSystem(SOURCE_SYSTEM);
    task.setMasterStatus("WAIT_TECH");
    task.setFinanceReviewerUserId(command.financeReviewerUserId());
    task.setFinanceReviewerName(trimToNull(command.financeReviewerName()));
    task.setOwnedProductCount(0);
    task.setTechSubmittedCount(0);
    task.setReturnedProductCount(0);
    task.setReadyProductCount(0);
    auditCreate(task, command.actor());
    try {
      return repository.saveTask(task);
    } catch (DataIntegrityViolationException conflict) {
      return repository.findLatestTaskByForm(
              source.form().getId(), scan.businessUnitType())
          .filter(existing -> existing.getRoundNo().equals(nextRound))
          .orElseThrow(() -> conflict);
    }
  }

  private QuoteCollaborationProductTask newProductTask(
      QuoteCollaborationTask master,
      OaFormItem item,
      QuoteCollaborationScanResult scan,
      String activeLockKey,
      QuoteCollaborationStartCommand command) {
    PrimaryScope primaryScope = requirePrimaryScope(scan);
    QuoteCollaborationProductTask task = new QuoteCollaborationProductTask();
    task.setOriginCollaborationId(master.getId());
    task.setAccountingMonth(scan.accountingMonth());
    task.setBusinessUnitType(scan.businessUnitType());
    task.setApplicableOrgCode(scan.priceOrgCode());
    task.setMaterialOrgCode(scan.materialOrganizationCode());
    task.setPriceOrgCode(scan.priceOrgCode());
    task.setProductCode(trimToNull(scan.productCode()));
    task.setTemporaryProductKey(temporaryProductKey(item, scan));
    task.setProductName(trimToNull(item.getProductName()));
    task.setProductSpec(trimToNull(item.getSpec()));
    task.setProductModel(trimToNull(item.getSunlModel()));
    task.setProductForm(scan.productForm().code());
    task.setPrimaryScope(primaryScope.code());
    task.setNeedBom(primaryScope == PrimaryScope.FULL_BOM ? 1 : 0);
    task.setNeedPackage(primaryScope == PrimaryScope.BARE_PACKAGE ? 1 : 0);
    task.setNeedPrice(primaryScope == PrimaryScope.PRICE_ONLY ? 1 : 0);
    task.setOpenGapCount(initialOpenGapCount(scan));
    task.setTaskStatus(ProductTaskStatus.WAIT_TECH.code());
    task.setOriginalTechnicianUserId(command.technicianUserId());
    task.setOriginalTechnicianName(command.technicianName().trim());
    task.setCurrentAssigneeUserId(command.technicianUserId());
    task.setCurrentAssigneeName(command.technicianName().trim());
    task.setTaskVersion(1);
    task.setActiveLockKey(activeLockKey);
    task.setActiveFlag(1);
    task.setLastValidationStatus(ValidationStatus.NOT_CHECKED.code());
    auditCreate(task, command.actor());
    return task;
  }

  private QuoteCollaborationQuoteLink newLink(
      QuoteSource source,
      QuoteCollaborationScanResult scan,
      QuoteCollaborationTask master,
      QuoteCollaborationProductTask task,
      QuoteLinkType linkType) {
    QuoteCollaborationQuoteLink link = new QuoteCollaborationQuoteLink();
    link.setProductTaskId(task.getId());
    link.setCollaborationId(master.getId());
    link.setOaFormId(source.form().getId());
    link.setOaFormItemId(source.item().getId());
    link.setOaNo(scan.oaNo());
    link.setProductCode(scan.productCode());
    link.setAccountingMonth(scan.accountingMonth());
    link.setApplicableOrgCode(scan.priceOrgCode());
    link.setLinkType(linkType.code());
    link.setApprovedResultId(scan.approvedResultId());
    link.setLinkStatus(QuoteLinkStatus.WAIT_SOURCE.code());
    link.setRepriceGapCount(0);
    link.setActiveFlag(1);
    link.setActiveLinkKey("OA_ITEM:" + source.item().getId());
    return link;
  }

  private List<GapUpsertCommand> initialGaps(QuoteCollaborationScanResult scan) {
    if (scan.requiredScope() == PrimaryScope.PRICE_ONLY) {
      return scan.price().gaps().stream()
          .map(gap -> CollaborationPriceGapCommandFactory.create(
              scan.oaFormItemId(), scan.productCode(), scan.accountingMonth(), gap))
          .toList();
    }
    String category = scan.requiredScope() == PrimaryScope.BARE_PACKAGE
        ? GapCategory.PACKAGE.code() : GapCategory.BOM.code();
    String type = scan.requiredScope() == PrimaryScope.BARE_PACKAGE
        ? "MISSING_PACKAGE" : "MISSING_BOM";
    return List.of(new GapUpsertCommand(
        category, type, "SCAN", scan.oaFormItemId(),
        CollaborationGapFingerprintFactory.create(
            scan.oaFormItemId(), scan.accountingMonth(), category, type,
            scan.productCode(), scan.productCode(),
            firstText(scan.authoritativeBomSource(), "SCAN") + "|"
                + firstText(scan.bomVersion(), "NO_VERSION")),
        null, null, scan.productCode(), null, null, null,
        MaterialRole.NORMAL.code(), null, type,
        firstText(scan.message(), "当前报价产品存在" + category + "缺口"),
        null, null, scan.accountingMonth(), scan.priceOrgCode()));
  }

  private QuoteCollaborationStartResult result(
      CollaborationStartAction action,
      QuoteCollaborationProductTask task,
      QuoteCollaborationQuoteLink link,
      CollaborationNextAction nextAction,
      boolean replay,
      String message) {
    return new QuoteCollaborationStartResult(
        action, task.getId(), task.getProductTaskNo(), link.getId(), task.getTaskStatus(),
        task.getCurrentAssigneeUserId(), task.getCurrentAssigneeName(), nextAction,
        task.getTaskVersion(), replay, message);
  }

  private QuoteCollaborationStartResult noCollaboration(QuoteCollaborationScanResult scan) {
    return new QuoteCollaborationStartResult(
        CollaborationStartAction.NO_COLLABORATION_REQUIRED, null, null, null,
        "READY", null, null, CollaborationNextAction.NONE, null, false,
        firstText(scan.message(), "BOM和价格均已准备，无需补录"));
  }

  private QuoteCollaborationStartResult reusedResult(
      QuoteCollaborationProductTask sourceTask,
      QuoteCollaborationQuoteLink link,
      boolean replay,
      String message) {
    return new QuoteCollaborationStartResult(
        CollaborationStartAction.REUSED_APPROVED_RESULT,
        sourceTask.getId(), sourceTask.getProductTaskNo(), link.getId(),
        QuoteLinkStatus.READY.code(), null, null, CollaborationNextAction.NONE,
        sourceTask.getTaskVersion(), replay, message);
  }

  private QuoteSource loadSource(Long itemId, QuoteCollaborationScanResult scan) {
    OaFormItem item = itemMapper.selectById(itemId);
    if (item == null) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_NOT_FOUND, "报价产品行不存在");
    }
    OaForm form = formMapper.selectById(item.getOaFormId());
    if (form == null) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.TASK_NOT_FOUND, "报价单不存在");
    }
    if (!form.getId().equals(item.getOaFormId())
        || !same(scan.oaNo(), form.getOaNo())
        || !sameNullable(scan.productCode(), item.getMaterialNo())) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.IDEMPOTENCY_CONFLICT,
          "报价产品在扫描后已变化，请重新检查后发起");
    }
    return new QuoteSource(form, item);
  }

  private PrimaryScope requirePrimaryScope(QuoteCollaborationScanResult scan) {
    if (scan.requiredScope() == null) {
      throw new CollaborationDomainException(
          CollaborationDomainErrorCode.STATE_TRANSITION_INVALID,
          "扫描结果缺少需要处理的范围");
    }
    return scan.requiredScope();
  }

  private CollaborationNextAction initialNextAction(QuoteCollaborationProductTask task) {
    if (Integer.valueOf(1).equals(task.getNeedBom())) {
      return CollaborationNextAction.SUPPLEMENT_BOM;
    }
    if (Integer.valueOf(1).equals(task.getNeedPackage())) {
      return CollaborationNextAction.SUPPLEMENT_PACKAGE;
    }
    return CollaborationNextAction.SUPPLEMENT_PRICE;
  }

  /**
   * 扫描结果可能关联的是升级前生成的 V1 锁键。优先按扫描返回的任务主键复核，
   * 再查当前 V2 锁键，避免上线后把同一产品的历史活动任务误判为不存在。
   */
  private Optional<QuoteCollaborationProductTask> findScannedOrLockedActiveTask(
      QuoteCollaborationScanResult scan,
      String activeLockKey,
      CollaborationScope scope) {
    if (scan.activeProductTaskId() != null) {
      Optional<QuoteCollaborationProductTask> scanned = repository.findProductTaskById(
          scan.activeProductTaskId(), scope);
      if (scanned.isPresent() && Integer.valueOf(1).equals(scanned.get().getActiveFlag())) {
        return scanned;
      }
    }
    return repository.findActiveProductTaskByLockKey(activeLockKey, scope);
  }

  private int initialOpenGapCount(QuoteCollaborationScanResult scan) {
    return scan.requiredScope() == PrimaryScope.PRICE_ONLY ? scan.price().gapCount() : 1;
  }

  private String temporaryProductKey(OaFormItem item, QuoteCollaborationScanResult scan) {
    if (StringUtils.hasText(scan.productCode())) {
      return null;
    }
    return CollaborationTemporaryProductKeyFactory.fromQuoteProduct(item);
  }

  private static String linkedMessage(QuoteCollaborationProductTask task) {
    return "该产品正在由" + firstText(task.getCurrentAssigneeName(), "原技术人员")
        + "处理，当前报价已关联结果";
  }

  private static void requireBaseCommand(
      QuoteCollaborationStartCommand command, boolean allowSystemActor) {
    if (command == null || command.oaFormItemId() == null || command.oaFormItemId() <= 0) {
      throw new IllegalArgumentException("报价产品行ID必须为正数");
    }
    long minimumActorId = allowSystemActor ? 0 : 1;
    if (command.actor() == null || command.actor().userId() == null
        || command.actor().userId() < minimumActorId) {
      throw new IllegalArgumentException("当前操作人不能为空");
    }
  }

  private static void requireTechnician(QuoteCollaborationStartCommand command) {
    if (command.technicianUserId() == null || command.technicianUserId() <= 0
        || !StringUtils.hasText(command.technicianName())) {
      throw new IllegalArgumentException("技术负责人必须明确选择");
    }
  }

  private static void auditCreate(QuoteCollaborationTask task, CollaborationActor actor) {
    task.setCreatedBy(actor.userId());
    task.setCreatedByName(actor.userName());
    task.setUpdatedBy(actor.userId());
    task.setUpdatedByName(actor.userName());
  }

  private static void auditCreate(QuoteCollaborationProductTask task, CollaborationActor actor) {
    task.setCreatedBy(actor.userId());
    task.setCreatedByName(actor.userName());
    task.setUpdatedBy(actor.userId());
    task.setUpdatedByName(actor.userName());
  }

  private static boolean same(String first, String second) {
    return trimToNull(first) != null && trimToNull(first).equals(trimToNull(second));
  }

  /** 新品允许扫描结果和报价行的料号同时为空，但只允许“两边都空”，不能吞掉真实变化。 */
  private static boolean sameNullable(String first, String second) {
    return Objects.equals(trimToNull(first), trimToNull(second));
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static String firstText(String value, String fallback) {
    return StringUtils.hasText(value) ? value.trim() : fallback;
  }

  private record QuoteSource(OaForm form, OaFormItem item) {}
}
