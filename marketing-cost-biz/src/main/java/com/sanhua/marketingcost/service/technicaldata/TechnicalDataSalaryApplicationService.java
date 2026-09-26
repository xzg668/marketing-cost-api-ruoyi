package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryContent.ItemEvidence;
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

/** 工资资料保存与个人版本的事务边界；来源取证、完整性校验各自独立。 */
@Service
public class TechnicalDataSalaryApplicationService {
  private final TechnicalDataReadPolicy readPolicy;
  private final TechnicalDataSharedModules sharedModules;
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository tasks;
  private final TechnicalDataSourceSnapshotFactory snapshots;
  private final TechnicalDataVersionContentCodec codec;
  private final TechnicalDataSalarySourceQuery sources;
  private final TechnicalDataSalaryUploadParser parser;
  private final TechnicalDataAttachmentStore files;

  public TechnicalDataSalaryApplicationService(QuoteTechnicalDataRepository repository,
      TechnicalDataTaskRepository tasks, TechnicalDataSourceSnapshotFactory snapshots,
      TechnicalDataVersionContentCodec codec, TechnicalDataSalarySourceQuery sources,
      TechnicalDataSalaryUploadParser parser, TechnicalDataAttachmentStore files, TechnicalDataSharedModules sharedModules, TechnicalDataReadPolicy readPolicy) {
    this.readPolicy=readPolicy;
    this.sharedModules = sharedModules;
    this.repository = repository; this.tasks = tasks; this.snapshots = snapshots;
    this.codec = codec; this.sources = sources;
    this.parser = parser; this.files = files;
  }

