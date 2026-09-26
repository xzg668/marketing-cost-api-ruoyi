package com.sanhua.marketingcost.service.technicaldata;

import static com.sanhua.marketingcost.service.technicaldata.TechnicalDataVersionCopies.*;

import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaRecipientRepository.Recipient;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 个人冻结、定向恢复和批准版本汇总。调用方先锁任务，随后按产品、版本、模块顺序加锁。 */
@Service
public class TechnicalDataParticipantVersions {
  private final TechnicalDataSharedModules sharedModules;
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataVersionContentCodec codec;
  private final TechnicalDataDependencies dependencies;
  private final TechnicalDataManufacturingApplicationService manufacturing;
  private final TechnicalDataPackageApplicationService packaging;
  private final TechnicalDataAuxiliaryApplicationService auxiliary;
  private final TechnicalDataSolderApplicationService solder;
  private final TechnicalDataSalaryApplicationService salary;
  private final TechnicalDataNetLossApplicationService netLoss;
  private final TechnicalDataPriceApplicationService prices;

  public TechnicalDataParticipantVersions(QuoteTechnicalDataRepository repository, TechnicalDataVersionContentCodec codec,
      TechnicalDataDependencies dependencies, TechnicalDataManufacturingApplicationService manufacturing,
      TechnicalDataPackageApplicationService packaging, TechnicalDataAuxiliaryApplicationService auxiliary,
      TechnicalDataSolderApplicationService solder, TechnicalDataSalaryApplicationService salary, TechnicalDataNetLossApplicationService netLoss, TechnicalDataSharedModules sharedModules, TechnicalDataPriceApplicationService prices) {
    this.sharedModules = sharedModules;
    this.repository = repository;
    this.codec = codec;
    this.dependencies = dependencies;
    this.manufacturing = manufacturing;
    this.packaging = packaging;
    this.auxiliary = auxiliary;
    this.solder = solder;
    this.salary = salary;
    this.netLoss = netLoss;
    this.prices = prices;
  }

  public QuoteTechDataVersion freeze(QuoteTechProduct product, Recipient person, int expectedVersion, long actorId) {
    if (!Objects.equals(product.getRowVersion(), expectedVersion)) throw conflict("产品资料已变化，请刷新后提交");
    var draft = draft(product);
    var all = repository.lockModules(product.getId());
    var own = scope(all, person);
    if (own.stream().anyMatch(module -> Integer.valueOf(0).equals(module.getOaEditAllowed()))) throw conflict("OA待办已暂停或失效，不能提交补录");
    for (var module : own) {
      if (!"PRICE".equals(module.getModuleType())) sharedModules.requireOwnership(product.getId(), module.getModuleType());
    }
    if (own.stream().anyMatch(module -> !Set.of("READY", "RETURNED").contains(module.getModuleStatus())
        || !Objects.equals(module.getCurrentVersionId(), draft.getId()) || !"MISSING".equals(module.getSourceAvailability())
        || module.getLastValidationCode() == null)) throw conflict("本人负责模块尚未全部完成服务端校验");
    Set<String> types = Set.copyOf(person.processingModules());
    if (types.contains("PROFILE") && !TechnicalDataProductFeeRules.validate(codec.productFees(draft)).isEmpty()) {
      throw conflict("本人负责模块的产品费用尚未填写完整，不能冻结提交");
    }
    if (types.contains("DRAWING_BOM") && !TechnicalDataDrawingRules.validate(codec.drawingBom(draft), product).isEmpty()) {
      throw conflict("电子图库明细尚未通过实际取数校验，不能冻结提交");
    }
    if (types.contains("MANUFACTURING") && !manufacturing.validateCurrent(product, codec.manufacturing(draft)).isEmpty()) {
      throw conflict("制造件原材料或来源尚未核实完整，不能冻结提交");
    }
    if (types.contains("PACKAGE") && !packaging.validateCurrent(product, draft).isEmpty()) {
      throw conflict("包装母子用量或来源尚未核实完整，不能冻结提交");
    }
    if (types.contains("AUXILIARY") && !auxiliary.validateCurrent(product, draft).isEmpty()) {
      throw conflict("辅料明细、本次金额或来源尚未核实完整，不能冻结提交");
    }
    if (types.contains("SOLDER") && !solder.validateCurrent(product, draft).isEmpty()) {
      throw conflict("焊料、图号来源或每产品用量尚未核实完整，不能冻结提交");
    }
    if (types.contains("SALARY") && !salary.validateCurrent(product, draft).isEmpty()) {
      throw conflict("工资金额或来源尚未核实完整，不能冻结提交");
    }
    if (types.contains("NET_LOSS") && !netLoss.validateCurrent(product, draft).isEmpty()) {
      throw conflict("净损失率或参考来源尚未核实完整，不能冻结提交");
    }
    if (types.contains("PRICE")) {
      var issues = prices.validateCurrent(product, draft);
      if (!issues.isEmpty()) throw conflict(String.join("；", issues));
    }
    var copy = scopedDraft(draft, repository.maxVersionNo(product.getId()) + 1, actorId, now(), types);
    copy.setReferenceSnapshotJson(codec.referenceSnapshotJson(codec.moduleSnapshots(own)));
    copy.setSourceFactsJson(codec.sourceFactsJson(product.getSourceSnapshotJson(), own, dependencies.capture(all, types)));
    repository.insertVersion(copy);
    copyDetails(copy.getId(), draft.getId(), types);
    var frozen = freezeCopy(copy.getId(), actorId);
    for (var module : own) setModule(module, frozen.getId(), "FROZEN");
    product.setLatestSubmittedVersionId(frozen.getId());
    product.setEffectiveVersionId(null);
    product.setEffectiveReviewRound(null);
    product.setEffectiveAt(null);
    product.setProductStatus("EDITING");
    saveProduct(product);
    return frozen;
  }

