package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageReferenceResponse.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 包装草稿与本人模块版本的事务边界；来源查询和用量规则独立维护。 */
@Service
public class TechnicalDataPackageApplicationService {
  private final TechnicalDataSharedModules sharedModules;
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository tasks;
  private final TechnicalDataSourceSnapshotFactory snapshots;
  private final TechnicalDataVersionContentCodec codec;
  private final TechnicalDataPackageSourceQuery sources;

  public TechnicalDataPackageApplicationService(QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository tasks, TechnicalDataSourceSnapshotFactory snapshots,
      TechnicalDataVersionContentCodec codec, TechnicalDataPackageSourceQuery sources, TechnicalDataSharedModules sharedModules) {
    this.sharedModules = sharedModules;
    this.repository = repository; this.tasks = tasks; this.snapshots = snapshots;
    this.codec = codec; this.sources = sources;
  }

  @Transactional(readOnly = true)
  public TechnicalDataPackageResponse get(Long productId, Long versionId, TechnicalDataActor actor) {
    var scope = scope(productId, actor, null);
    Long selected = versionId != null ? versionId
        : Set.of("FROZEN", "SUBMITTED", "APPROVED").contains(scope.module().getModuleStatus())
            ? scope.module().getCurrentVersionId() : scope.product().getCurrentEditVersionId();
    var version = selected == null ? null : repository.findVersion(selected).orElseThrow(() -> invalid("包装版本不存在"));
    if (version != null && !Objects.equals(version.getProductId(), productId)) throw forbidden("不能读取其他产品包装版本");
    var packaging = codec.packaging(version);
    var rows = version == null ? List.<QuoteTechPackageItem>of() : repository.findPackageItems(version.getId());
    boolean historical = versionId != null || version != null && !"DRAFT".equals(version.getVersionStatus());
    boolean currentFormat = packaging != null && packaging.entryMode() != null;
    var issues = !historical && Objects.equals(scope.module().getRequiredFlag(), 1)
        ? TechnicalDataPackageRules.validate(packaging, rows) : List.<String>of();
    var items = rows.stream().map(row -> {
      var evidence = currentFormat ? codec.packageEvidence(row) : null;
      BigDecimal perProduct = currentFormat && packaging.parentQuantity() != null && row.getQuantity() != null
          ? TechnicalDataPackageRules.perProduct(packaging, row) : null;
      return new TechnicalDataPackageResponse.Item(row.getId(), row.getLineNo(), row.getComponentMaterialNo(), row.getComponentName(),
          evidence == null ? null : evidence.model(), row.getComponentSpec(), row.getQuantity(), row.getOriginalUnit(), row.getRemark(), evidence, perProduct);
    }).toList();
    return new TechnicalDataPackageResponse(scope.task().getId(), productId, scope.product().getMaterialNo(),
        scope.product().getProductName(), version == null ? snapshots.readProfile(scope.product().getSourceSnapshotJson()).productModel() : version.getProductModel(),
        scope.product().getAccountingMonth(), selected, version == null ? null : version.getVersionNo(), version == null ? null : version.getVersionStatus(),
        scope.product().getRowVersion(), scope.module().getModuleStatus(), currentFormat ? packaging.entryMode() : scope.module().getEntryMode(),
        !historical && actor.canEditModule(scope.task(), scope.module()), historical, packaging, items, issues);
  }

  @Transactional(readOnly = true)
  public TechnicalDataPackageReferenceResponse references(Long productId, String keyword, TechnicalDataActor actor) {
    var scope = scope(productId, actor, null);
    return sources.references(scope.task(), scope.product(), keyword);
  }

  @Transactional(readOnly = true)
  public List<ChildOption> children(Long productId, String keyword, TechnicalDataActor actor) {
    var scope = scope(productId, actor, null);
    return sources.children(scope.task(), scope.product(), keyword);
  }