  @Transactional(readOnly = true)
  public TechnicalDataSalaryResponse get(Long productId, Long versionId, TechnicalDataActor actor) {
    var scope = scope(productId, actor, null);
    Long selected = readPolicy.readVersion(scope.product(), scope.module(), actor, versionId);
    var version = selected == null ? null : repository.findVersion(selected).orElseThrow(() -> invalid("工资版本不存在"));
    if (version != null && !Objects.equals(version.getProductId(), productId)) throw forbidden("不能读取其他产品的工资版本");
    var rows = version == null ? List.<QuoteTechSalaryItem>of() : repository.findSalaryItems(version.getId());
    boolean historical = versionId != null || version != null && !"DRAFT".equals(version.getVersionStatus());
    var issues = !historical && Objects.equals(scope.module().getRequiredFlag(), 1)
        ? TechnicalDataSalaryRules.validate(rows, codec) : List.<String>of();
    var items = rows.stream().map(row -> {
      var evidence = codec.salaryEvidence(row);
      return new TechnicalDataSalaryResponse.Item(row.getId(), row.getLaborType(),
          evidence == null ? null : evidence.sourceAmount(), row.getAmount(), evidence);
    }).toList();
    BigDecimal total = rows.isEmpty() || rows.stream().anyMatch(row -> row.getAmount() == null) ? null
        : rows.stream().map(QuoteTechSalaryItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    String mode = items.stream().anyMatch(row -> row.source() != null && row.source().upload() != null) ? "UPLOAD" : "REFERENCE";
    return new TechnicalDataSalaryResponse(scope.task().getId(), productId, scope.product().getRowVersion(), selected,
        version == null ? null : version.getVersionStatus(), scope.module().getModuleStatus(), mode,
        !historical && editable(scope, actor), historical, total, items, issues);
  }

  @Transactional(readOnly = true)
  public List<TechnicalDataSalaryCmsSource> references(Long productId, String keyword, String entryMode, TechnicalDataActor actor) {
    var scope = scope(productId, actor, null);
    return sources.searchReferences(scope.task(), scope.product(), keyword, entryMode);
  }

  @Transactional(readOnly = true)
  public TechnicalDataSalaryUploadResponse preview(Long productId, String name, byte[] bytes, TechnicalDataActor actor) {
    if (!editable(scope(productId, actor, null), actor)) throw forbidden("当前工资模块未分派给本人、无需补录或已送审");
    var parsed = parser.parse(name, bytes);
    if (!parsed.issues().isEmpty()) return parsed;
    var stored = files.save(productId, name, bytes);
    return parser.parse(stored.fileName(), stored.bytes());
  }

  @Transactional(readOnly = true)
  public TechnicalDataAttachmentStore.StoredFile file(Long productId, Long versionId, TechnicalDataActor actor) {
    var response = get(productId, versionId, actor);
    var upload = response.items().stream().map(TechnicalDataSalaryResponse.Item::source)
        .filter(Objects::nonNull).map(ItemEvidence::upload).filter(Objects::nonNull).findFirst()
        .orElseThrow(() -> invalid("此工资版本没有上传工时表"));
    return files.read(productId, upload.fileSha256());
  }

  @Transactional
  public TechnicalDataSalaryResponse save(Long productId, TechnicalDataSalarySaveRequest request, TechnicalDataActor actor) {
    if (request == null || request.getExpectedVersion() == null || request.getExpectedVersion() < 0
        || !request.getUnknownFields().isEmpty() || !Set.of("REFERENCE", "UPLOAD").contains(Objects.toString(request.getEntryMode(), ""))) {
      throw invalid("工资请求不完整或包含不支持的字段");
    }
    var scope = scope(productId, actor, request.getExpectedVersion());
    if (!editable(scope, actor)) throw forbidden("当前工资模块未分派给本人、无需补录或已送审");
    sharedModules.requireOwnership(productId, "SALARY");
    var source = sources.requireReference(scope.task(), scope.product(), request.getReferenceMaterialNo(),
        request.getReferenceFingerprint(), request.getEntryMode());
    if ("REFERENCE".equals(request.getEntryMode()) && request.getFileSha256() != null) throw invalid("参考方式不能带入上传文件");
    var items = "UPLOAD".equals(request.getEntryMode())
        ? uploadItems(productId, request.getFileSha256(), source) : referenceItems(source);
    var issues = TechnicalDataSalaryRules.validate(items, codec);
    if (!issues.isEmpty()) throw invalid(String.join("；", issues));
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var draft = draft(scope.product(), actor, now);
    repository.deleteAllSalaryItemsIfDraft(draft.getId());
    if (repository.insertSalaryItemsIfDraft(draft.getId(), items) != items.size()) throw conflict("工资草稿保存失败");
    draft.setSalaryTotalAmount(items.stream().map(QuoteTechSalaryItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
    draft.setUpdatedBy(actor.userId());
    if (repository.updateDraftVersion(draft, draft.getRowVersion(), now) != 1) throw conflict("工资草稿已变化，请刷新后保存");
    var module = scope.module();
    module.setEntryMode(request.getEntryMode()); module.setCurrentVersionId(draft.getId()); module.setModuleStatus("READY");
    module.setReferenceSourceType("UPLOAD".equals(request.getEntryMode()) ? "EXCEL_UPLOAD" : "CMS_SALARY");
    module.setReferenceSourceId("UPLOAD".equals(request.getEntryMode()) ? request.getFileSha256() : source.materialNo());
    module.setReferenceSourceVersion(Integer.toString(source.costYear())); module.setReferenceFingerprint(source.fingerprint());
    module.setReferenceSnapshotJson(items.getFirst().getSourceSnapshotJson()); module.setReferencedAt(now);
    module.setLastValidationCode("SALARY_VERIFIED"); module.setLastValidationMessage("直接人工和辅助人员工资及来源完整");
    if (repository.updateModule(module, module.getRowVersion(), now) != 1) throw conflict("工资模块已变化，请刷新后保存");
    scope.product().setCurrentEditVersionId(draft.getId()); scope.product().setProductStatus("EDITING");
    if (repository.updateProductPointers(scope.product(), request.getExpectedVersion(), now) != 1) throw conflict("产品资料已变化，请刷新后保存");
    repository.markTaskInProgress(scope.task().getId(), actor.userId(), now);
    return get(productId, null, actor);
  }

  public List<String> validateCurrent(QuoteTechProduct product, QuoteTechDataVersion version) {
    var rows = repository.findSalaryItems(version.getId());
    var issues = new ArrayList<>(TechnicalDataSalaryRules.validate(rows, codec));
    if (!issues.isEmpty()) return issues;
    try {
      var direct = rows.stream().filter(row -> "DIRECT".equals(row.getLaborType())).findFirst().orElseThrow();
      var indirect = rows.stream().filter(row -> "INDIRECT".equals(row.getLaborType())).findFirst().orElseThrow();
      var upload = codec.salaryEvidence(direct).upload();
      var selected = codec.salaryEvidence(indirect).reference();
      var current = sources.requireReference(repository.findTask(product.getTaskId()).orElseThrow(), product,
          selected.materialNo(), selected.fingerprint(), upload == null ? "REFERENCE" : "UPLOAD");
      var expected = upload == null ? referenceItems(current) : uploadItems(product.getId(), upload.fileSha256(), current);
      for (int index = 0; index < rows.size(); index++) {
        if (!codec.sameSourceEvidence(codec.salaryEvidence(expected.get(index)), codec.salaryEvidence(rows.get(index)))) {
          issues.add("工资原始依据已变化或不完整，请重新选择参考成品"); break;
        }
      }
    } catch (RuntimeException exception) { issues.add(exception.getMessage() == null ? "工资来源检查失败" : exception.getMessage()); }
    return List.copyOf(issues);
  }

  private List<QuoteTechSalaryItem> referenceItems(TechnicalDataSalaryCmsSource source) {
    var result = new ArrayList<QuoteTechSalaryItem>();
    for (String type : List.of("DIRECT", "INDIRECT")) {
      var evidence = new ItemEvidence(type, source, null); var original = evidence.cmsItem();
      var item = new QuoteTechSalaryItem(); item.setLineNo(result.size() + 1); item.setSortSeq(result.size() + 1);
      item.setLaborType(type); item.setAmount(original.amountYuan());
      item.setSourceReferenceId("CMS_SALARY:" + original.sourceId()); item.setSourceReferenceVersion(original.sourcePeriod());
      item.setSourceSnapshotJson(codec.salaryEvidenceJson(evidence)); result.add(item);
    }
    return result;
  }

  private List<QuoteTechSalaryItem> uploadItems(Long productId, String hash, TechnicalDataSalaryCmsSource indirect) {
    var stored = files.read(productId, hash);
    var parsed = parser.parse(stored.fileName(), stored.bytes());
    if (!parsed.issues().isEmpty()) throw invalid("工时表有解析错误，请按工作表和行号修正后重新上传");
    var directRow = new QuoteTechSalaryItem();
    directRow.setLineNo(1); directRow.setSortSeq(1); directRow.setLaborType("DIRECT"); directRow.setAmount(parsed.amountYuan());
    directRow.setSourceReferenceId("SALARY_UPLOAD:" + stored.sha256()); directRow.setSourceReferenceVersion(stored.sha256());
    directRow.setSourceSnapshotJson(codec.salaryEvidenceJson(new ItemEvidence("DIRECT", null, parsed)));
    var indirectEvidence = new ItemEvidence("INDIRECT", indirect, null);
    var source = indirectEvidence.cmsItem();
    var indirectRow = new QuoteTechSalaryItem();
    indirectRow.setLineNo(2); indirectRow.setSortSeq(2); indirectRow.setLaborType("INDIRECT"); indirectRow.setAmount(source.amountYuan());
    indirectRow.setSourceReferenceId("CMS_SALARY:" + source.sourceId()); indirectRow.setSourceReferenceVersion(source.sourcePeriod());
    indirectRow.setSourceSnapshotJson(codec.salaryEvidenceJson(indirectEvidence));
    return List.of(directRow, indirectRow);
  }

  private boolean editable(Scope scope, TechnicalDataActor actor) {
    return Objects.equals(scope.product().getContentSchemaVersion(), 2) && Objects.equals(scope.product().getActiveFlag(), 1)
        && Objects.equals(scope.module().getRequiredFlag(), 1) && "MISSING".equals(scope.module().getSourceAvailability())
        && actor.canEditModule(scope.task(), scope.module());
  }

  private Scope scope(Long productId, TechnicalDataActor actor, Integer expected) {
    var found = repository.findProduct(productId).orElseThrow(() -> invalid("补录产品不存在"));
    var task = (expected == null ? repository.findTask(found.getTaskId()) : repository.lockTask(found.getTaskId())).orElseThrow();
    var product = expected == null ? found : repository.lockProduct(productId).orElseThrow();
    if (expected != null && product.getCurrentEditVersionId() != null) repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
    var modules = expected == null ? tasks.findModules(productId) : repository.lockModules(productId);
    if (actor == null || !actor.canReadTask(task, modules)) throw forbidden("无权读取此补录产品");
    var module = modules.stream().filter(row -> "SALARY".equals(row.getModuleType())).findFirst().orElseThrow(() -> invalid("产品没有工资模块"));
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