  /** OA 明确拒绝时继续使用原来的唯一工作草稿，不另存一份草稿。 */
  public void releaseCandidate(QuoteTechProduct product, Recipient person, long versionId, long actorId) {
    var candidate = verified(versionId, product.getId());
    var current = draft(product);
    for (var module : scope(repository.lockModules(product.getId()), person)) {
      if (!Objects.equals(module.getCurrentVersionId(), versionId)) throw conflict("模块已不属于本次提交");
      setModule(module, current.getId(), "READY");
    }
    transition(candidate, "VOIDED", actorId);
    if (Objects.equals(product.getLatestSubmittedVersionId(), versionId)) product.setLatestSubmittedVersionId(null);
    product.setProductStatus("EDITING");
    saveProduct(product);
  }

  public void restore(QuoteTechProduct product, Recipient person, long sourceId, long actorId) {
    restore(product, person, Long.valueOf(sourceId), actorId);
  }

  /** 报价员可再次退回先前轮次批准的板块，读取各板块自己的批准版本。 */
  public void restoreApproved(QuoteTechProduct product, Recipient person, long actorId) {
    restore(product, person, null, actorId);
  }

  private void restore(QuoteTechProduct product, Recipient person, Long sourceId, long actorId) {
    var current = draft(product);
    var all = repository.lockModules(product.getId());
    var own = scope(all, person);
    // 退回覆盖当前可变草稿的选中板块，不累积作废草稿；已提交快照保持不可变。
    var next = current;
    Map<String, Long> sourceIds = new LinkedHashMap<>();
    for (var module : own) {
      Long id = sourceId == null ? module.getCurrentVersionId() : sourceId;
      if (id == null) throw conflict("退回板块缺少原已提交版本");
      var source = verified(id, product.getId());
      if (sourceId == null && !"APPROVED".equals(source.getVersionStatus())) throw conflict("退回板块尚未批准");
      sourceIds.put(module.getModuleType(), id);
      copyModule(source, next, module.getModuleType());
    }
    next.setReferenceSnapshotJson(null);
    next.setUpdatedBy(actorId);
    if (repository.updateDraftVersion(next, next.getRowVersion(), now()) != 1) throw conflict("当前草稿已变化，不能覆盖退回内容");
    for (var source : sourceIds.entrySet()) {
      switch (source.getKey()) {
        case "PACKAGE" -> repository.deleteAllPackageItemsIfDraft(next.getId());
        case "AUXILIARY" -> repository.deleteAllAuxItemsIfDraft(next.getId());
        case "SALARY" -> repository.deleteAllSalaryItemsIfDraft(next.getId());
        default -> { }
      }
      copyDetails(next.getId(), source.getValue(), Set.of(source.getKey()));
    }
    // 未退回的审批板块仍指向原冻结版本，其他人的草稿内容及明细保持原样。
    for (var module : own) setModule(module, next.getId(), "RETURNED");
    product.setCurrentEditVersionId(next.getId());
    product.setEffectiveVersionId(null);
    product.setEffectiveReviewRound(null);
    product.setEffectiveAt(null);
    product.setProductStatus("RETURNED");
    saveProduct(product);
  }

