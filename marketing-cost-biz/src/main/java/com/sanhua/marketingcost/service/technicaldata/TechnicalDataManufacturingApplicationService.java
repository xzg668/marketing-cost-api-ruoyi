package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataManufacturingResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataManufacturingSaveRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.*;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.MakePartNoScrapConfirmationService;
import com.sanhua.marketingcost.service.MakePartScrapMappingService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomAssembler.Nature;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingHybridBomService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowOrchestrator;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowStage;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingSourceQuery.Assessment;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataManufacturingSourceQuery.State;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 只保存本人制造件模块；原始图库、正式 U9 及其他人的已填内容不回写。 */
@Service
public class TechnicalDataManufacturingApplicationService {
  private final TechnicalDataReadPolicy readPolicy;
  private static final Logger log = LoggerFactory.getLogger(TechnicalDataManufacturingApplicationService.class);
  private final TechnicalDataSharedModules sharedModules;
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository tasks;
  private final TechnicalDataVersionContentCodec codec;
  private final TechnicalDataSourceSnapshotFactory snapshots;
  private final ElectronicDrawingWorkflowContextPort contexts;
  private final TechnicalDataManufacturingSourceQuery sources;
  private final MaterialMasterRawMapper materials;
  private final MakePartScrapMappingService scraps;
  private final MakePartNoScrapConfirmationService noScrap;
  private final ElectronicDrawingHybridBomService hybridBom;
  private final ElectronicDrawingWorkflowOrchestrator workflow;
  private final TransactionTemplate transaction;
  private final TechnicalDataMaterialPriceQuery prices;

  public TechnicalDataManufacturingApplicationService(QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository tasks, TechnicalDataVersionContentCodec codec,
      TechnicalDataSourceSnapshotFactory snapshots, ElectronicDrawingWorkflowContextPort contexts,
      TechnicalDataManufacturingSourceQuery sources, MaterialMasterRawMapper materials,
      MakePartScrapMappingService scraps, MakePartNoScrapConfirmationService noScrap,
      ElectronicDrawingHybridBomService hybridBom, ElectronicDrawingWorkflowOrchestrator workflow,
      PlatformTransactionManager transactionManager, TechnicalDataMaterialPriceQuery prices, TechnicalDataSharedModules sharedModules, TechnicalDataReadPolicy readPolicy) {
    this.readPolicy=readPolicy;
    this.sharedModules = sharedModules;
    this.repository = repository; this.tasks = tasks; this.codec = codec; this.snapshots = snapshots;
    this.contexts = contexts; this.sources = sources; this.materials = materials; this.scraps = scraps; this.noScrap = noScrap;
    this.hybridBom = hybridBom; this.workflow = workflow;
    this.transaction = new TransactionTemplate(transactionManager);
    this.prices = prices;
  }

  public TechnicalDataManufacturingResponse read(Long productId, Long versionId, TechnicalDataActor actor) {
    var scope = scope(productId, actor, null);
    Long selected = readPolicy.readVersion(scope.product(), scope.module(), actor, versionId);
    var version = selected == null ? null : repository.findVersion(selected).orElseThrow(() -> invalid("制造件版本不存在"));
    if (version != null && !Objects.equals(version.getProductId(), productId)) throw forbidden("不能读取其他产品版本");
    var saved = codec.manufacturing(version);
    boolean historical = versionId != null || version != null && !"DRAFT".equals(version.getVersionStatus());
    var context = historical ? null : context(scope);
    boolean formalAvailable = !historical && "AVAILABLE".equals(scope.module().getSourceAvailability())
        && context.sourceVersionId() == null;
    Assessment source = historical || formalAvailable ? null : sources.inspect(context);
    var issues = !historical && !Objects.equals(scope.module().getRequiredFlag(), 1) ? List.<String>of()
        : saved == null ? List.<String>of("请先保存原材料或下级关系检查结果")
        : historical ? TechnicalDataManufacturingRules.validate(saved, scope.product()) : validate(scope.product(), saved, source);
    var inputs = saved == null || !TechnicalDataManufacturingRules.validate(saved, scope.product()).isEmpty()
        ? List.<TechnicalDataManufacturingResponse.CalculationInput>of()
        : saved.items().stream().map(TechnicalDataManufacturingRules::calculationInput).toList();
    boolean composed = context != null && context.compositionFingerprint() != null;
    String bomMessage = historical ? null : formalAvailable ? "来源检查已有正式 U9 下级关系，无需补录原材料"
        : composed ? "原材料关系已挂入本产品的 BOM，审批和报价确认通过后用于核算"
        : ElectronicDrawingWorkflowStage.VALIDATION_FAILED.equals(context.workflowStage())
            ? "资料已保存，BOM 检查尚未通过，请核实来源与下级关系后重新检查"
            : "BOM 尚未组好，补齐原材料并完成待确认项后重新检查";
    return new TechnicalDataManufacturingResponse(productId, scope.product().getRowVersion(), selected,
        !historical && actor.canEditModule(scope.task(), scope.module()), historical, source, saved, issues, inputs,
        composed, bomMessage,
        historical ? List.of() : prices.check(context, applicableRaw(saved, source)));
  }

