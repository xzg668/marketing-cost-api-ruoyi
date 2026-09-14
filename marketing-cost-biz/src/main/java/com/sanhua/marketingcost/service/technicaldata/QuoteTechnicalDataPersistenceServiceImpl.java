package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechReviewItem;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteTechnicalDataPersistenceServiceImpl
    implements QuoteTechnicalDataPersistenceService {
  private static final Set<String> MODULE_TYPES = Set.of(
      "PROFILE", "PACKAGE", "AUXILIARY", "SALARY");
  private static final Set<String> COPYABLE_VERSION_STATUSES = Set.of(
      QuoteTechDataVersion.STATUS_SUBMITTED,
      QuoteTechDataVersion.STATUS_RETURNED,
      QuoteTechDataVersion.STATUS_APPROVED);
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataVersionContentCodec contentCodec;

  public QuoteTechnicalDataPersistenceServiceImpl(
      QuoteTechnicalDataRepository repository,
      TechnicalDataVersionContentCodec contentCodec) {
    this.repository = repository;
    this.contentCodec = contentCodec;
  }

  @Override
  @Transactional
  public QuoteTechTask createTask(QuoteTechTask task) {
    Objects.requireNonNull(task, "task");
    task.setTaskNo(required("taskNo", task.getTaskNo()));
    task.setOaNo(required("oaNo", task.getOaNo()));
    task.setAccountingMonth(month(task.getAccountingMonth()));
    task.setBusinessUnitType(required("businessUnitType", task.getBusinessUnitType()));
    task.setApplicableOrgCode(required("applicableOrgCode", task.getApplicableOrgCode()));
    requiredId("oaFormId", task.getOaFormId());
    requiredId("assigneeUserId", task.getAssigneeUserId());
    task.setAssigneeName(required("assigneeName", task.getAssigneeName()));
    task.setTaskStatus(defaultText(task.getTaskStatus(), "PENDING"));
    task.setReviewStatus(defaultText(task.getReviewStatus(), "NOT_STARTED"));
    task.setTaskVersion(defaultNumber(task.getTaskVersion(), 0));
    task.setReviewRound(defaultNumber(task.getReviewRound(), 0));
    task.setActiveFlag(defaultNumber(task.getActiveFlag(), 1));
    task.setActiveLockKey(task.getActiveFlag() == 1
        ? taskActiveLockKey(task.getOaNo(), task.getAccountingMonth(), task.getAssigneeUserId())
        : null);
    return repository.insertTask(task);
  }

  @Override
  @Transactional
  public QuoteTechProduct createProduct(QuoteTechProduct product) {
    Objects.requireNonNull(product, "product");
    requiredId("taskId", product.getTaskId());
    requiredId("oaFormItemId", product.getOaFormItemId());
    product.setQuoteNo(required("quoteNo", product.getQuoteNo()));
    product.setAccountingMonth(month(product.getAccountingMonth()));
    product.setSourceSnapshotJson(required("sourceSnapshotJson", product.getSourceSnapshotJson()));
    product.setSourceFingerprint(required("sourceFingerprint", product.getSourceFingerprint()));
    product.setLevelNo(defaultNumber(product.getLevelNo(), 1));
    product.setProductStatus(defaultText(product.getProductStatus(), "PENDING"));
    product.setRowVersion(defaultNumber(product.getRowVersion(), 0));
    product.setActiveFlag(defaultNumber(product.getActiveFlag(), 1));
    product.setActiveLockKey(product.getActiveFlag() == 1
        ? productActiveLockKey(product.getOaFormItemId(), product.getAccountingMonth())
        : null);
    return repository.insertProduct(product);
  }

  @Override
  @Transactional
  public QuoteTechModule createModule(QuoteTechModule module) {
    Objects.requireNonNull(module, "module");
    requiredId("productId", module.getProductId());
    module.setModuleType(required("moduleType", module.getModuleType()));
    module.setRequirementReasonCode(
        required("requirementReasonCode", module.getRequirementReasonCode()));
    module.setRequirementReason(required("requirementReason", module.getRequirementReason()));
    module.setRequiredFlag(defaultNumber(module.getRequiredFlag(), 1));
    module.setEntryMode(defaultText(module.getEntryMode(), "NONE"));
    module.setModuleStatus(defaultText(
        module.getModuleStatus(), module.getRequiredFlag() == 0 ? "NOT_REQUIRED" : "PENDING"));
    module.setRowVersion(defaultNumber(module.getRowVersion(), 0));
    return repository.insertModule(module);
  }

  @Override
  @Transactional
  public QuoteTechDataVersion createVersion(QuoteTechDataVersion version) {
    normalizeVersion(version);
    return repository.insertVersion(version);
  }

  @Override
  @Transactional
  public QuoteTechDataVersion createDraftWithPackageItems(
      QuoteTechDataVersion version, List<QuoteTechPackageItem> items) {
    QuoteTechDataVersion created = createVersion(version);
    addPackageItems(created.getId(), items);
    return created;
  }

  @Override
  @Transactional
  public QuoteTechReviewItem createReviewItem(QuoteTechReviewItem reviewItem) {
    Objects.requireNonNull(reviewItem, "reviewItem");
    requiredId("taskId", reviewItem.getTaskId());
    requiredId("productId", reviewItem.getProductId());
    requiredId("submittedVersionId", reviewItem.getSubmittedVersionId());
    if (reviewItem.getReviewRound() == null || reviewItem.getReviewRound() <= 0) {
      throw new IllegalArgumentException("reviewRound 必须大于0");
    }
    reviewItem.setModuleType(required("moduleType", reviewItem.getModuleType()));
    reviewItem.setDecision(defaultText(reviewItem.getDecision(), "PENDING"));
    return repository.insertReviewItem(reviewItem);
  }

  @Override
  @Transactional
  public void addPackageItems(Long versionId, List<QuoteTechPackageItem> items) {
    List<QuoteTechPackageItem> values = values(items);
    if (values.isEmpty()) return;
    requireDraft(versionId);
    bindVersion(values, versionId, QuoteTechPackageItem::getVersionId,
        QuoteTechPackageItem::setVersionId);
    requireBatchCount(
        repository.insertPackageItemsIfDraft(versionId, values), values.size(), versionId);
  }

  @Override
  @Transactional
  public void addAuxItems(Long versionId, List<QuoteTechAuxItem> items) {
    List<QuoteTechAuxItem> values = values(items);
    if (values.isEmpty()) return;
    requireDraft(versionId);
    bindVersion(values, versionId, QuoteTechAuxItem::getVersionId, QuoteTechAuxItem::setVersionId);
    requireBatchCount(repository.insertAuxItemsIfDraft(versionId, values), values.size(), versionId);
  }

  @Override
  @Transactional
  public void addSalaryItems(Long versionId, List<QuoteTechSalaryItem> items) {
    List<QuoteTechSalaryItem> values = values(items);
    if (values.isEmpty()) return;
    requireDraft(versionId);
    bindVersion(
        values, versionId, QuoteTechSalaryItem::getVersionId, QuoteTechSalaryItem::setVersionId);
    requireBatchCount(
        repository.insertSalaryItemsIfDraft(versionId, values), values.size(), versionId);
  }

  @Override
  @Transactional
  public QuoteTechDataVersion updateDraft(
      QuoteTechDataVersion version, int expectedRowVersion) {
    Objects.requireNonNull(version, "version");
    requiredId("version.id", version.getId());
    requireExpectedVersion(expectedRowVersion);
    requireDraft(version.getId());
    if (repository.updateDraftVersion(version, expectedRowVersion, now()) != 1) {
      throw new QuoteTechnicalDataOptimisticLockException("版本", version.getId());
    }
    return repository.findVersion(version.getId())
        .orElseThrow(() -> new IllegalStateException("更新后技术资料版本不存在"));
  }

  @Override
  @Transactional
  public QuoteTechDataVersion transitionVersion(
      Long versionId,
      String expectedStatus,
      String targetStatus,
      int expectedRowVersion,
      String contentFingerprint,
      Long actorId) {
    requiredId("versionId", versionId);
    requiredId("actorId", actorId);
    requireExpectedVersion(expectedRowVersion);
    String expected = required("expectedStatus", expectedStatus);
    String target = required("targetStatus", targetStatus);
    requireAllowedVersionTransition(expected, target);
    if (QuoteTechDataVersion.STATUS_DRAFT.equals(expected)
        && QuoteTechDataVersion.STATUS_SUBMITTED.equals(target)) {
      QuoteTechDataVersion draft = repository.findVersion(versionId)
          .orElseThrow(() -> new IllegalArgumentException("技术资料版本不存在：" + versionId));
      if (!QuoteTechDataVersion.STATUS_DRAFT.equals(draft.getVersionStatus())
          || !Objects.equals(draft.getRowVersion(), expectedRowVersion)) {
        throw new QuoteTechnicalDataOptimisticLockException("版本状态", versionId);
      }
      QuoteTechProduct product = repository.findProduct(draft.getProductId())
          .orElseThrow(() -> new IllegalArgumentException(
              "技术资料产品不存在：" + draft.getProductId()));
      if (!Objects.equals(product.getCurrentEditVersionId(), versionId)) {
        throw new IllegalStateException("只能提交产品当前编辑版本");
      }
      return freezeDraftForSubmission(product.getId(), product.getRowVersion(), actorId);
    }
    if (repository.transitionVersion(
        versionId,
        expected,
        target,
        expectedRowVersion,
        contentFingerprint,
        null,
        actorId,
        now()) != 1) {
      throw new QuoteTechnicalDataOptimisticLockException("版本状态", versionId);
    }
    return repository.findVersion(versionId)
        .orElseThrow(() -> new IllegalStateException("状态迁移后技术资料版本不存在"));
  }

  @Override
  @Transactional
  public QuoteTechDataVersion freezeDraftForSubmission(
      Long productId, int expectedProductVersion, Long actorId) {
    requiredId("productId", productId);
    requiredId("actorId", actorId);
    requireExpectedVersion(expectedProductVersion);
    QuoteTechProduct product = lockProduct(productId, expectedProductVersion);
    Long draftId = product.getCurrentEditVersionId();
    if (draftId == null) throw new IllegalStateException("产品尚未创建当前草稿版本");
    QuoteTechDataVersion draft = lockOwnedVersion(product, draftId);
    if (!QuoteTechDataVersion.STATUS_DRAFT.equals(draft.getVersionStatus())) {
      throw new QuoteTechnicalDataImmutableVersionException(
          draft.getId(), draft.getVersionStatus());
    }

    List<QuoteTechModule> modules = repository.lockModules(productId);
    List<QuoteTechPackageItem> packages = repository.findPackageItems(draftId);
    List<QuoteTechAuxItem> auxiliaries = repository.findAuxItems(draftId);
    List<QuoteTechSalaryItem> salaries = repository.findSalaryItems(draftId);
    requireCompleteModules(draftId, modules, packages, auxiliaries, salaries);

    draft.setPackageTotalAmount(sumPackageAmount(packages));
    draft.setAuxiliaryTotalAmount(sumAuxAmount(auxiliaries));
    draft.setSalaryTotalAmount(sumSalaryAmount(salaries));
    List<TechnicalDataVersionContentCodec.ModuleSnapshot> snapshots =
        contentCodec.moduleSnapshots(modules);
    String referenceSnapshotJson = contentCodec.referenceSnapshotJson(snapshots);
    draft.setReferenceSnapshotJson(referenceSnapshotJson);
    draft.setUpdatedBy(actorId);
    LocalDateTime changedAt = now();
    int draftRowVersion = draft.getRowVersion();
    if (repository.updateDraftVersion(draft, draftRowVersion, changedAt) != 1) {
      throw new QuoteTechnicalDataOptimisticLockException("版本", draftId);
    }
    draft.setRowVersion(draftRowVersion + 1);
    // 指纹必须以数据库规范化后的内容为准。特别是包装尚无价格时，内存汇总是
    // BigDecimal.ZERO（scale=0），落入 DECIMAL(20,8) 后会成为 0.00000000；
    // 若直接使用内存对象计算，生效查询按持久化内容复算时会误判内容被篡改。
    QuoteTechDataVersion persistedDraft = repository.findVersion(draftId)
        .orElseThrow(() -> new IllegalStateException("更新后技术资料版本不存在：" + draftId));
    String fingerprint = contentCodec.fingerprint(
        persistedDraft, snapshots, packages, auxiliaries, salaries);
    if (repository.transitionVersion(
        draftId,
        QuoteTechDataVersion.STATUS_DRAFT,
        QuoteTechDataVersion.STATUS_SUBMITTED,
        persistedDraft.getRowVersion(),
        fingerprint,
        referenceSnapshotJson,
        actorId,
        changedAt) != 1) {
      throw new QuoteTechnicalDataOptimisticLockException("版本状态", draftId);
    }

    for (QuoteTechModule module : modules) {
      int moduleVersion = module.getRowVersion();
      if (Integer.valueOf(1).equals(module.getRequiredFlag())) {
        module.setModuleStatus("SUBMITTED");
      } else {
        module.setModuleStatus("NOT_REQUIRED");
      }
      if (repository.updateModule(module, moduleVersion, changedAt) != 1) {
        throw new QuoteTechnicalDataOptimisticLockException("模块", module.getId());
      }
    }

    product.setProductStatus("SUBMITTED");
    product.setCurrentEditVersionId(null);
    product.setLatestSubmittedVersionId(draftId);
    if (repository.updateProductPointers(product, expectedProductVersion, changedAt) != 1) {
      throw new QuoteTechnicalDataOptimisticLockException("产品", productId);
    }
    return repository.findVersion(draftId)
        .orElseThrow(() -> new IllegalStateException("提交后技术资料版本不存在"));
  }

  @Override
  @Transactional
  public QuoteTechDataVersion copySubmittedVersionAsDraft(
      Long productId,
      Long sourceVersionId,
      int expectedProductVersion,
      Long actorId) {
    return copyVersionAsDraft(
        productId, sourceVersionId, expectedProductVersion, actorId, null);
  }

  @Override
  @Transactional
  public QuoteTechDataVersion copyReturnedModulesAsDraft(
      Long productId,
      Long sourceVersionId,
      int expectedProductVersion,
      Long actorId,
      Set<String> returnedModuleTypes) {
    if (returnedModuleTypes == null || returnedModuleTypes.isEmpty()
        || !MODULE_TYPES.containsAll(returnedModuleTypes)) {
      throw new IllegalArgumentException("退回模块集合无效");
    }
    return copyVersionAsDraft(
        productId, sourceVersionId, expectedProductVersion, actorId,
        Set.copyOf(returnedModuleTypes));
  }

  private QuoteTechDataVersion copyVersionAsDraft(
      Long productId,
      Long sourceVersionId,
      int expectedProductVersion,
      Long actorId,
      Set<String> returnedModuleTypes) {
    requiredId("productId", productId);
    requiredId("sourceVersionId", sourceVersionId);
    requiredId("actorId", actorId);
    requireExpectedVersion(expectedProductVersion);
    QuoteTechProduct product = lockProduct(productId, expectedProductVersion);
    if (product.getCurrentEditVersionId() != null) {
      throw new IllegalStateException("产品已有当前草稿，不能重复复制历史版本");
    }
    QuoteTechDataVersion source = lockOwnedVersion(product, sourceVersionId);
    if (!COPYABLE_VERSION_STATUSES.contains(source.getVersionStatus())) {
      throw new IllegalArgumentException("只能从已提交、已退回或已生效版本复制草稿");
    }
    if (!StringUtils.hasText(source.getContentFingerprint())) {
      throw new IllegalStateException("历史提交版本缺少内容指纹");
    }

    List<QuoteTechModule> modules = repository.lockModules(productId);
    List<QuoteTechPackageItem> sourcePackages = repository.findPackageItems(sourceVersionId);
    List<QuoteTechAuxItem> sourceAuxiliaries = repository.findAuxItems(sourceVersionId);
    List<QuoteTechSalaryItem> sourceSalaries = repository.findSalaryItems(sourceVersionId);

    QuoteTechDataVersion draft = copiedDraft(
        source, repository.maxVersionNo(productId) + 1, actorId, now());
    repository.insertVersion(draft);
    addPackageItems(draft.getId(), copyPackages(sourcePackages));
    addAuxItems(draft.getId(), copyAuxiliaries(sourceAuxiliaries));
    addSalaryItems(draft.getId(), copySalaries(sourceSalaries));

    List<TechnicalDataVersionContentCodec.ModuleSnapshot> snapshots =
        contentCodec.readReferenceSnapshot(source.getReferenceSnapshotJson());
    restoreCopiedModules(
        draft.getId(), sourceVersionId, modules, snapshots, returnedModuleTypes);

    product.setProductStatus("EDITING");
    product.setCurrentEditVersionId(draft.getId());
    if (repository.updateProductPointers(product, expectedProductVersion, now()) != 1) {
      throw new QuoteTechnicalDataOptimisticLockException("产品", productId);
    }
    return repository.findVersion(draft.getId())
        .orElseThrow(() -> new IllegalStateException("复制后的技术资料草稿不存在"));
  }

  @Override
  @Transactional
  public void updatePackageItem(QuoteTechPackageItem item) {
    requireMutableItem(item == null ? null : item.getVersionId(), item == null ? null : item.getId());
    if (repository.updatePackageItemIfDraft(item) != 1) {
      throw immutable(item.getVersionId());
    }
  }

  @Override
  @Transactional
  public void updateAuxItem(QuoteTechAuxItem item) {
    requireMutableItem(item == null ? null : item.getVersionId(), item == null ? null : item.getId());
    if (repository.updateAuxItemIfDraft(item) != 1) {
      throw immutable(item.getVersionId());
    }
  }

  @Override
  @Transactional
  public void updateSalaryItem(QuoteTechSalaryItem item) {
    requireMutableItem(item == null ? null : item.getVersionId(), item == null ? null : item.getId());
    if (repository.updateSalaryItemIfDraft(item) != 1) {
      throw immutable(item.getVersionId());
    }
  }

  @Override
  @Transactional
  public void deletePackageItem(Long versionId, Long itemId) {
    requireMutableItem(versionId, itemId);
    if (repository.deletePackageItemIfDraft(versionId, itemId) != 1) throw immutable(versionId);
  }

  @Override
  @Transactional
  public void deleteAuxItem(Long versionId, Long itemId) {
    requireMutableItem(versionId, itemId);
    if (repository.deleteAuxItemIfDraft(versionId, itemId) != 1) throw immutable(versionId);
  }

  @Override
  @Transactional
  public void deleteSalaryItem(Long versionId, Long itemId) {
    requireMutableItem(versionId, itemId);
    if (repository.deleteSalaryItemIfDraft(versionId, itemId) != 1) throw immutable(versionId);
  }

  private QuoteTechProduct lockProduct(Long productId, int expectedProductVersion) {
    QuoteTechProduct product = repository.lockProduct(productId)
        .orElseThrow(() -> new IllegalArgumentException("技术资料产品不存在：" + productId));
    if (!Integer.valueOf(1).equals(product.getActiveFlag())) {
      throw new IllegalStateException("历史技术资料产品不能变更版本");
    }
    if (!Objects.equals(product.getRowVersion(), expectedProductVersion)) {
      throw new QuoteTechnicalDataOptimisticLockException("产品", productId);
    }
    return product;
  }

  private QuoteTechDataVersion lockOwnedVersion(
      QuoteTechProduct product, Long versionId) {
    QuoteTechDataVersion version = repository.lockVersion(versionId)
        .orElseThrow(() -> new IllegalArgumentException("技术资料版本不存在：" + versionId));
    if (!Objects.equals(version.getProductId(), product.getId())) {
      throw new IllegalArgumentException("技术资料版本不属于指定产品");
    }
    return version;
  }

  private void requireCompleteModules(
      Long draftId,
      List<QuoteTechModule> modules,
      List<QuoteTechPackageItem> packages,
      List<QuoteTechAuxItem> auxiliaries,
      List<QuoteTechSalaryItem> salaries) {
    Map<String, QuoteTechModule> byType = moduleMap(modules);
    QuoteTechModule profile = byType.get("PROFILE");
    if (!Objects.equals(profile.getCurrentVersionId(), draftId)
        || !"MANUAL".equals(profile.getEntryMode())
        || !("READY".equals(profile.getModuleStatus())
            || "NOT_REQUIRED".equals(profile.getModuleStatus()))
        || !StringUtils.hasText(profile.getLastValidationCode())) {
      throw new IllegalStateException("PROFILE模块尚未完成有效校验");
    }
    requireDetailModule(draftId, byType.get("PACKAGE"), packages.size());
    requireDetailModule(draftId, byType.get("AUXILIARY"), auxiliaries.size());
    requireDetailModule(draftId, byType.get("SALARY"), salaries.size());
  }

  private void requireDetailModule(Long draftId, QuoteTechModule module, int itemCount) {
    if (!Integer.valueOf(1).equals(module.getRequiredFlag())) {
      if (!"NOT_REQUIRED".equals(module.getModuleStatus())) {
        throw new IllegalStateException(module.getModuleType() + "非必填模块状态必须为NOT_REQUIRED");
      }
      return;
    }
    if (!Objects.equals(module.getCurrentVersionId(), draftId)
        || !"READY".equals(module.getModuleStatus())
        || !("MANUAL".equals(module.getEntryMode()) || "REFERENCE".equals(module.getEntryMode()))
        || !StringUtils.hasText(module.getLastValidationCode())
        || module.getLastValidationCode().toUpperCase().contains("INVALID")
        || itemCount <= 0) {
      throw new IllegalStateException(module.getModuleType() + "模块没有真实完整明细，不能提交");
    }
    if ("REFERENCE".equals(module.getEntryMode())
        && (!StringUtils.hasText(module.getReferenceSourceType())
            || !StringUtils.hasText(module.getReferenceSourceId())
            || !StringUtils.hasText(module.getReferenceFingerprint())
            || !StringUtils.hasText(module.getReferenceSnapshotJson()))) {
      throw new IllegalStateException(module.getModuleType() + "参照来源快照不完整");
    }
  }

  private Map<String, QuoteTechModule> moduleMap(List<QuoteTechModule> modules) {
    Map<String, QuoteTechModule> result = new LinkedHashMap<>();
    for (QuoteTechModule module : modules) {
      if (!MODULE_TYPES.contains(module.getModuleType())
          || result.put(module.getModuleType(), module) != null) {
        throw new IllegalStateException("产品技术资料模块结构异常");
      }
    }
    if (!result.keySet().equals(MODULE_TYPES)) {
      throw new IllegalStateException("产品必须且只能包含PROFILE/PACKAGE/AUXILIARY/SALARY模块");
    }
    return result;
  }

  private void restoreCopiedModules(
      Long draftId,
      Long sourceVersionId,
      List<QuoteTechModule> modules,
      List<TechnicalDataVersionContentCodec.ModuleSnapshot> snapshots,
      Set<String> returnedModuleTypes) {
    Map<String, QuoteTechModule> byType = moduleMap(modules);
    Map<String, TechnicalDataVersionContentCodec.ModuleSnapshot> snapshotByType =
        new LinkedHashMap<>();
    for (TechnicalDataVersionContentCodec.ModuleSnapshot snapshot : snapshots) {
      if (!MODULE_TYPES.contains(snapshot.moduleType())
          || snapshotByType.put(snapshot.moduleType(), snapshot) != null) {
        throw new IllegalStateException("历史提交版本模块快照结构异常");
      }
    }
    if (!snapshotByType.keySet().equals(MODULE_TYPES)) {
      throw new IllegalStateException("历史提交版本模块快照不完整");
    }
    LocalDateTime changedAt = now();
    for (String type : MODULE_TYPES) {
      QuoteTechModule module = byType.get(type);
      TechnicalDataVersionContentCodec.ModuleSnapshot snapshot = snapshotByType.get(type);
      boolean required = Integer.valueOf(1).equals(module.getRequiredFlag());
      if (required != snapshot.required()) {
        throw new IllegalStateException(type + "模块必填规则已变化，不能直接复制历史版本");
      }
      if (required && "NONE".equals(snapshot.entryMode())) {
        throw new IllegalStateException(type + "历史模块缺少有效录入方式");
      }
      int expectedVersion = module.getRowVersion();
      module.setEntryMode(snapshot.entryMode());
      boolean partialReturn = returnedModuleTypes != null;
      boolean returned = partialReturn && returnedModuleTypes.contains(type);
      module.setModuleStatus(required
          ? (partialReturn ? (returned ? "EDITING" : "APPROVED")
              : ("RETURNED".equals(module.getModuleStatus()) ? "EDITING" : "READY"))
          : "NOT_REQUIRED");
      module.setCurrentVersionId(partialReturn && !returned ? sourceVersionId : draftId);
      module.setReferenceSourceType(snapshot.referenceSourceType());
      module.setReferenceSourceId(snapshot.referenceSourceId());
      module.setReferenceSourceVersion(snapshot.referenceSourceVersion());
      module.setReferenceFingerprint(snapshot.referenceFingerprint());
      module.setReferenceSnapshotJson(snapshot.referenceSnapshotJson());
      module.setLastValidationCode(snapshot.validationCode());
      module.setLastValidationMessage(snapshot.validationMessage());
      if (repository.updateModule(module, expectedVersion, changedAt) != 1) {
        throw new QuoteTechnicalDataOptimisticLockException("模块", module.getId());
      }
    }
  }

  private QuoteTechDataVersion copiedDraft(
      QuoteTechDataVersion source,
      int versionNo,
      Long actorId,
      LocalDateTime createdAt) {
    QuoteTechDataVersion draft = new QuoteTechDataVersion();
    draft.setProductId(source.getProductId());
    draft.setVersionNo(versionNo);
    draft.setVersionStatus(QuoteTechDataVersion.STATUS_DRAFT);
    draft.setProductModel(source.getProductModel());
    draft.setProductProperty(source.getProductProperty());
    draft.setNewProductFlag(source.getNewProductFlag());
    draft.setPackageTotalAmount(source.getPackageTotalAmount());
    draft.setAuxiliaryTotalAmount(source.getAuxiliaryTotalAmount());
    draft.setSalaryTotalAmount(source.getSalaryTotalAmount());
    draft.setReferenceSnapshotJson(source.getReferenceSnapshotJson());
    draft.setCreatedFromVersionId(source.getId());
    draft.setRowVersion(0);
    draft.setCreatedBy(actorId);
    draft.setUpdatedBy(actorId);
    draft.setCreatedAt(createdAt);
    draft.setUpdatedAt(createdAt);
    return draft;
  }

  private List<QuoteTechPackageItem> copyPackages(List<QuoteTechPackageItem> source) {
    return source.stream().map(item -> {
      QuoteTechPackageItem copy = new QuoteTechPackageItem();
      copy.setLineNo(item.getLineNo());
      copy.setSortSeq(item.getSortSeq());
      copy.setComponentMaterialNo(item.getComponentMaterialNo());
      copy.setComponentName(item.getComponentName());
      copy.setComponentSpec(item.getComponentSpec());
      copy.setQuantity(item.getQuantity());
      copy.setOriginalUnit(item.getOriginalUnit());
      copy.setStandardQuantity(item.getStandardQuantity());
      copy.setStandardUnit(item.getStandardUnit());
      copy.setConversionFactor(item.getConversionFactor());
      copy.setPriceBasisType(item.getPriceBasisType());
      copy.setReferenceUnitPrice(item.getReferenceUnitPrice());
      copy.setAmount(item.getAmount());
      copy.setSourceReferenceId(item.getSourceReferenceId());
      copy.setSourceReferenceVersion(item.getSourceReferenceVersion());
      copy.setSourceSnapshotJson(item.getSourceSnapshotJson());
      copy.setRemark(item.getRemark());
      return copy;
    }).toList();
  }

  private List<QuoteTechAuxItem> copyAuxiliaries(List<QuoteTechAuxItem> source) {
    return source.stream().map(item -> {
      QuoteTechAuxItem copy = new QuoteTechAuxItem();
      copy.setLineNo(item.getLineNo());
      copy.setSortSeq(item.getSortSeq());
      copy.setSubjectCode(item.getSubjectCode());
      copy.setSubjectName(item.getSubjectName());
      copy.setAuxiliaryMaterialNo(item.getAuxiliaryMaterialNo());
      copy.setAuxiliaryName(item.getAuxiliaryName());
      copy.setAuxiliarySpec(item.getAuxiliarySpec());
      copy.setPricingMethod(item.getPricingMethod());
      copy.setQuantity(item.getQuantity());
      copy.setOriginalUnit(item.getOriginalUnit());
      copy.setStandardQuantity(item.getStandardQuantity());
      copy.setStandardUnit(item.getStandardUnit());
      copy.setConversionFactor(item.getConversionFactor());
      copy.setReferenceUnitPrice(item.getReferenceUnitPrice());
      copy.setPriceUnit(item.getPriceUnit());
      copy.setLossRate(item.getLossRate());
      copy.setAmount(item.getAmount());
      copy.setSourceReferenceId(item.getSourceReferenceId());
      copy.setSourceReferenceVersion(item.getSourceReferenceVersion());
      copy.setSourceSnapshotJson(item.getSourceSnapshotJson());
      copy.setRemark(item.getRemark());
      return copy;
    }).toList();
  }

  private List<QuoteTechSalaryItem> copySalaries(List<QuoteTechSalaryItem> source) {
    return source.stream().map(item -> {
      QuoteTechSalaryItem copy = new QuoteTechSalaryItem();
      copy.setLineNo(item.getLineNo());
      copy.setSortSeq(item.getSortSeq());
      copy.setProcessCode(item.getProcessCode());
      copy.setProcessName(item.getProcessName());
      copy.setLaborType(item.getLaborType());
      copy.setWorkingHours(item.getWorkingHours());
      copy.setOriginalTimeUnit(item.getOriginalTimeUnit());
      copy.setStandardHours(item.getStandardHours());
      copy.setStandardTimeUnit(item.getStandardTimeUnit());
      copy.setConversionFactor(item.getConversionFactor());
      copy.setWageRate(item.getWageRate());
      copy.setRateUnit(item.getRateUnit());
      copy.setHourlyRate(item.getHourlyRate());
      copy.setPersonCoefficient(item.getPersonCoefficient());
      copy.setAmount(item.getAmount());
      copy.setSourceReferenceId(item.getSourceReferenceId());
      copy.setSourceReferenceVersion(item.getSourceReferenceVersion());
      copy.setSourceSnapshotJson(item.getSourceSnapshotJson());
      copy.setRemark(item.getRemark());
      return copy;
    }).toList();
  }

  private BigDecimal sumPackageAmount(List<QuoteTechPackageItem> items) {
    return items.stream().map(QuoteTechPackageItem::getAmount)
        .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal sumAuxAmount(List<QuoteTechAuxItem> items) {
    return items.stream().map(QuoteTechAuxItem::getAmount)
        .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private BigDecimal sumSalaryAmount(List<QuoteTechSalaryItem> items) {
    return items.stream().map(QuoteTechSalaryItem::getAmount)
        .filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  public static String taskActiveLockKey(
      String oaNo, String accountingMonth, Long assigneeUserId) {
    return "OA:" + oaNo + ":MONTH:" + accountingMonth + ":ASSIGNEE:" + assigneeUserId;
  }

  public static String productActiveLockKey(Long oaFormItemId, String accountingMonth) {
    return "ITEM:" + oaFormItemId + ":MONTH:" + accountingMonth;
  }

  private void normalizeVersion(QuoteTechDataVersion version) {
    Objects.requireNonNull(version, "version");
    requiredId("productId", version.getProductId());
    if (version.getVersionNo() == null || version.getVersionNo() <= 0) {
      throw new IllegalArgumentException("versionNo 必须大于0");
    }
    version.setVersionStatus(defaultText(version.getVersionStatus(), QuoteTechDataVersion.STATUS_DRAFT));
    version.setNewProductFlag(defaultNumber(version.getNewProductFlag(), 0));
    version.setPackageTotalAmount(defaultDecimal(version.getPackageTotalAmount()));
    version.setAuxiliaryTotalAmount(defaultDecimal(version.getAuxiliaryTotalAmount()));
    version.setSalaryTotalAmount(defaultDecimal(version.getSalaryTotalAmount()));
    version.setRowVersion(defaultNumber(version.getRowVersion(), 0));
  }

  private QuoteTechDataVersion requireDraft(Long versionId) {
    requiredId("versionId", versionId);
    QuoteTechDataVersion version = repository.findVersion(versionId)
        .orElseThrow(() -> new IllegalArgumentException("技术资料版本不存在：" + versionId));
    if (!QuoteTechDataVersion.STATUS_DRAFT.equals(version.getVersionStatus())) {
      throw new QuoteTechnicalDataImmutableVersionException(versionId, version.getVersionStatus());
    }
    return version;
  }

  private void requireMutableItem(Long versionId, Long itemId) {
    requiredId("versionId", versionId);
    requiredId("itemId", itemId);
    requireDraft(versionId);
  }

  private QuoteTechnicalDataImmutableVersionException immutable(Long versionId) {
    String status = repository.findVersion(versionId)
        .map(QuoteTechDataVersion::getVersionStatus).orElse("NOT_FOUND");
    return new QuoteTechnicalDataImmutableVersionException(versionId, status);
  }

  private void requireBatchCount(int actual, int expected, Long versionId) {
    if (actual != expected) throw immutable(versionId);
  }

  private <T> List<T> values(List<T> items) {
    if (items == null || items.isEmpty()) return List.of();
    List<T> values = List.copyOf(items);
    if (values.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("明细不能包含null");
    }
    return values;
  }

  private <T> void bindVersion(
      List<T> items,
      Long versionId,
      java.util.function.Function<T, Long> getter,
      java.util.function.BiConsumer<T, Long> setter) {
    for (T item : items) {
      Long current = getter.apply(item);
      if (current == null) setter.accept(item, versionId);
      else if (!versionId.equals(current)) throw new IllegalArgumentException("明细不属于指定版本");
    }
  }

  private String month(String value) {
    String normalized = required("accountingMonth", value);
    try {
      return YearMonth.parse(normalized).toString();
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException("accountingMonth 必须为YYYY-MM", exception);
    }
  }

  private String required(String field, String value) {
    if (!StringUtils.hasText(value)) throw new IllegalArgumentException(field + "不能为空");
    return value.trim();
  }

  private void requiredId(String field, Long value) {
    if (value == null || value <= 0) throw new IllegalArgumentException(field + "必须大于0");
  }

  private void requireExpectedVersion(int value) {
    if (value < 0) throw new IllegalArgumentException("expectedRowVersion不能小于0");
  }

  private void requireAllowedVersionTransition(String expectedStatus, String targetStatus) {
    boolean allowed = (QuoteTechDataVersion.STATUS_DRAFT.equals(expectedStatus)
        && ("SUBMITTED".equals(targetStatus) || "VOIDED".equals(targetStatus)))
        || ("SUBMITTED".equals(expectedStatus)
        && ("APPROVED".equals(targetStatus)
        || "RETURNED".equals(targetStatus)
        || "VOIDED".equals(targetStatus)));
    if (!allowed) {
      throw new IllegalArgumentException(
          "不允许的技术资料版本状态迁移：" + expectedStatus + " -> " + targetStatus);
    }
  }

  private String defaultText(String value, String defaultValue) {
    return StringUtils.hasText(value) ? value.trim() : defaultValue;
  }

  private Integer defaultNumber(Integer value, int defaultValue) {
    return value == null ? defaultValue : value;
  }

  private java.math.BigDecimal defaultDecimal(java.math.BigDecimal value) {
    return value == null ? java.math.BigDecimal.ZERO : value;
  }

  private LocalDateTime now() {
    return LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
  }
}
