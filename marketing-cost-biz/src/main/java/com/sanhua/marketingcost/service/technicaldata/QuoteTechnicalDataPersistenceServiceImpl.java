package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
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
        ? taskActiveLockKey(task.getOaFormItemId(), task.getAccountingMonth())
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
    QuoteTechDataVersion current = requireDraft(version.getId());
    if (!Objects.equals(current.getProductId(), version.getProductId())
        || !Objects.equals(current.getVersionNo(), version.getVersionNo())
        || contentCodec.schemaVersion(current) != contentCodec.schemaVersion(version)) {
      throw new IllegalArgumentException("不能修改草稿的产品归属、版本号或内容结构");
    }
    contentCodec.supplementContent(version);
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
        && (QuoteTechDataVersion.STATUS_SUBMITTED.equals(target)
            || QuoteTechDataVersion.STATUS_FROZEN.equals(target))) {
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
    requireCompleteModules(draft, modules, packages, auxiliaries, salaries);

    draft.setPackageTotalAmount(sumPackageAmount(packages));
    draft.setAuxiliaryTotalAmount(sumAuxAmount(auxiliaries));
    draft.setSalaryTotalAmount(sumSalaryAmount(salaries));
    List<TechnicalDataVersionContentCodec.ModuleSnapshot> snapshots =
        contentCodec.moduleSnapshots(modules);
    String referenceSnapshotJson = contentCodec.referenceSnapshotJson(snapshots);
    draft.setReferenceSnapshotJson(referenceSnapshotJson);
    if (contentCodec.schemaVersion(draft) == 2) {
      draft.setSourceFactsJson(contentCodec.sourceFactsJson(product.getSourceSnapshotJson(), modules));
    }
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
        contentCodec.schemaVersion(draft) == 2 ? QuoteTechDataVersion.STATUS_FROZEN
            : QuoteTechDataVersion.STATUS_SUBMITTED,
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
        module.setModuleStatus(contentCodec.schemaVersion(draft) == 2 ? "FROZEN" : "SUBMITTED");
      } else {
        module.setModuleStatus("NOT_REQUIRED");
      }
      if (repository.updateModule(module, moduleVersion, changedAt) != 1) {
        throw new QuoteTechnicalDataOptimisticLockException("模块", module.getId());
      }
    }

    product.setProductStatus(contentCodec.schemaVersion(draft) == 2 ? "PREPARED" : "SUBMITTED");
    product.setCurrentEditVersionId(null);
    product.setLatestSubmittedVersionId(draftId);
    if (repository.updateProductPointers(product, expectedProductVersion, changedAt) != 1) {
      throw new QuoteTechnicalDataOptimisticLockException("产品", productId);
    }
    return repository.findVersion(draftId)
        .orElseThrow(() -> new IllegalStateException("提交后技术资料版本不存在"));
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
      QuoteTechDataVersion draft,
      List<QuoteTechModule> modules,
      List<QuoteTechPackageItem> packages,
      List<QuoteTechAuxItem> auxiliaries,
      List<QuoteTechSalaryItem> salaries) {
    Long draftId = draft.getId();
    Map<String, QuoteTechModule> byType = moduleMap(modules,
        TechnicalDataModuleType.codesForVersion(draft.getContentSchemaVersion()));
    if (contentCodec.schemaVersion(draft) == 2) {
      for (QuoteTechModule module : modules) {
        if (!Set.of("AVAILABLE", "MISSING").contains(module.getSourceAvailability() == null
            ? "UNCONFIRMED" : module.getSourceAvailability())) {
          throw new IllegalStateException(module.getModuleType() + "来源检查未确认或失败，不能提交");
        }
        if (Set.of("PROFILE", "DRAWING_BOM", "MANUFACTURING", "SOLDER", "NET_LOSS", "PRICE")
            .contains(module.getModuleType())) {
          requireDetailModule(draftId, module, contentCodec.supplementItemCount(draft, module.getModuleType()));
        }
      }
      requireDetailModule(draftId, byType.get("PACKAGE"), packages.size());
      requireDetailModule(draftId, byType.get("AUXILIARY"), auxiliaries.size());
      requireDetailModule(draftId, byType.get("SALARY"), salaries.size());
      return;
    }
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
        || !("MANUAL".equals(module.getEntryMode()) || "REFERENCE".equals(module.getEntryMode())
            || "AUXILIARY".equals(module.getModuleType()) && "UPLOAD".equals(module.getEntryMode()))
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

  private Map<String, QuoteTechModule> moduleMap(List<QuoteTechModule> modules, Set<String> expectedTypes) {
    Map<String, QuoteTechModule> result = new LinkedHashMap<>();
    for (QuoteTechModule module : modules) {
      if (!expectedTypes.contains(module.getModuleType())
          || result.put(module.getModuleType(), module) != null) {
        throw new IllegalStateException("产品技术资料模块结构异常");
      }
    }
    if (!result.keySet().equals(expectedTypes)) {
      throw new IllegalStateException("产品模块与版本结构不一致：" + expectedTypes);
    }
    return result;
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
      Long oaFormItemId, String accountingMonth) {
    if (oaFormItemId == null || oaFormItemId <= 0) throw new IllegalArgumentException("oaFormItemId必须大于0");
    return productActiveLockKey(oaFormItemId, accountingMonth);
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
    if (version.getContentSchemaVersion() == null) {
      QuoteTechProduct product = repository.findProduct(version.getProductId())
          .orElseThrow(() -> new IllegalArgumentException("技术产品不存在"));
      version.setContentSchemaVersion(product.getContentSchemaVersion() == null ? 1 : product.getContentSchemaVersion());
    }
    contentCodec.supplementContent(version);
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
        && ("FROZEN".equals(targetStatus) || "SUBMITTED".equals(targetStatus) || "VOIDED".equals(targetStatus)))
        || ("FROZEN".equals(expectedStatus) && ("SUBMITTED".equals(targetStatus) || "VOIDED".equals(targetStatus)))
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
