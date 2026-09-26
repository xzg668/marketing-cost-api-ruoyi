package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSolderResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSolderSaveRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSolderSource.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 焊料模块的草稿和本人审批边界。保存既有版本 JSON，不新增明细表或独立流程。 */
@Service
public class TechnicalDataSolderApplicationService {
  private final TechnicalDataReadPolicy readPolicy;
  private final TechnicalDataSharedModules sharedModules;
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository tasks;
  private final TechnicalDataVersionContentCodec codec;
  private final TechnicalDataSourceSnapshotFactory snapshots;
  private final TechnicalDataSolderSourceQuery sources;
  private final TechnicalDataMaterialPriceQuery prices;

  public TechnicalDataSolderApplicationService(QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository tasks, TechnicalDataVersionContentCodec codec,
      TechnicalDataSourceSnapshotFactory snapshots, TechnicalDataSolderSourceQuery sources,
      TechnicalDataMaterialPriceQuery prices, TechnicalDataSharedModules sharedModules, TechnicalDataReadPolicy readPolicy) {
    this.readPolicy=readPolicy;
    this.sharedModules = sharedModules;
    this.repository = repository; this.tasks = tasks; this.codec = codec; this.snapshots = snapshots;
    this.sources = sources; this.prices = prices;
  }

  @Transactional(readOnly = true)
  public TechnicalDataSolderResponse get(Long productId, Long versionId, TechnicalDataActor actor) {
    var scope = scope(productId, actor, null);
    Long selected = readPolicy.readVersion(scope.product(), scope.module(), actor, versionId);
    var version = selected == null ? null : repository.findVersion(selected).orElseThrow(() -> invalid("焊料版本不存在"));
    if (version != null && !Objects.equals(version.getProductId(), productId)) throw forbidden("不能读取其他产品的焊料版本");
    boolean historical = versionId != null || version != null && !"DRAFT".equals(version.getVersionStatus());
    var content = codec.solder(version);
    var issues = !historical && Integer.valueOf(1).equals(scope.module().getRequiredFlag())
        ? TechnicalDataSolderRules.validate(content) : List.<String>of();
    return new TechnicalDataSolderResponse(scope.task().getId(), productId, scope.product().getRowVersion(),
        selected, version == null ? null : version.getVersionStatus(), scope.module().getModuleStatus(),
        !historical && actor.canEditModule(scope.task(), scope.module()), historical, content, issues,
        historical ? List.of() : prices.check(scope.task(), scope.product(), content));
  }

  @Transactional(readOnly = true)
  public List<Reference> references(Long productId, String keyword, TechnicalDataActor actor) {
    var scope = scope(productId, actor, null);
    return sources.references(scope.task(), scope.product(), keyword);
  }

  @Transactional(readOnly = true)
  public MaterialLookup material(Long productId, String materialNo, TechnicalDataActor actor) {
    return sources.material(scope(productId, actor, null).task(), materialNo);
  }