  @Transactional
  public TechnicalDataPackageResponse save(Long productId, TechnicalDataPackageSaveRequest request, TechnicalDataActor actor) {
    if (request == null || request.getExpectedVersion() == null || request.getExpectedVersion() < 0
        || !request.getUnknownFields().isEmpty() || request.getItems() == null || request.getItems().size() > 500
        || !Set.of("REFERENCE", "MANUAL").contains(Objects.toString(request.getEntryMode(), ""))) throw invalid("包装请求不完整或包含未知字段");
    var scope = scope(productId, actor, request.getExpectedVersion());
    if (!Objects.equals(scope.product().getContentSchemaVersion(), 2) || !Objects.equals(scope.product().getActiveFlag(), 1)
        || !actor.canEditModule(scope.task(), scope.module())) throw forbidden("当前包装模块未分派给本人或已送审");
    sharedModules.requireOwnership(productId, "PACKAGE");
    if (!"MISSING".equals(scope.module().getSourceAvailability())) throw invalid("请先核实本产品的包装缺口");
    BigDecimal parentQuantity = TechnicalDataPackageRules.quantity(request.getParentQuantity(), "母件用量", true);
    boolean reference = "REFERENCE".equals(request.getEntryMode());
    Source referenceSource = reference ? sources.require(scope.task(), scope.product(), request.getReferenceParentNodeId(), request.getReferenceFingerprint()) : null;
    if (!reference && (request.getReferenceParentNodeId() != null || request.getReferenceFingerprint() != null)) throw invalid("自行录入不能带入参考页的母件来源");
    var evidence = referenceSource == null ? null : referenceSource.evidence();
    var packaging = new Packaging(evidence == null ? null : evidence.topProductCode(), evidence == null ? null : evidence.parentMaterialNo(),
        evidence == null ? null : evidence.parentQuantity(), parentQuantity, TechnicalDataPackageRules.PARENT_UNIT,
        evidence == null ? null : "U9:" + evidence.parentNodeId(), request.getEntryMode(), evidence);
    var sourceCache = new HashMap<Long, Source>();
    if (referenceSource != null) sourceCache.put(evidence.parentNodeId(), referenceSource);
    var items = new ArrayList<QuoteTechPackageItem>();
    for (var input : request.getItems()) {
      if (input == null || !input.getUnknownFields().isEmpty()) throw invalid("包装子件包含未知字段");
      items.add(item(scope, input, referenceSource, sourceCache, items.size() + 1));
    }
    var issues = TechnicalDataPackageRules.validate(packaging, items);
    // 草稿可缺母件用量或尚未选子件；已填写的行必须真实完整，提交另做完整性校验。
    if (issues.stream().anyMatch(message -> message.startsWith("第 "))) throw invalid(String.join("；", issues));
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var draft = draft(scope.product(), actor, now);
    repository.deleteAllPackageItemsIfDraft(draft.getId());
    if (!items.isEmpty() && repository.insertPackageItemsIfDraft(draft.getId(), items) != items.size()) throw conflict("包装草稿保存失败");
    draft.setPackagingJson(codec.packagingJson(packaging));
    // 新包装成本由已确认价格计算；此处不复制旧的价格或金额。
    draft.setPackageTotalAmount(BigDecimal.ZERO); draft.setUpdatedBy(actor.userId());
    if (repository.updateDraftVersion(draft, draft.getRowVersion(), now) != 1) throw conflict("包装草稿已变化，请刷新后保存");
    var module = scope.module(); module.setEntryMode(request.getEntryMode()); module.setCurrentVersionId(draft.getId());
    module.setModuleStatus(issues.isEmpty() ? "READY" : "EDITING");
    module.setReferenceSourceType(evidence == null ? null : "U9_BOM");
    module.setReferenceSourceId(evidence == null ? null : evidence.parentNodeId().toString());
    module.setReferenceSourceVersion(evidence == null ? null : evidence.buildBatchId());
    module.setReferenceFingerprint(evidence == null ? null : evidence.fingerprint());
    module.setReferenceSnapshotJson(evidence == null ? null : codec.packagingJson(packaging));
    module.setReferencedAt(evidence == null ? null : now);
    module.setLastValidationCode(issues.isEmpty() ? "PACKAGE_VERIFIED" : "PACKAGE_INCOMPLETE");
    module.setLastValidationMessage(issues.isEmpty() ? "包装母子用量已核实" : String.join("；", issues));
    if (repository.updateModule(module, module.getRowVersion(), now) != 1) throw conflict("包装模块已变化，请刷新后保存");
    scope.product().setCurrentEditVersionId(draft.getId()); scope.product().setProductStatus("EDITING");
    if (repository.updateProductPointers(scope.product(), request.getExpectedVersion(), now) != 1) throw conflict("产品资料已变化，请刷新后保存");
    repository.markTaskInProgress(scope.task().getId(), actor.userId(), now);
    return get(productId, null, actor);
  }

