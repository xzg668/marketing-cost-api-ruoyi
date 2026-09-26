package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataDrawingRecheckRequest;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataDrawingResponse;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.DrawingBom;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.DrawingEvidence;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.DrawingNode;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingExcelAcquisitionException;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingExcelAcquisitionPort.AcquiredExcel;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingProductLookup;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceNodeRepository;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkContext;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowOrchestrator;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowOrchestrator.WorkflowCommand;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 图库模块的授权、真实复查与草稿保存；采集、导入和料号匹配仍由既有图库链完成。 */
@Service
public class TechnicalDataDrawingApplicationService {
  private final TechnicalDataReadPolicy readPolicy;
  private final TechnicalDataSharedModules sharedModules;
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository tasks;
  private final TechnicalDataVersionContentCodec codec;
  private final ElectronicDrawingWorkflowContextPort contexts;
  private final ElectronicDrawingWorkflowOrchestrator workflow;
  private final ElectronicDrawingProductLookup productLookup;
  private final ElectronicDrawingSourceNodeRepository sourceNodes;
  private final QuoteBomSupplementVersionMapper sources;
  private final TechnicalDataSourceSnapshotFactory snapshots;
  private final TransactionTemplate transaction;

  public TechnicalDataDrawingApplicationService(QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository tasks, TechnicalDataVersionContentCodec codec,
      ElectronicDrawingWorkflowContextPort contexts, ElectronicDrawingWorkflowOrchestrator workflow,
      ElectronicDrawingProductLookup productLookup, ElectronicDrawingSourceNodeRepository sourceNodes,
      QuoteBomSupplementVersionMapper sources, TechnicalDataSourceSnapshotFactory snapshots,
      PlatformTransactionManager transactionManager, TechnicalDataSharedModules sharedModules, TechnicalDataReadPolicy readPolicy) {
    this.readPolicy=readPolicy;
    this.sharedModules = sharedModules;
    this.repository = repository; this.tasks = tasks; this.codec = codec; this.contexts = contexts;
    this.workflow = workflow; this.productLookup = productLookup; this.sourceNodes = sourceNodes;
    this.sources = sources; this.snapshots = snapshots;
    this.transaction = new TransactionTemplate(transactionManager);
  }

  public TechnicalDataDrawingResponse read(Long productId, Long versionId, TechnicalDataActor actor) {
    Scope scope = scope(productId, actor, false, null);
    Long selected = readPolicy.readVersion(scope.product(), scope.module(), actor, versionId);
    var version = selected == null ? null : repository.findVersion(selected).orElseThrow(() -> invalid("图库补录版本不存在"));
    if (version != null && !Objects.equals(version.getProductId(), productId)) throw forbidden("不能读取其他产品版本");
    var drawing = codec.drawingBom(version);
    var context = context(scope);
    var source = drawing == null ? null : sources.selectById(drawing.sourceVersionId());
    if (source != null && (!Objects.equals(source.getOaFormItemId(), scope.product().getOaFormItemId())
        || !Objects.equals(source.getPeriodMonth(), scope.product().getAccountingMonth()))) throw invalid("图库来源归属不一致");
    var nodes = source == null ? List.<ElectronicDrawingSourceNode>of() : sourceNodes.findByVersionId(source.getId());
    boolean matched = !nodes.isEmpty() && nodes.stream().allMatch(TechnicalDataDrawingApplicationService::matched);
    boolean historical = versionId != null || version != null && !"DRAFT".equals(version.getVersionStatus());
    boolean acquired = TechnicalDataDrawingRules.validate(drawing, scope.product()).isEmpty()
        && (historical || "DRAWING_SOURCE_VERIFIED".equals(scope.module().getLastValidationCode()));
    boolean composed = source != null && source.getCompositionFingerprint() != null;
    return new TechnicalDataDrawingResponse(productId, scope.product().getRowVersion(), selected,
        scope.product().getAccountingMonth(), !historical && actor.canEditModule(scope.task(), scope.module()),
        acquired, historical ? "显示本次提交时的图库明细" : scope.module().getLastValidationMessage(),
        matched, composed, composed && "APPROVED".equals(source.getVersionStatus()),
        drawing != null && !Objects.equals(drawing.sourceVersionId(), context.sourceVersionId()),
        drawing, productLookup.options(context), nodes.stream().map(node -> new TechnicalDataDrawingResponse.Resolution(
            node.getId().toString(), node.getResolvedMaterialCode(), node.getMatchStatus())).toList(),
        context.workflowId(), actor.canViewSupplementOverview());
  }