  private Manufacturing applicableRaw(Manufacturing saved, Assessment source) {
    if (source == null || saved == null || saved.items() == null || saved.evidence() == null
        || !Objects.equals(saved.evidence().drawingSourceVersionId(), source.sourceVersionId())) return null;
    var applicable = saved.items().stream().filter(raw -> source.nodes().stream().anyMatch(node ->
        node.state() == State.MISSING_RAW && node.sourceNodeId().toString().equals(raw.parentSourceNodeId())
            && Objects.equals(node.parentMaterialNo(), raw.parentMaterialNo()))).toList();
    return new Manufacturing(applicable, saved.evidence());
  }

  public MaterialOption material(Long productId, String materialNo, TechnicalDataActor actor) {
    var scope = scope(productId, actor, null);
    var row = material(context(scope), materialNo);
    return new MaterialOption(row.getMaterialCode(), row.getMaterialName(), row.getDrawingNo(), row.getMaterialSpec(), row.getUnit());
  }

  public TechnicalDataManufacturingResponse save(Long productId, TechnicalDataManufacturingSaveRequest request, TechnicalDataActor actor) {
    transaction.executeWithoutResult(status -> saveDraft(productId, request, actor));
    var saved = read(productId, null, actor);
    if (saved.issues().isEmpty()) {
      var context = context(scope(productId, actor, null));
      try {
        workflow.resumeAfterMaterialSelection(context.workflowId(), context.businessUnitType(),
            context.applicableOrgCode(), context.accountingMonth());
      } catch (RuntimeException exception) {
        // 草稿已提交，后续组树失败不得谎报“保存失败”或恢复已删掉的旧关系。
        log.warn("manufacturing saved; BOM check pending: productId={} itemId={} month={}",
            productId, context.oaFormItemId(), context.accountingMonth(), exception);
      }
    }
    return read(productId, null, actor);
  }