  private QuoteTechPackageItem item(Scope scope, TechnicalDataPackageItemRequest input, Source reference, Map<Long, Source> cache, int line) {
    var item = new QuoteTechPackageItem(); item.setLineNo(line); item.setSortSeq(line);
    item.setQuantity(TechnicalDataPackageRules.quantity(input.getQuantity(), "第 " + line + " 行子件用量", false));
    PackageItemEvidence evidence;
    if (input.getSourceNodeId() != null) {
      Long parentId = input.getSourceParentNodeId();
      Source source = cache.computeIfAbsent(parentId, id -> sources.require(scope.task(), scope.product(), id, input.getSourceFingerprint()));
      if (!Objects.equals(source.evidence().fingerprint(), input.getSourceFingerprint())) throw conflict("子件来源已变化，请重新选择");
      if (reference != null && !Objects.equals(parentId, reference.evidence().parentNodeId())) throw invalid("参考方式只能使用所选包装母件的实际子件");
      var child = source.children().stream().filter(row -> Objects.equals(row.sourceNodeId(), input.getSourceNodeId())).findFirst()
          .orElseThrow(() -> invalid("所选子件不是该包装母件的直接下级"));
      item.setComponentMaterialNo(child.materialNo()); item.setComponentName(child.name()); item.setComponentSpec(child.specification());
      item.setOriginalUnit(text(child.unit(), 32, "来源子件单位", true));
      item.setSourceReferenceId(child.sourceNodeId().toString()); item.setSourceReferenceVersion(source.evidence().buildBatchId());
      evidence = new PackageItemEvidence("U9_BOM", child.model(), parentId, child.sourceNodeId(), source.evidence().topProductCode(),
          source.evidence().buildBatchId(), child.path(), source.evidence().fingerprint(), child.quantity(), child.unit());
    } else {
      if (reference != null || input.getSourceParentNodeId() != null || input.getSourceFingerprint() != null) throw invalid("参考包装子件必须有实际来源节点");
      String model = text(input.getComponentModel(), 64, "新增子件型号", true);
      item.setComponentMaterialNo(model); item.setComponentName(text(input.getComponentName(), 255, "新增子件名称", true));
      item.setComponentSpec(text(input.getComponentSpec(), 255, "新增子件规格", false));
      item.setOriginalUnit(text(input.getUnit(), 32, "新增子件单位", true));
      evidence = new PackageItemEvidence("MANUAL", model, null, null, null, null, null, null, null, null);
    }
    item.setStandardQuantity(item.getQuantity()); item.setStandardUnit(item.getOriginalUnit()); item.setConversionFactor(BigDecimal.ONE);
    item.setSourceSnapshotJson(codec.packageEvidenceJson(evidence)); item.setRemark(text(input.getRemark(), 1000, "备注", false));
    return item;
  }

