package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.NetLoss;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.service.NetLossRateQuery;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 净损失率草稿、来源核验及本人提交边界，复用现有产品版本与审批。 */
@Service
public class TechnicalDataNetLossApplicationService {
  private final TechnicalDataSharedModules sharedModules;
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository tasks;
  private final TechnicalDataVersionContentCodec codec;
  private final TechnicalDataSourceSnapshotFactory snapshots;
  private final NetLossRateQuery sources;
  private final OaMessageCodec json;

  public TechnicalDataNetLossApplicationService(QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository tasks, TechnicalDataVersionContentCodec codec,
      TechnicalDataSourceSnapshotFactory snapshots, NetLossRateQuery sources, OaMessageCodec json, TechnicalDataSharedModules sharedModules) {
    this.sharedModules = sharedModules;
    this.repository = repository; this.tasks = tasks; this.codec = codec; this.snapshots = snapshots;
    this.sources = sources; this.json = json;
  }

  @Transactional(readOnly = true)
  public TechnicalDataNetLossResponse get(Long productId, Long versionId, TechnicalDataActor actor) {
    var scope = scope(productId, actor, null);
    Long selected = versionId != null ? versionId
        : Set.of("FROZEN", "SUBMITTED", "APPROVED").contains(scope.module().getModuleStatus())
            ? scope.module().getCurrentVersionId() : scope.product().getCurrentEditVersionId();
    var version = selected == null ? null : repository.findVersion(selected).orElseThrow(() -> invalid("净损失率版本不存在"));
    if (version != null && !Objects.equals(version.getProductId(), productId)) throw forbidden("不能读取其他产品的净损失率版本");
    boolean historical = versionId != null || version != null && !"DRAFT".equals(version.getVersionStatus());
    var content = codec.netLoss(version);
    var issues = !historical && Integer.valueOf(1).equals(scope.module().getRequiredFlag())
        ? TechnicalDataNetLossRules.validate(content) : List.<String>of();
    var publicSource = versionId == null
        ? sources.lookup(scope.product().getMaterialNo(), snapshots.readProfile(scope.product().getSourceSnapshotJson()).productModel(),
            materialOrg(scope.task()), year(scope.product()), scope.task().getBusinessUnitType()) : null;
    // 当前页展示公共优先的选择；查看明确历史版本时只展示原快照，不重算历史取值。
    var applicableRate = publicSource == null ? null : NetLossRateQuery.select(publicSource,
        "MISSING".equals(publicSource.status()) && "APPROVED".equals(scope.module().getModuleStatus()) && version != null
            && "APPROVED".equals(version.getVersionStatus())
            ? TechnicalDataNetLossRules.costingInput(content) : null);
    return new TechnicalDataNetLossResponse(scope.task().getId(), productId, scope.product().getRowVersion(),
        selected, version == null ? null : version.getVersionStatus(), scope.module().getModuleStatus(),
        !historical && actor.canEditModule(scope.task(), scope.module()), historical, content, issues, publicSource, applicableRate);
  }

  @Transactional(readOnly = true)
  public List<TechnicalDataNetLossReference> references(Long productId, String searchBy, String keyword, TechnicalDataActor actor) {
    var scope = scope(productId, actor, null);
    return sources.search(searchBy, keyword, materialOrg(scope.task()), year(scope.product()), scope.task().getBusinessUnitType())
        .stream().map(source -> new TechnicalDataNetLossReference(source, json.canonicalHash(source))).toList();
  }

