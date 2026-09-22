package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryContent.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 辅料参考/上传的事务边界；原始资料服务端取证，技术只编辑本次金额。 */
@Service
public class TechnicalDataAuxiliaryApplicationService {
  private final TechnicalDataSharedModules sharedModules;
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository tasks;
  private final TechnicalDataSourceSnapshotFactory snapshots;
  private final TechnicalDataVersionContentCodec codec;
  private final TechnicalDataAuxiliarySourceQuery sources;
  private final TechnicalDataAuxiliaryUploadParser parser;
  private final TechnicalDataAttachmentStore files;

  public TechnicalDataAuxiliaryApplicationService(QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository tasks, TechnicalDataSourceSnapshotFactory snapshots,
      TechnicalDataVersionContentCodec codec, TechnicalDataAuxiliarySourceQuery sources,
      TechnicalDataAuxiliaryUploadParser parser, TechnicalDataAttachmentStore files, TechnicalDataSharedModules sharedModules) {
    this.sharedModules = sharedModules;
    this.repository = repository; this.tasks = tasks; this.snapshots = snapshots;
    this.codec = codec; this.sources = sources; this.parser = parser; this.files = files;
  }

  @Transactional(readOnly = true)
  public TechnicalDataAuxiliaryResponse get(Long productId, Long versionId, TechnicalDataActor actor) {
    var scope = scope(productId, actor, null);
    Long selected = versionId != null ? versionId
        : Set.of("FROZEN", "SUBMITTED", "APPROVED").contains(scope.module().getModuleStatus())
            ? scope.module().getCurrentVersionId() : scope.product().getCurrentEditVersionId();
    var version = selected == null ? null : repository.findVersion(selected).orElseThrow(() -> invalid("辅料版本不存在"));
    if (version != null && !Objects.equals(version.getProductId(), productId)) throw forbidden("不能读取其他产品的辅料版本");
    var rows = version == null ? List.<QuoteTechAuxItem>of() : repository.findAuxItems(version.getId());
    boolean historical = versionId != null || version != null && !"DRAFT".equals(version.getVersionStatus());
    var issues = !historical && Objects.equals(scope.module().getRequiredFlag(), 1)
        ? TechnicalDataAuxiliaryRules.validate(rows, codec) : List.<String>of();
    var items = rows.stream().map(row -> {
      var evidence = codec.auxiliaryEvidence(row);
      BigDecimal original = evidence == null ? null : evidence.cms() != null ? evidence.cms().item().sourceAmount()
          : evidence.upload() != null ? evidence.upload().item().amountPerProduct() : null;
      return new TechnicalDataAuxiliaryResponse.Item(row.getId(), row.getLineNo(), row.getSourceReferenceId(),
          row.getAuxiliaryName(), original, row.getAmount(), evidence);
    }).toList();
    String mode = rows.isEmpty() ? scope.module().getEntryMode() : "UPLOAD_AMOUNT".equals(rows.getFirst().getPricingMethod()) ? "UPLOAD" : "REFERENCE";
    BigDecimal total = rows.stream().anyMatch(row -> row.getAmount() == null) ? null
        : rows.stream().map(QuoteTechAuxItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    return new TechnicalDataAuxiliaryResponse(scope.task().getId(), productId, scope.product().getMaterialNo(),
        scope.product().getProductName(), version == null ? snapshots.readProfile(scope.product().getSourceSnapshotJson()).productModel() : version.getProductModel(),
        scope.product().getAccountingMonth(), selected, version == null ? null : version.getVersionNo(), version == null ? null : version.getVersionStatus(),
        scope.product().getRowVersion(), scope.module().getModuleStatus(), mode,
        !historical && actor.canEditModule(scope.task(), scope.module()), historical, total, items, issues);
  }

  @Transactional(readOnly = true)
  public List<TechnicalDataAuxiliaryCmsSource> references(Long productId, String keyword, TechnicalDataActor actor) {
    var scope = scope(productId, actor, null);
    return sources.search(scope.task(), scope.product(), keyword);
  }

  @Transactional(readOnly = true)
  public TechnicalDataAuxiliaryUploadResponse preview(Long productId, String name, byte[] bytes, TechnicalDataActor actor) {
    ensureEditable(scope(productId, actor, null), actor);
    var parsed = parser.parse(name, bytes);
    if (!parsed.issues().isEmpty()) return parsed;
    var stored = files.save(productId, name, bytes);
    return parser.parse(stored.fileName(), stored.bytes());
  }

  @Transactional(readOnly = true)
  public TechnicalDataAttachmentStore.StoredFile file(Long productId, Long versionId, TechnicalDataActor actor) {
    var response = get(productId, versionId, actor);
    var evidence = response.items().isEmpty() ? null : response.items().getFirst().source();
    if (evidence == null || evidence.upload() == null) throw invalid("此辅料版本没有上传原表");
    return files.read(productId, evidence.upload().fileSha256());
  }

  @Transactional
  public TechnicalDataAuxiliaryResponse save(Long productId, TechnicalDataAuxiliarySaveRequest request, TechnicalDataActor actor) {
    if (request == null || request.getExpectedVersion() == null || request.getExpectedVersion() < 0
        || !request.getUnknownFields().isEmpty() || request.getItems() == null || request.getItems().size() > 5000
        || !Set.of("REFERENCE", "UPLOAD").contains(Objects.toString(request.getEntryMode(), ""))) throw invalid("辅料请求不完整或包含未知字段");
    var scope = scope(productId, actor, request.getExpectedVersion()); ensureEditable(scope, actor);
    sharedModules.requireOwnership(productId, "AUXILIARY");
    List<ItemEvidence> origins;
    if ("REFERENCE".equals(request.getEntryMode())) {
      if (request.getFileSha256() != null) throw invalid("参考方式不能带入上传文件");
      origins = cmsEvidence(sources.require(scope.task(), scope.product(), request.getReferenceMaterialNo(), request.getReferenceFingerprint()));
    } else {
      if (request.getReferenceMaterialNo() != null || request.getReferenceFingerprint() != null) throw invalid("上传方式不能带入参考成品");
      origins = uploadEvidence(productId, request.getFileSha256());
    }
    var inputs = new LinkedHashMap<String, TechnicalDataAuxiliaryItemRequest>();
    for (var input : request.getItems()) {
      if (input == null || input.getItemKey() == null || !input.getUnknownFields().isEmpty()
          || inputs.putIfAbsent(input.getItemKey(), input) != null) throw invalid("辅料行缺少标识、重复或包含不支持的字段");
      TechnicalDataAuxiliaryRules.amount(input.getAmount());
    }
    if (!inputs.keySet().equals(origins.stream().map(ItemEvidence::itemKey).collect(java.util.stream.Collectors.toSet()))) throw invalid("请保留所选来源的完整明细，不能省略或拼接其他来源行");
    var items = new ArrayList<QuoteTechAuxItem>();
    for (var origin : origins) items.add(item(origin, inputs.get(origin.itemKey()).getAmount(), items.size() + 1));
    var issues = TechnicalDataAuxiliaryRules.validate(items, codec);
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var draft = draft(scope.product(), actor, now);
    repository.deleteAllAuxItemsIfDraft(draft.getId());
    if (repository.insertAuxItemsIfDraft(draft.getId(), items) != items.size()) throw conflict("辅料草稿保存失败");
    // 完整金额才用于后续汇总；不完整草稿由模块状态和逐行校验阻挡送审。
    draft.setAuxiliaryTotalAmount(items.stream().filter(row -> row.getAmount() != null).map(QuoteTechAuxItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    draft.setUpdatedBy(actor.userId());
    if (repository.updateDraftVersion(draft, draft.getRowVersion(), now) != 1) throw conflict("辅料草稿已变化，请刷新后保存");
    var origin = origins.getFirst(); var module = scope.module();
    module.setEntryMode(request.getEntryMode()); module.setCurrentVersionId(draft.getId());
    module.setModuleStatus(issues.isEmpty() ? "READY" : "EDITING");
    module.setReferenceSourceType(origin.cms() != null ? "CMS_AUX_SUBJECT" : "EXCEL_UPLOAD");
    module.setReferenceSourceId(origin.cms() != null ? origin.cms().materialNo() : origin.upload().fileSha256());
    module.setReferenceSourceVersion(origin.cms() != null ? scope.product().getAccountingMonth() : origin.upload().fileSha256());
    module.setReferenceFingerprint(origin.cms() != null ? origin.cms().fingerprint() : origin.upload().fileSha256());
    module.setReferenceSnapshotJson(codec.auxiliaryEvidenceJson(origin)); module.setReferencedAt(now);
    module.setLastValidationCode(issues.isEmpty() ? "AUXILIARY_VERIFIED" : "AUXILIARY_INCOMPLETE");
    module.setLastValidationMessage(issues.isEmpty() ? "辅料明细及本次金额完整" : String.join("；", issues));
    if (repository.updateModule(module, module.getRowVersion(), now) != 1) throw conflict("辅料模块已变化，请刷新后保存");
    scope.product().setCurrentEditVersionId(draft.getId()); scope.product().setProductStatus("EDITING");
    if (repository.updateProductPointers(scope.product(), request.getExpectedVersion(), now) != 1) throw conflict("产品资料已变化，请刷新后保存");
    repository.markTaskInProgress(scope.task().getId(), actor.userId(), now);
    return get(productId, null, actor);
  }

  public List<String> validateCurrent(QuoteTechProduct product, QuoteTechDataVersion version) {
    var rows = repository.findAuxItems(version.getId());
    var issues = new ArrayList<>(TechnicalDataAuxiliaryRules.validate(rows, codec));
    if (!issues.isEmpty()) return issues;
    try {
      var first = codec.auxiliaryEvidence(rows.getFirst());
      var expected = first.cms() != null ? cmsEvidence(sources.require(repository.findTask(product.getTaskId()).orElseThrow(),
          product, first.cms().materialNo(), first.cms().fingerprint())) : uploadEvidence(product.getId(), first.upload().fileSha256());
      if (expected.size() != rows.size()) issues.add("辅料来源明细不完整，请重新选取");
      else for (int index = 0; index < rows.size(); index++) {
        if (!codec.sameAuxiliaryEvidence(expected.get(index), codec.auxiliaryEvidence(rows.get(index)))) {
          issues.add("辅料原始依据已变化或不完整，请重新选取"); break;
        }
      }
    } catch (RuntimeException exception) { issues.add(exception.getMessage() == null ? "辅料来源检查失败" : exception.getMessage()); }
    return List.copyOf(issues);
  }

  private List<ItemEvidence> cmsEvidence(TechnicalDataAuxiliaryCmsSource source) {
    return source.items().stream().map(row -> new ItemEvidence("CMS:" + row.sourceId(),
        new CmsEvidence(source.materialNo(), source.name(), source.model(), source.fingerprint(), row), null)).toList();
  }

  private List<ItemEvidence> uploadEvidence(Long productId, String hash) {
    var stored = files.read(productId, hash); var parsed = parser.parse(stored.fileName(), stored.bytes());
    if (!parsed.issues().isEmpty()) throw invalid("辅料文件有解析错误，请按工作表和行号修正后重新上传");
    return parsed.items().stream().map(row -> new ItemEvidence(row.itemKey(), null,
        new UploadEvidence(stored.fileName(), stored.sha256(), parsed.sheetName(), row))).toList();
  }

  private QuoteTechAuxItem item(ItemEvidence evidence, BigDecimal amount, int line) {
    var item = new QuoteTechAuxItem(); item.setLineNo(line); item.setSortSeq(line); item.setAmount(amount);
    item.setSourceReferenceId(evidence.itemKey()); item.setSourceSnapshotJson(codec.auxiliaryEvidenceJson(evidence));
    if (evidence.cms() != null) {
      var source = evidence.cms().item(); item.setPricingMethod("CMS_AMOUNT");
      item.setSubjectCode(source.subjectCode()); item.setSubjectName(source.subjectName()); item.setAuxiliaryName(source.subjectName());
      item.setSourceReferenceVersion(source.sourcePeriod());
    } else {
      var source = evidence.upload().item(); item.setPricingMethod("UPLOAD_AMOUNT");
      item.setAuxiliaryMaterialNo(source.materialNo()); item.setAuxiliaryName(source.name()); item.setRemark(source.remark());
      item.setSourceReferenceVersion(evidence.upload().fileSha256());
      // 原归类和二级科目建议保留在原表证据中，财务分类单独确认，不伪造已分类科目。
    }
    return item;
  }

  private void ensureEditable(Scope scope, TechnicalDataActor actor) {
    if (!Objects.equals(scope.product().getContentSchemaVersion(), 2) || !Objects.equals(scope.product().getActiveFlag(), 1)
        || !Objects.equals(scope.module().getRequiredFlag(), 1) || !actor.canEditModule(scope.task(), scope.module())) throw forbidden("当前辅料模块未分派给本人或已送审");
    if (!"MISSING".equals(scope.module().getSourceAvailability())) throw invalid("请先核实本产品的辅料缺口");
  }

  private Scope scope(Long productId, TechnicalDataActor actor, Integer expected) {
    var found = repository.findProduct(productId).orElseThrow(() -> invalid("补录产品不存在"));
    var task = (expected == null ? repository.findTask(found.getTaskId()) : repository.lockTask(found.getTaskId())).orElseThrow();
    var product = expected == null ? found : repository.lockProduct(productId).orElseThrow();
    if (expected != null && product.getCurrentEditVersionId() != null) repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
    var modules = expected == null ? tasks.findModules(productId) : repository.lockModules(productId);
    if (actor == null || !actor.canReadTask(task, modules)) throw forbidden("无权读取此补录产品");
    var module = modules.stream().filter(row -> "AUXILIARY".equals(row.getModuleType())).findFirst().orElseThrow(() -> invalid("产品没有辅料模块"));
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