  public TechnicalDataDrawingResponse recheck(Long productId, TechnicalDataDrawingRecheckRequest request,
      TechnicalDataActor actor) {
    if (request == null || !request.getUnknownFields().isEmpty() || !Boolean.TRUE.equals(request.getMaintained())
        || request.getExpectedVersion() == null || request.getExpectedVersion() < 0) {
      throw invalid("请勾选已维护本产品图库明细，并提供当前资料版本；不接受客户端自填明细或完成状态");
    }
    var scope = scope(productId, actor, false, request.getExpectedVersion());
    requireEditable(scope, actor);
    var context = context(scope);
    String drawing = productLookup.requireDrawing(context, request.getDrawingNo());
    var command = new WorkflowCommand(context.workflowId(), context.oaFormItemId(), context.businessUnitType(),
        context.applicableOrgCode(), drawing, scope.task().getCreatedBy(), "财务报价", context.accountingMonth());
    AcquiredExcel acquired;
    try {
      acquired = workflow.acquire(command);
    } catch (ElectronicDrawingExcelAcquisitionException exception) {
      recordFailure(productId, request.getExpectedVersion(), actor, exception.getCode(), exception.getMessage());
      return read(productId, null, actor);
    }
    try {
      transaction.executeWithoutResult(status -> {
        var locked = scope(productId, actor, true, request.getExpectedVersion());
        requireEditable(locked, actor);
        sharedModules.requireOwnership(productId, "DRAWING_BOM");
        var current = context(locked);
        if (!Objects.equals(current.revision(), context.revision())
            || !Objects.equals(current.sourceVersionId(), context.sourceVersionId())) {
          throw conflict("取数期间图库来源或财务选择已变化，请刷新后重新检查");
        }
        var refreshed = workflow.refreshSource(command, acquired);
        save(locked, snapshot(refreshed), actor);
      });
    } catch (TechnicalDataTaskException exception) {
      throw exception;
    } catch (IllegalArgumentException | com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceImportException exception) {
      recordFailure(productId, request.getExpectedVersion(), actor, "DRAWING_SOURCE_INVALID", exception.getMessage());
    }
    return read(productId, null, actor);
  }

  private DrawingBom snapshot(ElectronicDrawingWorkContext context) {
    var source = sources.selectById(context.sourceVersionId());
    var rows = sourceNodes.findByVersionId(context.sourceVersionId());
    if (source == null || rows.isEmpty()) throw invalid("图库没有返回有效明细");
    return TechnicalDataDrawingSnapshot.from(context, source, rows);
  }

  private void save(Scope scope, DrawingBom drawing, TechnicalDataActor actor) {
    var issues = TechnicalDataDrawingRules.validate(drawing, scope.product());
    if (!issues.isEmpty()) throw new IllegalArgumentException(String.join("；", issues));
    var product = scope.product();
    var now = now();
    var draft = product.getCurrentEditVersionId() == null ? newDraft(product, actor, now)
        : repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
    if (!Objects.equals(draft.getProductId(), product.getId()) || !"DRAFT".equals(draft.getVersionStatus())) {
      throw conflict("当前资料已提交，请刷新后查看");
    }
    draft.setDrawingBomJson(codec.drawingBomJson(drawing));
    draft.setUpdatedBy(actor.userId());
    if (repository.updateDraftVersion(draft, draft.getRowVersion(), now) != 1) throw conflict("图库草稿已变化");
    var module = scope.module();
    module.setEntryMode("MANUAL");
    module.setCurrentVersionId(draft.getId());
    module.setModuleStatus("READY");
    module.setReferenceSourceType("ELECTRONIC_DRAWING_EXCEL");
    module.setReferenceSourceId(drawing.sourceVersionId().toString());
    module.setReferenceFingerprint(drawing.evidence().fileSha256());
    module.setReferencedAt(now);
    module.setLastValidationCode("DRAWING_SOURCE_VERIFIED");
    module.setLastValidationMessage("电子图库明细已取得并校验，可继续下一项；未匹配的 U9 料号由财务确认");
    updateModule(module, now);
    product.setCurrentEditVersionId(draft.getId());
    product.setProductStatus("EDITING");
    updateProduct(product, now);
    if ("PENDING".equals(scope.task().getTaskStatus())) repository.markTaskInProgress(scope.task().getId(), actor.userId(), now);
  }