  @Transactional
  public TechnicalDataNetLossResponse save(Long productId, TechnicalDataNetLossSaveRequest request, TechnicalDataActor actor) {
    if (request == null || request.getExpectedVersion() == null || request.getExpectedVersion() < 0
        || !request.getUnknownFields().isEmpty()
        || !Set.of("REFERENCE", "MANUAL").contains(Objects.toString(request.getEntryMode(), ""))) throw invalid("净损失率输入不完整或包含不支持的字段");
    var scope = scope(productId, actor, request.getExpectedVersion());
    if (!Integer.valueOf(2).equals(scope.product().getContentSchemaVersion()) || !Integer.valueOf(1).equals(scope.product().getActiveFlag())
        || !actor.canEditModule(scope.task(), scope.module())) throw forbidden("当前净损失率模块未分派给本人或已送审");
    sharedModules.requireOwnership(productId, "NET_LOSS");
    if (!"MISSING".equals(scope.module().getSourceAvailability())) throw invalid("请先核实本产品的净损失率缺口");
    requireMissing(scope.task(), scope.product());
    boolean reference = "REFERENCE".equals(request.getEntryMode());
    NetLoss content;
    if (reference) {
      if (request.getPercent() != null) throw invalid("参考费率不能手工修改，请切换直接填写");
      var selected = requireReference(scope.task(), scope.product(), request.getReferenceMaterialNo(), request.getReferenceFingerprint());
      content = new NetLoss(selected.source().bareMaterialNo(), selected.source().rate(), "REFERENCE",
          "QUALITY_LOSS_RATE:" + selected.source().configurationId(), selected);
    } else {
      if (request.getReferenceMaterialNo() != null || request.getReferenceFingerprint() != null) throw invalid("直接填写不能混入参考来源");
      content = new NetLoss(null, TechnicalDataNetLossRules.ratio(request.getPercent()), "MANUAL", null, null);
    }
    var issues = TechnicalDataNetLossRules.validate(content);
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var draft = draft(scope.product(), actor, now);
    draft.setNetLossJson(codec.netLossJson(content)); draft.setUpdatedBy(actor.userId());
    if (repository.updateDraftVersion(draft, draft.getRowVersion(), now) != 1) throw conflict("净损失率草稿已变化，请刷新后保存");
    var module = scope.module(); module.setCurrentVersionId(draft.getId()); module.setEntryMode(request.getEntryMode());
    module.setModuleStatus(issues.isEmpty() ? "READY" : "EDITING");
    module.setReferenceSourceType(reference ? "QUALITY_LOSS_RATE" : null);
    module.setReferenceSourceId(reference ? content.reference().source().configurationId().toString() : null);
    module.setReferenceSourceVersion(reference ? content.reference().fingerprint() : null);
    module.setReferenceFingerprint(reference ? content.reference().fingerprint() : null);
    module.setReferenceSnapshotJson(reference ? codec.netLossJson(content) : null);
    module.setReferencedAt(reference ? now : null);
    module.setLastValidationCode(issues.isEmpty() ? "NET_LOSS_VERIFIED" : "NET_LOSS_INCOMPLETE");
    module.setLastValidationMessage(issues.isEmpty() ? "本次净损失率已核实" : String.join("；", issues));
    if (repository.updateModule(module, module.getRowVersion(), now) != 1) throw conflict("净损失率模块已变化，请刷新后保存");
    scope.product().setCurrentEditVersionId(draft.getId()); scope.product().setProductStatus("EDITING");
    if (repository.updateProductPointers(scope.product(), scope.product().getRowVersion(), now) != 1) throw conflict("产品资料已变化，请刷新后保存");
    return get(productId, null, actor);
  }

  public List<String> validateCurrent(QuoteTechProduct product, QuoteTechDataVersion version) {
    var content = codec.netLoss(version);
    var issues = new ArrayList<>(TechnicalDataNetLossRules.validate(content));
    if (!issues.isEmpty()) return List.copyOf(issues);
    try {
      var task = repository.findTask(product.getTaskId()).orElseThrow();
      requireMissing(task, product);
      if ("REFERENCE".equals(content.entryMode())) {
        requireReference(task, product, content.reference().source().materialNo(), content.reference().fingerprint());
      }
    } catch (RuntimeException exception) { issues.add(exception.getMessage() == null ? "净损失率来源检查失败，请重试" : exception.getMessage()); }
    return List.copyOf(issues);
  }

  private void requireMissing(QuoteTechTask task, QuoteTechProduct product) {
    var source = sources.lookup(product.getMaterialNo(), snapshots.readProfile(product.getSourceSnapshotJson()).productModel(),
        materialOrg(task), year(product), task.getBusinessUnitType());
    if ("AVAILABLE".equals(source.status())) throw conflict("本产品已取得公共净损失率，请先复查资料，无需继续补录");
    if (!"MISSING".equals(source.status())) throw invalid(source.message());
  }

  private TechnicalDataNetLossReference requireReference(QuoteTechTask task, QuoteTechProduct product, String code, String fingerprint) {
    if (code == null || code.isBlank() || code.length() > 64) throw invalid("请选择参考成品");
    var found = sources.search("CODE", code.trim(), materialOrg(task), year(product), task.getBusinessUnitType());
    if (found.size() != 1) throw invalid("参考成品不属于当前组织的有效成品档案，请重新查询");
    var source = found.getFirst();
    if (!"AVAILABLE".equals(source.status())) throw invalid(source.message());
    String current = json.canonicalHash(source);
    if (!Objects.equals(current, fingerprint)) throw conflict("参考净损失率或料品关系已变化，请重新查询；本次输入仍保留");
    return new TechnicalDataNetLossReference(source, current);
  }
  private static int year(QuoteTechProduct product) { return YearMonth.parse(product.getAccountingMonth()).getYear(); }
  private static String materialOrg(QuoteTechTask task) { return MaterialOrganization.fromPriceOrgCode(task.getApplicableOrgCode()).getCode(); }

  private Scope scope(Long productId, TechnicalDataActor actor, Integer expected) {
    var found = repository.findProduct(productId).orElseThrow(() -> invalid("补录产品不存在"));
    var task = (expected == null ? repository.findTask(found.getTaskId()) : repository.lockTask(found.getTaskId())).orElseThrow();
    var product = expected == null ? found : repository.lockProduct(productId).orElseThrow();
    if (expected != null && product.getCurrentEditVersionId() != null) repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
    var modules = expected == null ? tasks.findModules(productId) : repository.lockModules(productId);
    if (actor == null || !actor.canReadTask(task, modules)) throw forbidden("无权读取此补录产品");
    var module = modules.stream().filter(row -> "NET_LOSS".equals(row.getModuleType())).findFirst().orElseThrow(() -> invalid("产品没有净损失率模块"));
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