  @Transactional
  public TechnicalDataSolderResponse save(Long productId, TechnicalDataSolderSaveRequest request, TechnicalDataActor actor) {
    if (request == null || request.getExpectedVersion() == null || request.getExpectedVersion() < 0
        || !request.getUnknownFields().isEmpty() || request.getItems() == null || request.getItems().size() > 500
        || !Set.of("REFERENCE", "MANUAL").contains(Objects.toString(request.getEntryMode(), ""))) throw invalid("焊料输入不完整或包含不支持的字段");
    var scope = scope(productId, actor, request.getExpectedVersion());
    if (!Integer.valueOf(2).equals(scope.product().getContentSchemaVersion()) || !Integer.valueOf(1).equals(scope.product().getActiveFlag())
        || !actor.canEditModule(scope.task(), scope.module())) throw forbidden("当前焊料模块未分派给本人或已送审");
    sharedModules.requireOwnership(productId, "SOLDER");
    if (!"MISSING".equals(scope.module().getSourceAvailability())) throw invalid("请先核实本产品的焊料缺口");
    boolean reference = "REFERENCE".equals(request.getEntryMode());
    var source = reference ? sources.require(scope.task(), scope.product(), request.getReferenceMaterialNo(), request.getReferenceFingerprint()) : null;
    if (!reference && (request.getReferenceMaterialNo() != null || request.getReferenceFingerprint() != null)) throw invalid("新增焊料不能带入参考成品来源");
    var items = new ArrayList<SolderItem>();
    for (var input : request.getItems()) {
      if (input == null || !input.getUnknownFields().isEmpty()) throw invalid("焊料行包含不支持的字段，图号和来源不能手工修改");
      BigDecimal quantity = TechnicalDataSolderRules.quantity(input.getQuantityPerProduct());
      if (reference) {
        if (input.getMaterialNo() != null || input.getMaterialFingerprint() != null) throw invalid("参考焊料只能修改用量或删除");
        var original = source.items().stream().filter(row -> Objects.equals(row.itemKey(), input.getItemKey())).findFirst()
            .orElseThrow(() -> invalid("焊料来源行不属于所选参考成品"));
        items.add(new SolderItem(original.itemKey(), original.materialNo(), original.drawingNo(), quantity, "kg",
            original.sourceReference(), original.name(), original.evidence()));
      } else {
        if (input.getItemKey() != null) throw invalid("新增焊料不能带入参考节点");
        var master = sources.requireMaterial(scope.task(), input.getMaterialNo(), input.getMaterialFingerprint());
        items.add(new SolderItem("MANUAL:" + master.materialNo(), master.materialNo(), master.drawingNo(), quantity, "kg",
            "MATERIAL_MASTER:" + master.id(), master.name(), new ItemEvidence(master, null, null)));
      }
    }
    var content = new Solder(items, request.getEntryMode(), source == null ? null : source.evidence());
    var issues = TechnicalDataSolderRules.validate(content);
    // 草稿允许尚未填写用量；已填写的料号、来源和数量必须有效。
    var invalid = issues.stream().filter(message -> !message.endsWith("请填写用量") && !message.equals("请至少填写一条焊料明细")).toList();
    if (!invalid.isEmpty()) throw invalid(String.join("；", invalid));
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var draft = draft(scope.product(), actor, now);
    draft.setSolderItemsJson(codec.solderJson(content)); draft.setUpdatedBy(actor.userId());
    if (repository.updateDraftVersion(draft, draft.getRowVersion(), now) != 1) throw conflict("焊料草稿已变化，请刷新后保存");
    var module = scope.module(); module.setCurrentVersionId(draft.getId()); module.setEntryMode(request.getEntryMode());
    module.setModuleStatus(issues.isEmpty() ? "READY" : "EDITING");
    module.setReferenceSourceType(reference ? "U9_BOM" : null);
    module.setReferenceSourceId(reference ? source.evidence().materialNo() : null);
    module.setReferenceSourceVersion(reference ? source.evidence().fingerprint() : null);
    module.setReferenceFingerprint(reference ? source.evidence().fingerprint() : null);
    module.setReferenceSnapshotJson(reference ? codec.solderJson(content) : null);
    module.setReferencedAt(reference ? now : null);
    module.setLastValidationCode(issues.isEmpty() ? "SOLDER_VERIFIED" : "SOLDER_INCOMPLETE");
    module.setLastValidationMessage(issues.isEmpty() ? "焊料与每产品用量已核实" : String.join("；", issues));
    if (repository.updateModule(module, module.getRowVersion(), now) != 1) throw conflict("焊料模块已变化，请刷新后保存");
    scope.product().setCurrentEditVersionId(draft.getId()); scope.product().setProductStatus("EDITING");
    if (repository.updateProductPointers(scope.product(), scope.product().getRowVersion(), now) != 1) throw conflict("产品资料已变化，请刷新后保存");
    return get(productId, null, actor);
  }

  public List<String> validateCurrent(QuoteTechProduct product, QuoteTechDataVersion version) {
    var content = codec.solder(version);
    var issues = new ArrayList<>(TechnicalDataSolderRules.validate(content));
    if (!issues.isEmpty()) return List.copyOf(issues);
    try {
      var task = repository.findTask(product.getTaskId()).orElseThrow();
      if ("REFERENCE".equals(content.entryMode())) {
        var source = sources.require(task, product, content.reference().materialNo(), content.reference().fingerprint());
        for (var item : content.items()) {
          var original = source.items().stream().filter(row -> row.itemKey().equals(item.itemKey())).findFirst().orElseThrow(() -> invalid("原焊料节点已不属于参考成品"));
          if (!Objects.equals(item.evidence().material().fingerprint(), original.evidence().material().fingerprint())) issues.add("焊料料品档案已变化，请重新查询");
        }
      } else for (var item : content.items()) sources.requireMaterial(task, item.materialNo(), item.evidence().material().fingerprint());
    } catch (RuntimeException exception) { issues.add(exception.getMessage() == null ? "焊料来源检查失败，请重试" : exception.getMessage()); }
    return List.copyOf(issues);
  }

  private Scope scope(Long productId, TechnicalDataActor actor, Integer expected) {
    var found = repository.findProduct(productId).orElseThrow(() -> invalid("补录产品不存在"));
    var task = (expected == null ? repository.findTask(found.getTaskId()) : repository.lockTask(found.getTaskId())).orElseThrow();
    var product = expected == null ? found : repository.lockProduct(productId).orElseThrow();
    if (expected != null && product.getCurrentEditVersionId() != null) repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
    var modules = expected == null ? tasks.findModules(productId) : repository.lockModules(productId);
    if (actor == null || !actor.canReadTask(task, modules)) throw forbidden("无权读取此补录产品");
    var module = modules.stream().filter(row -> "SOLDER".equals(row.getModuleType())).findFirst().orElseThrow(() -> invalid("产品没有焊料模块"));
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
  private record Scope(QuoteTechTask task, QuoteTechProduct product, QuoteTechModule module) {}
  private static TechnicalDataTaskException invalid(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.INVALID_REQUEST, message); }
  private static TechnicalDataTaskException forbidden(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, message); }
  private static TechnicalDataTaskException conflict(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, message); }
}