  private QuoteTechDataVersion newDraft(QuoteTechProduct product, TechnicalDataActor actor, LocalDateTime now) {
    var draft = new QuoteTechDataVersion();
    draft.setProductId(product.getId()); draft.setVersionNo(repository.maxVersionNo(product.getId()) + 1);
    draft.setVersionStatus("DRAFT"); draft.setContentSchemaVersion(2);
    var profile = snapshots.readProfile(product.getSourceSnapshotJson());
    draft.setProductModel(profile.productModel()); draft.setNewProductFlag(Boolean.TRUE.equals(profile.newProduct()) ? 1 : 0);
    draft.setPackageTotalAmount(BigDecimal.ZERO); draft.setAuxiliaryTotalAmount(BigDecimal.ZERO); draft.setSalaryTotalAmount(BigDecimal.ZERO);
    draft.setRowVersion(0); draft.setCreatedBy(actor.userId()); draft.setUpdatedBy(actor.userId());
    draft.setCreatedAt(now); draft.setUpdatedAt(now);
    return repository.insertVersion(draft);
  }

  private void recordFailure(Long productId, int expectedVersion, TechnicalDataActor actor, String code, String message) {
    transaction.executeWithoutResult(status -> {
      var scope = scope(productId, actor, true, expectedVersion);
      requireEditable(scope, actor);
      sharedModules.requireOwnership(productId, "DRAWING_BOM");
      var module = scope.module();
      module.setModuleStatus("EDITING"); module.setLastValidationCode(code);
      module.setLastValidationMessage("本次图库复查未通过：" + (message == null ? "请稍后重试" : message.substring(0, Math.min(450, message.length()))));
      updateModule(module, now());
      updateProduct(scope.product(), now());
    });
  }

  private Scope scope(Long productId, TechnicalDataActor actor, boolean lock, Integer expected) {
    var found = repository.findProduct(productId).orElseThrow(() -> invalid("补录产品不存在"));
    var task = (lock ? repository.lockTask(found.getTaskId()) : repository.findTask(found.getTaskId())).orElseThrow();
    var product = lock ? repository.lockProduct(productId).orElseThrow() : found;
    if (lock && product.getCurrentEditVersionId() != null) repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
    var modules = lock ? repository.lockModules(productId) : tasks.findModules(productId);
    if (actor == null || !actor.canReadTask(task, modules)) throw forbidden("无权读取此补录产品");
    var module = modules.stream().filter(row -> "DRAWING_BOM".equals(row.getModuleType())).findFirst()
        .orElseThrow(() -> invalid("产品没有电子图库模块"));
    if (expected != null && !Objects.equals(product.getRowVersion(), expected)) throw conflict("资料已被其他会话修改，请刷新后重查");
    return new Scope(task, product, module);
  }

  private void requireEditable(Scope scope, TechnicalDataActor actor) {
    if (!Integer.valueOf(1).equals(scope.product().getActiveFlag()) || !Integer.valueOf(2).equals(scope.product().getContentSchemaVersion())
        || !actor.canEditModule(scope.task(), scope.module())) throw forbidden("此图库模块不属于本人，或已提交审批");
    if (!"MISSING".equals(scope.module().getSourceAvailability())) throw invalid("请先确认本产品缺少图库资料，再办理补录");
  }

  private ElectronicDrawingWorkContext context(Scope scope) {
    return contexts.load(scope.product().getOaFormItemId(), scope.task().getBusinessUnitType(),
        scope.task().getApplicableOrgCode(), scope.product().getAccountingMonth());
  }

  private void updateModule(QuoteTechModule module, LocalDateTime now) {
    if (repository.updateModule(module, module.getRowVersion(), now) != 1) throw conflict("模块状态已变化");
  }

  private void updateProduct(QuoteTechProduct product, LocalDateTime now) {
    if (repository.updateProductPointers(product, product.getRowVersion(), now) != 1) throw conflict("产品资料已变化");
  }

  private static boolean matched(ElectronicDrawingSourceNode node) {
    return Set.of(ElectronicDrawingSourceNode.MATCH_AUTO, ElectronicDrawingSourceNode.MATCH_MANUAL).contains(Objects.toString(node.getMatchStatus(), ""))
        && node.getResolvedMaterialCode() != null && !node.getResolvedMaterialCode().isBlank();
  }

  private static LocalDateTime now() { return LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE).truncatedTo(ChronoUnit.SECONDS); }
  private static TechnicalDataTaskException invalid(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.INVALID_REQUEST, message); }
  private static TechnicalDataTaskException forbidden(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, message); }
  private static TechnicalDataTaskException conflict(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, message); }
  private record Scope(QuoteTechTask task, QuoteTechProduct product, QuoteTechModule module) {}
}