  public void state(QuoteTechProduct product, Recipient person, long versionId, String status) {
    for (var module : scope(repository.lockModules(product.getId()), person)) {
      if (!Objects.equals(module.getCurrentVersionId(), versionId)) throw conflict("本人模块已不属于此次提交版本");
      setModule(module, versionId, status);
    }
  }

  /** 只组装各模块实际批准的版本，避免将其他人的未提交草稿带入核算。 */
  public QuoteTechDataVersion activate(QuoteTechProduct product, long actorId) {
    var modules = repository.lockModules(product.getId());
    var required = modules.stream().filter(module -> Integer.valueOf(1).equals(module.getRequiredFlag())).toList();
    if (required.isEmpty() || required.stream().anyMatch(module -> !"APPROVED".equals(module.getModuleStatus()))) return null;
    if (!dependencies.approvedIssues(modules).isEmpty()) return null;
    if (modules.stream().anyMatch(module -> !Set.of("AVAILABLE", "MISSING").contains(
        module.getSourceAvailability() == null ? "UNCONFIRMED" : module.getSourceAvailability()))) return null;
    var current = draft(product);
    var aggregate = scopedDraft(current, repository.maxVersionNo(product.getId()) + 1, actorId, now(), Set.of());
    Map<String, Long> sources = new LinkedHashMap<>();
    for (var module : required) {
      var source = verified(module.getCurrentVersionId(), product.getId());
      if (!"APPROVED".equals(source.getVersionStatus())) throw conflict("汇总模块没有已批准版本");
      copyModule(source, aggregate, module.getModuleType());
      sources.put(module.getModuleType(), source.getId());
    }
    aggregate.setReferenceSnapshotJson(codec.referenceSnapshotJson(codec.moduleSnapshots(modules)));
    aggregate.setSourceFactsJson(codec.sourceFactsJson(product.getSourceSnapshotJson(), modules));
    repository.insertVersion(aggregate);
    sources.forEach((type, sourceId) -> copyDetails(aggregate.getId(), sourceId, Set.of(type)));
    var frozen = freezeCopy(aggregate.getId(), actorId);
    var submitted = transition(frozen, "SUBMITTED", actorId);
    var approved = transition(submitted, "APPROVED", actorId);
    product.setEffectiveVersionId(approved.getId());
    product.setEffectiveAt(now());
    product.setEffectiveReviewRound(approved.getVersionNo());
    product.setProductStatus("APPROVED");
    saveProduct(product);
    return approved;
  }

  public QuoteTechDataVersion verified(long id, long productId) {
    var version = repository.lockVersion(id).orElseThrow(() -> conflict("提交版本不存在"));
    if (!Objects.equals(version.getProductId(), productId) || "DRAFT".equals(version.getVersionStatus())
        || version.getContentFingerprint() == null || !version.getContentFingerprint().equals(fingerprint(version))) {
      throw conflict("提交内容与冻结指纹不一致");
    }
    return version;
  }