  /** 冻结前再次校验来源仍有效，历史阅读不随公共 BOM 变化。 */
  public List<String> validateCurrent(QuoteTechProduct product, QuoteTechDataVersion version) {
    var packaging = codec.packaging(version); var items = repository.findPackageItems(version.getId());
    var issues = new ArrayList<>(TechnicalDataPackageRules.validate(packaging, items));
    if (!issues.isEmpty()) return issues;
    var task = repository.findTask(product.getTaskId()).orElseThrow();
    var cache = new HashMap<Long, Source>();
    try {
      if (packaging.source() != null) cache.put(packaging.source().parentNodeId(), sources.require(task, product, packaging.source().parentNodeId(), packaging.source().fingerprint()));
      for (var item : items) {
        var evidence = codec.packageEvidence(item);
        if (evidence == null) { issues.add("包装子件缺少来源说明"); continue; }
        if ("U9_BOM".equals(evidence.kind())) {
          var source = cache.computeIfAbsent(evidence.parentNodeId(), id -> sources.require(task, product, id, evidence.sourceFingerprint()));
          if (!source.evidence().fingerprint().equals(evidence.sourceFingerprint())
              || source.children().stream().noneMatch(child -> Objects.equals(child.sourceNodeId(), evidence.sourceNodeId())
                  && Objects.equals(child.materialNo(), item.getComponentMaterialNo()) && Objects.equals(child.unit(), item.getOriginalUnit()))) issues.add("包装子件来源已变化，请重新查询");
        } else if (!"MANUAL".equals(evidence.kind()) || !Objects.equals(evidence.model(), item.getComponentMaterialNo())) issues.add("新增包装料号必须与填写的型号一致");
      }
    } catch (RuntimeException exception) { issues.add(exception.getMessage() == null ? "包装来源检查失败，请重新查询" : exception.getMessage()); }
    return List.copyOf(issues);
  }

  private Scope scope(Long productId, TechnicalDataActor actor, Integer expected) {
    var found = repository.findProduct(productId).orElseThrow(() -> invalid("补录产品不存在"));
    var task = (expected == null ? repository.findTask(found.getTaskId()) : repository.lockTask(found.getTaskId())).orElseThrow();
    var product = expected == null ? found : repository.lockProduct(productId).orElseThrow();
    if (expected != null && product.getCurrentEditVersionId() != null) repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
    var modules = expected == null ? tasks.findModules(productId) : repository.lockModules(productId);
    if (actor == null || !actor.canReadTask(task, modules)) throw forbidden("无权读取此补录产品");
    var module = modules.stream().filter(row -> "PACKAGE".equals(row.getModuleType())).findFirst().orElseThrow(() -> invalid("产品没有包装模块"));
    if (expected != null && !Objects.equals(product.getRowVersion(), expected)) throw conflict("资料已被其他会话修改，请刷新后重试");
    return new Scope(task, product, module);
  }

  private QuoteTechDataVersion draft(QuoteTechProduct product, TechnicalDataActor actor, LocalDateTime now) {
    if (product.getCurrentEditVersionId() != null) {
      var draft = repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
      if (!Objects.equals(draft.getProductId(), product.getId()) || !"DRAFT".equals(draft.getVersionStatus())) throw conflict("当前版本不能覆盖");
      return draft;
    }
    var draft = new QuoteTechDataVersion(); var profile = snapshots.readProfile(product.getSourceSnapshotJson());
    draft.setProductId(product.getId()); draft.setVersionNo(repository.maxVersionNo(product.getId()) + 1);
    draft.setVersionStatus("DRAFT"); draft.setContentSchemaVersion(2); draft.setProductModel(profile.productModel());
    draft.setNewProductFlag(Boolean.TRUE.equals(profile.newProduct()) ? 1 : 0);
    draft.setPackageTotalAmount(BigDecimal.ZERO); draft.setAuxiliaryTotalAmount(BigDecimal.ZERO); draft.setSalaryTotalAmount(BigDecimal.ZERO);
    draft.setRowVersion(0); draft.setCreatedBy(actor.userId()); draft.setUpdatedBy(actor.userId()); draft.setCreatedAt(now); draft.setUpdatedAt(now);
    return repository.insertVersion(draft);
  }

  private String text(String value, int max, String label, boolean required) {
    String normalized = value == null ? null : value.trim();
    if (required && (normalized == null || normalized.isEmpty())) throw invalid(label + "不能为空");
    if (normalized != null && normalized.length() > max) throw invalid(label + "最多 " + max + " 字");
    return normalized;
  }
  private record Scope(QuoteTechTask task, QuoteTechProduct product, QuoteTechModule module) {}
  private static TechnicalDataTaskException invalid(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.INVALID_REQUEST, message); }
  private static TechnicalDataTaskException forbidden(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, message); }
  private static TechnicalDataTaskException conflict(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, message); }
}