  private void saveDraft(Long productId, TechnicalDataManufacturingSaveRequest request, TechnicalDataActor actor) {
    if (request == null || !request.getUnknownFields().isEmpty() || request.getExpectedVersion() == null
        || request.getExpectedVersion() < 0 || request.getItems() == null) throw invalid("制造件输入或并发版本不完整，不能修改来源字段");
    var scope = scope(productId, actor, request.getExpectedVersion());
    if (!Objects.equals(scope.product().getActiveFlag(), 1) || !Objects.equals(scope.product().getContentSchemaVersion(), 2)
        || !actor.canEditModule(scope.task(), scope.module())) throw forbidden("当前制造件模块未分派给本人或已提交审批");
    sharedModules.requireOwnership(productId, "MANUFACTURING");
    if (!"MISSING".equals(scope.module().getSourceAvailability())) throw invalid("制造件缺口尚未确认，请先检查来源并分派");
    var context = context(scope);
    var assessment = sources.inspect(context);
    if (!Objects.equals(assessment.sourceVersionId(), request.getSourceVersionId())
        || !Objects.equals(assessment.fingerprint(), request.getSourceFingerprint())) {
      throw conflict("图库料号或下级关系已变化，请刷新后核实；已有原材料资料保留");
    }
    var lines = new ArrayList<RawMaterial>();
    for (var input : request.getItems()) {
      if (input == null || !input.getUnknownFields().isEmpty()) throw invalid("原材料输入不能修改图库净重等来源字段");
      var parent = assessment.nodes().stream().filter(row -> Objects.equals(row.sourceNodeId(), input.getParentSourceNodeId()))
          .findFirst().orElseThrow(() -> invalid("原材料必须绑定本次图库的制造件节点"));
      if (parent.state() != State.MISSING_RAW) throw invalid("该节点已有下级关系、待财务确认或查询异常，不能补原材料");
      var raw = material(context, input.getRawMaterialNo());
      var mappings = scraps.listMappings(raw.getMaterialCode(), context.businessUnitType()).stream()
          .map(row -> new ManufacturingScrap(row.getScrapCode(), row.getScrapName(), row.getScrapUnit())).toList();
      var confirmation = mappings.isEmpty() ? noScrap.findEffective(raw.getMaterialCode(), context.accountingMonth(), context.businessUnitType()) : null;
      String scrapStatus = mappings.size() == 1 ? "MATCHED" : mappings.size() > 1 ? "AMBIGUOUS"
          : confirmation != null && "ACTIVE".equals(confirmation.getStatus()) ? "NO_SCRAP_CONFIRMED" : "MISSING";
      var evidence = new ManufacturingNodeEvidence(parent.name(), parent.drawingNo(), parent.quantityPerParent(),
          parent.sourceNetWeight(), parent.sourceNetWeightUnit(), raw.getMaterialName(), raw.getMaterialSpec(), scrapStatus, mappings);
      BigDecimal netG = TechnicalDataManufacturingUnits.sourceWeightG(parent.sourceNetWeight(), parent.sourceNetWeightUnit());
      lines.add(new RawMaterial("RAW:" + parent.sourceNodeId(), parent.sourceNodeId().toString(), assessment.sourceVersionId(),
          parent.parentMaterialNo(), raw.getMaterialCode(), raw.getDrawingNo(), netG.movePointLeft(3),
          TechnicalDataManufacturingUnits.purchasingQuantity(input.getGrossWeightKg(), raw.getUnit()), raw.getUnit(), null,
          input.getGrossWeightKg(), input.getNetLengthMm(), evidence));
    }
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE).truncatedTo(ChronoUnit.SECONDS);
    var content = new Manufacturing(lines, new ManufacturingEvidence(scope.product().getOaFormItemId(),
        scope.product().getAccountingMonth(), assessment.sourceVersionId(), assessment.fingerprint(), now));
    var invalid = TechnicalDataManufacturingRules.validate(content, scope.product());
    if (!invalid.isEmpty()) throw invalid(invalid.getFirst());
    var issues = validate(scope.product(), content, assessment);
    // 与草稿同一事务清除旧组树；已发布来源由服务明确拒绝，不能覆盖历史。
    hybridBom.invalidateDraftComposition(context);
    var draft = draft(scope.product(), actor, now);
    draft.setManufacturingJson(codec.manufacturingJson(content)); draft.setUpdatedBy(actor.userId());
    if (repository.updateDraftVersion(draft, draft.getRowVersion(), now) != 1) throw conflict("制造件草稿已变化，请刷新后保存");
    var module = scope.module();
    module.setCurrentVersionId(draft.getId()); module.setEntryMode("MANUAL");
    module.setModuleStatus(issues.isEmpty() ? "READY" : "EDITING");
    module.setReferenceSourceType("ELECTRONIC_DRAWING_EXCEL");
    module.setReferenceSourceId(assessment.sourceVersionId().toString()); module.setReferenceFingerprint(assessment.fingerprint());
    module.setReferencedAt(now); module.setLastValidationCode(issues.isEmpty() ? "MANUFACTURING_VERIFIED" : "MANUFACTURING_INCOMPLETE");
    module.setLastValidationMessage(issues.isEmpty() ? "制造件原材料关系已核实，资料按单件保存" : issues.getFirst());
    if (repository.updateModule(module, module.getRowVersion(), now) != 1) throw conflict("制造件模块已变化");
    scope.product().setCurrentEditVersionId(draft.getId()); scope.product().setProductStatus("EDITING");
    if (repository.updateProductPointers(scope.product(), scope.product().getRowVersion(), now) != 1) throw conflict("产品资料已变化");
    if ("PENDING".equals(scope.task().getTaskStatus())) repository.markTaskInProgress(scope.task().getId(), actor.userId(), now);
  }

  /** 送 OA 前按当前来源再次核查，不能只信上次保存时的 READY 标志。 */
  public List<String> validateCurrent(QuoteTechProduct product, Manufacturing content) {
    var task = repository.findTask(product.getTaskId()).orElseThrow(() -> invalid("制造件任务不存在"));
    var context = contexts.load(product.getOaFormItemId(), task.getBusinessUnitType(),
        task.getApplicableOrgCode(), product.getAccountingMonth());
    var issues = new ArrayList<>(validate(product, content, sources.inspect(context)));
    if (context.compositionFingerprint() == null) issues.add("制造件原材料尚未通过本产品 BOM 组树检查，请保存并重新检查后提交");
    if (issues.isEmpty()) {
      for (var row : content.items()) {
        try {
          var raw = material(context, row.rawMaterialNo());
          if (!Objects.equals(raw.getUnit(), row.unit())) issues.add("原材料采购单位已变化，请重新核实后提交");
        } catch (IllegalArgumentException | TechnicalDataTaskException exception) {
          issues.add(exception.getMessage());
        }
      }
    }
    return List.copyOf(issues);
  }

  private List<String> validate(QuoteTechProduct product, Manufacturing content, Assessment source) {
    var issues = new ArrayList<>(TechnicalDataManufacturingRules.validate(content, product));
    if (!issues.isEmpty()) return issues;
    if (!Objects.equals(source.fingerprint(), content.evidence().sourceFingerprint())
        && !sources.matchesFingerprint(source, content.evidence().sourceFingerprint())) {
      issues.add("图库料号或下级 BOM 已变化，请重新核实制造件资料");
    }
    if (source.hasUnresolved()) issues.add("仍有节点待财务确认或下级查询未成功，不能提交不完整的制造件资料");
    Set<String> required = source.nodes().stream().filter(row -> row.state() == State.MISSING_RAW)
        .map(row -> row.sourceNodeId().toString()).collect(java.util.stream.Collectors.toSet());
    Set<String> provided = content.items().stream().map(RawMaterial::parentSourceNodeId).collect(java.util.stream.Collectors.toSet());
    if (!required.equals(provided)) issues.add("请按当前缺口补齐原材料，已有下级关系的节点无需补录");
    return List.copyOf(issues);
  }

  private MaterialMasterRaw material(ElectronicDrawingWorkContext context, String code) {
    if (code == null || code.isBlank()) throw invalid("请填写采购原材料料号");
    var rows = materials.selectByLatestBatchAndCodes(Set.of(code.trim()), null, context.materialOrgCode());
    if (rows.size() != 1) throw invalid("当前物料组织未找到唯一原材料料号，请核实料品档案");
    var material = rows.getFirst();
    if (Nature.parse(material.getShapeAttr()) != Nature.PURCHASE) throw invalid("原材料必须选择采购件料号");
    TechnicalDataManufacturingUnits.purchasingQuantity(BigDecimal.ONE, material.getUnit());
    return material;
  }

  private Scope scope(Long productId, TechnicalDataActor actor, Integer expected) {
    var found = repository.findProduct(productId).orElseThrow(() -> invalid("补录产品不存在"));
    var task = (expected == null ? repository.findTask(found.getTaskId()) : repository.lockTask(found.getTaskId())).orElseThrow();
    var product = expected == null ? found : repository.lockProduct(productId).orElseThrow();
    if (expected != null && product.getCurrentEditVersionId() != null) repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
    var modules = expected == null ? tasks.findModules(productId) : repository.lockModules(productId);
    if (actor == null || !actor.canReadTask(task, modules)) throw forbidden("无权读取此补录产品");
    var module = modules.stream().filter(row -> "MANUFACTURING".equals(row.getModuleType())).findFirst().orElseThrow(() -> invalid("产品没有制造件模块"));
    if (expected != null && !Objects.equals(product.getRowVersion(), expected)) throw conflict("资料已被其他会话修改，请刷新后重试");
    return new Scope(task, product, module);
  }

  private QuoteTechDataVersion draft(QuoteTechProduct product, TechnicalDataActor actor, LocalDateTime now) {
    if (product.getCurrentEditVersionId() != null) {
      var draft = repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
      if (!Objects.equals(draft.getProductId(), product.getId()) || !"DRAFT".equals(draft.getVersionStatus())) throw conflict("当前资料已提交，不能覆盖");
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

  private ElectronicDrawingWorkContext context(Scope scope) {
    return contexts.load(scope.product().getOaFormItemId(), scope.task().getBusinessUnitType(), scope.task().getApplicableOrgCode(), scope.product().getAccountingMonth());
  }

  public record MaterialOption(String materialNo, String name, String drawingNo, String specification, String unit) {}
  private record Scope(QuoteTechTask task, QuoteTechProduct product, QuoteTechModule module) {}
  private static TechnicalDataTaskException invalid(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.INVALID_REQUEST, message); }
  private static TechnicalDataTaskException forbidden(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, message); }
  private static TechnicalDataTaskException conflict(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, message); }
}