  public QuoteTechDataVersion transition(QuoteTechDataVersion version, String status, long actorId) {
    if (repository.transitionVersion(version.getId(), version.getVersionStatus(), status, version.getRowVersion(),
        version.getContentFingerprint(), version.getReferenceSnapshotJson(), actorId, now()) != 1) throw conflict("版本状态已变化");
    return repository.findVersion(version.getId()).orElseThrow();
  }

  private QuoteTechDataVersion freezeCopy(long id, long actorId) {
    // DECIMAL 等以落库后的规范值计算指纹，避免内存小数位与数据库不一致。
    var persisted = repository.findVersion(id).orElseThrow();
    persisted.setContentFingerprint(fingerprint(persisted));
    return transition(persisted, "FROZEN", actorId);
  }

  private String fingerprint(QuoteTechDataVersion version) {
    return codec.fingerprint(version, codec.readReferenceSnapshot(version.getReferenceSnapshotJson()),
        repository.findPackageItems(version.getId()), repository.findAuxItems(version.getId()), repository.findSalaryItems(version.getId()));
  }

  private QuoteTechDataVersion draft(QuoteTechProduct product) {
    if (!Integer.valueOf(1).equals(product.getActiveFlag()) || product.getCurrentEditVersionId() == null) throw conflict("产品缺少活动草稿");
    var version = repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
    if (!Objects.equals(version.getProductId(), product.getId()) || !"DRAFT".equals(version.getVersionStatus())) throw conflict("当前编辑版本无效");
    return version;
  }

  private List<QuoteTechModule> scope(List<QuoteTechModule> modules, Recipient person) {
    var assigned = modules.stream().filter(module -> Integer.valueOf(1).equals(module.getRequiredFlag())
        && Objects.equals(module.getAssigneeUserId(), person.userId())).toList();
    if (!person.active() || assigned.isEmpty() || !Set.copyOf(person.modules()).equals(assigned.stream()
        .map(QuoteTechModule::getModuleType).collect(java.util.stream.Collectors.toSet()))
        || person.processingModules().isEmpty() || !person.modules().containsAll(person.processingModules())) {
      throw conflict("本人负责板块或本轮修订范围与当前待办不一致");
    }
    return assigned.stream().filter(module -> person.processingModules().contains(module.getModuleType())).toList();
  }

  private void copyDetails(long target, long source, Set<String> types) {
    if (types.contains("PACKAGE")) {
      var rows = copyPackages(repository.findPackageItems(source));
      rows.forEach(row -> row.setVersionId(target));
      if (!rows.isEmpty() && repository.insertPackageItemsIfDraft(target, rows) != rows.size()) throw conflict("包装明细复制失败");
    }
    if (types.contains("AUXILIARY")) {
      var rows = copyAuxiliaries(repository.findAuxItems(source));
      rows.forEach(row -> row.setVersionId(target));
      if (!rows.isEmpty() && repository.insertAuxItemsIfDraft(target, rows) != rows.size()) throw conflict("辅料明细复制失败");
    }
    if (types.contains("SALARY")) {
      var rows = copySalaries(repository.findSalaryItems(source));
      rows.forEach(row -> row.setVersionId(target));
      if (!rows.isEmpty() && repository.insertSalaryItemsIfDraft(target, rows) != rows.size()) throw conflict("工资明细复制失败");
    }
  }

  private void setModule(QuoteTechModule module, Long versionId, String status) {
    module.setCurrentVersionId(versionId);
    module.setModuleStatus(status);
    if (repository.updateModule(module, module.getRowVersion(), now()) != 1) throw conflict("模块已被其他会话修改");
  }

  private void saveProduct(QuoteTechProduct product) {
    if (repository.updateProductPointers(product, product.getRowVersion(), now()) != 1) throw conflict("产品版本已变化");
  }

  private LocalDateTime now() { return LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE); }
  private TechnicalDataTaskException conflict(String message) {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, message);
  }
}
