package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalBomSaveRequest;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Complete BOM editing belongs to the existing DRAWING_BOM assignment, with independent content. */
@Service
public class TechnicalBomApplicationService {
  private final TechnicalBomModuleCoverage coverage;
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository tasks;
  private final TechnicalDataReadPolicy reads;
  private final TechnicalDataSharedModules shared;
  private final TechnicalDataVersionContentCodec content;
  private final TechnicalDataSourceSnapshotFactory snapshots;
  private final TechnicalBomContextQuery contexts;
  private final TechnicalBomAbsenceQuery absence;
  private final TechnicalBomReferenceQuery references;
  private final TechnicalBomValidation validation;
  private final TechnicalBomWorkbook workbooks;
  private final TechnicalDataAttachmentStore files;
  private final OaMessageCodec json;
  public TechnicalBomApplicationService(QuoteTechnicalDataRepository repository, TechnicalDataTaskRepository tasks,
      TechnicalDataReadPolicy reads, TechnicalDataSharedModules shared, TechnicalDataVersionContentCodec content,
      TechnicalDataSourceSnapshotFactory snapshots, TechnicalBomContextQuery contexts, TechnicalBomAbsenceQuery absence,
      TechnicalBomReferenceQuery references, TechnicalBomValidation validation, TechnicalBomWorkbook workbooks,
      TechnicalDataAttachmentStore files, OaMessageCodec json, TechnicalBomModuleCoverage coverage) {
    this.coverage = coverage;
    this.repository=repository; this.tasks=tasks; this.reads=reads; this.shared=shared; this.content=content;
    this.snapshots=snapshots; this.contexts=contexts; this.absence=absence; this.references=references;
    this.validation=validation; this.workbooks=workbooks; this.files=files; this.json=json;
  }
  public record Response(Long productId, Integer rowVersion, Long versionId, boolean editable, boolean historical,
      TechnicalBomRows.Product target, TechnicalBomAbsenceQuery.Result source, TechnicalBomContent bom) {}
  public record Preview(TechnicalBomRows.Product product, List<TechnicalBomRows.Row> rows,
      TechnicalBomContent.Reference reference, TechnicalBomContent.Upload upload, List<TechnicalBomRows.Issue> issues) {}

  @Transactional(readOnly=true)
  public Response read(Long productId, Long versionId, TechnicalDataActor actor) {
    var scope=scope(productId, actor, null);
    Long selected=reads.readVersion(scope.product(), scope.module(), actor, versionId);
    var version=selected==null?null:repository.findVersion(selected).orElseThrow(()->invalid("BOM版本不存在"));
    if(version!=null&&!Objects.equals(version.getProductId(),productId)) throw forbidden("BOM版本不属于本产品");
    var target=contexts.read(scope.task(),scope.product());
    boolean historical=versionId!=null||version!=null&&!"DRAFT".equals(version.getVersionStatus());
    var saved=content.technicalBom(version);
    var source=historical?new TechnicalBomAbsenceQuery.Result(saved==null?"GALLERY":"TECHNICAL", null, "显示本次提交保存的BOM资料")
        :absence.read(target.context());
    return new Response(productId,scope.product().getRowVersion(),selected,!historical&&editable(scope,actor)&&source.allowsEntry(),
        historical,target.product(),source,saved);
  }
  @Transactional(readOnly=true)
  public List<TechnicalBomReferenceQuery.Candidate> search(Long productId,String keyword,TechnicalDataActor actor) {
    var scope=scope(productId,actor,null);
    return references.search(contexts.read(scope.task(),scope.product()).context(),keyword);
  }
  @Transactional(readOnly=true)
  public List<TechnicalBomReferenceQuery.Candidate> searchReplacement(Long productId, String keyword, TechnicalDataActor actor) {
    var scope=scope(productId,actor,null); requireEditable(scope,actor);
    var target=contexts.read(scope.task(),scope.product()); requireAbsent(target.context());
    return references.searchReplacement(target.context(),keyword);
  }
  @Transactional(readOnly=true)
  public List<TechnicalBomReferenceQuery.Candidate> searchReplacementForQuote(
      Long itemId, String accountingMonth, String keyword, TechnicalDataActor actor) {
    return references.searchReplacement(contexts.readQuote(itemId,accountingMonth,actor).context(),keyword);
  }
  @Transactional(readOnly=true)
  public Preview reference(Long productId,String code,TechnicalDataActor actor) {
    var scope=scope(productId,actor,null); requireEditable(scope,actor);
    var target=contexts.read(scope.task(),scope.product()); requireAbsent(target.context());
    return referencePreview(target, code);
  }
  @Transactional(readOnly=true)
  public Response sourceForQuote(Long itemId, String accountingMonth, TechnicalDataActor actor) {
    var target = contexts.readPreview(itemId, accountingMonth, actor);
    return new Response(null, null, null, false, false, target.product(), absence.read(target.context()), null);
  }
  @Transactional(readOnly=true)
  public List<TechnicalBomReferenceQuery.Candidate> searchForQuote(Long itemId, String accountingMonth, String keyword, TechnicalDataActor actor) {
    return references.search(contexts.readQuote(itemId, accountingMonth, actor).context(), keyword);
  }
  @Transactional(readOnly=true)
  public Preview referenceForQuote(Long itemId, String accountingMonth, String code, TechnicalDataActor actor) {
    return referencePreview(contexts.readQuote(itemId, accountingMonth, actor), code);
  }
  @Transactional(readOnly=true)
  public Preview checkReferenceForQuote(Long itemId, String accountingMonth, TechnicalBomSaveRequest request, TechnicalDataActor actor) {
    return checkReference(contexts.readQuote(itemId, accountingMonth, actor), request);
  }
  @Transactional(readOnly=true)
  public Preview checkReference(Long productId, TechnicalBomSaveRequest request, TechnicalDataActor actor) {
    var scope=scope(productId,actor,null); requireEditable(scope,actor);
    var target=contexts.read(scope.task(),scope.product()); requireAbsent(target.context());
    return checkReference(target, request);
  }
  private Preview checkReference(TechnicalBomContextQuery.Target target, TechnicalBomSaveRequest request) {
    if (request == null || !"REFERENCE".equals(request.getEntryMode()) || !request.getUnknownFields().isEmpty())
      throw invalid("参考BOM检查请求不完整或含不支持的字段");
    var input = referenceInput(target, request, true);
    var checked = validation.validate(target.context(), target.product(), target.product(), input.rows(), input.lines(), true);
    return new Preview(target.product(), input.rows(), input.reference(), null, checked.issues());
  }
  @Transactional(readOnly=true)
  public Preview previewUploadForQuote(Long itemId, String accountingMonth, String fileName, byte[] bytes, TechnicalDataActor actor) {
    var parsed = checkedUpload(contexts.readQuote(itemId, accountingMonth, actor), fileName, bytes);
    var upload = parsed.issues().isEmpty()
        ? new TechnicalBomContent.Upload(fileName, TechnicalDataAttachmentStore.sha256(bytes), bytes.length, TechnicalBomWorkbook.ENTRY) : null;
    return new Preview(parsed.product(), parsed.rows(), null, upload, parsed.issues());
  }
  @Transactional(readOnly=true)
  public TechnicalDataAttachmentStore.StoredFile templateForQuote(Long itemId, String accountingMonth, String code, TechnicalDataActor actor) {
    return referenceTemplate(contexts.readQuote(itemId, accountingMonth, actor), code);
  }
  private Preview referencePreview(TechnicalBomContextQuery.Target target, String code) {
    var source=references.read(target.context(),code);
    var checked=validation.validate(target.context(),target.product(),target.product(),source.rows(),source.sourceLines());
    return new Preview(target.product(),source.rows(),source.evidence(),null,checked.issues());
  }
  @Transactional(readOnly=true)
  public TechnicalDataAttachmentStore.StoredFile template(Long productId,String exampleCode,TechnicalDataActor actor) {
    var scope=scope(productId,actor,null);
    return referenceTemplate(contexts.read(scope.task(),scope.product()), exampleCode);
  }
  private TechnicalDataAttachmentStore.StoredFile referenceTemplate(TechnicalBomContextQuery.Target target, String code) {
    var source=references.read(target.context(),code);
    return new TechnicalDataAttachmentStore.StoredFile("BOM填写模板.xlsx",source.evidence().fingerprint(),workbooks.template(source.product(),source.rows(),source.evidence()));
  }
  @Transactional(readOnly=true)
  public Preview preview(Long productId,String fileName,byte[] bytes,TechnicalDataActor actor) {
    var scope=scope(productId,actor,null); requireEditable(scope,actor);
    var target=contexts.read(scope.task(),scope.product()); requireAbsent(target.context());
    var parsed=checkedUpload(target,fileName,bytes);
    TechnicalBomContent.Upload upload=null;
    if(parsed.issues().isEmpty()) { var stored=files.save(productId,fileName,bytes); upload=new TechnicalBomContent.Upload(stored.fileName(),stored.sha256(),stored.bytes().length,TechnicalBomWorkbook.ENTRY); }
    return new Preview(parsed.product(),parsed.rows(),null,upload,parsed.issues());
  }
  private Preview checkedUpload(TechnicalBomContextQuery.Target target, String fileName, byte[] bytes) {
    var parsed=workbooks.parse(fileName,bytes);
    var issues=new ArrayList<>(parsed.issues());
    if(issues.isEmpty()) issues.addAll(validation.validate(target.context(),target.product(),parsed.product(),parsed.rows(),Map.of()).issues());
    return new Preview(parsed.product(),parsed.rows(),null,null,List.copyOf(issues));
  }
  @Transactional(readOnly=true)
  public TechnicalDataAttachmentStore.StoredFile file(Long productId,Long versionId,TechnicalDataActor actor) {
    var response=read(productId,versionId,actor);
    if(response.bom()==null||response.bom().upload()==null) throw invalid("此版本没有上传BOM原表");
    return files.read(productId,response.bom().upload().sha256());
  }
  @Transactional
  public Response save(Long productId,TechnicalBomSaveRequest request,TechnicalDataActor actor) {
    if(request==null||request.getExpectedVersion()==null||request.getExpectedVersion()<0||!request.getUnknownFields().isEmpty()
        ||!Set.of("REFERENCE","UPLOAD").contains(Objects.toString(request.getEntryMode(),""))) throw invalid("BOM保存请求不完整或含不支持的字段");
    var scope=scope(productId,actor,request.getExpectedVersion()); requireEditable(scope,actor); shared.requireOwnership(productId,"DRAWING_BOM");
    var target=contexts.read(scope.task(),scope.product()); var missing=requireAbsent(target.context());
    var bom=prepare(scope.product().getId(),target,missing,request);
    var now=LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var draft=draft(scope.product(),actor,now);
    if(content.drawingBom(draft)!=null) throw invalid("当前草稿已有图库明细，不能用完整技术BOM覆盖图库来源");
    var previous = content.technicalBom(draft);
    if (previous != null && content.sameSourceEvidence(previous.inputBasis(), bom.inputBasis())) bom = previous;
    draft.setTechnicalBomJson(content.technicalBomJson(bom)); draft.setUpdatedBy(actor.userId());
    if(repository.updateDraftVersion(draft,draft.getRowVersion(),now)!=1) throw conflict("BOM草稿已变化，请刷新后保存");
    var module=scope.module(); module.setEntryMode(bom.entryMode()); module.setCurrentVersionId(draft.getId()); module.setModuleStatus("READY");
    module.setReferenceSourceType("TECH_"+bom.entryMode()); module.setReferenceSourceId(bom.reference()!=null?bom.reference().productCode():bom.upload().sha256());
    module.setReferenceSourceVersion(bom.reference()!=null?String.join(",",bom.reference().versions()):bom.upload().sha256());
    module.setReferenceFingerprint(json.dataFingerprint(bom.inputBasis())); module.setReferenceSnapshotJson(content.technicalBomJson(bom)); module.setReferencedAt(now);
    module.setLastValidationCode("TECH_BOM_VERIFIED"); module.setLastValidationMessage("完整BOM已校验保存，按原流程提交后用于核算");
    if(repository.updateModule(module,module.getRowVersion(),now)!=1) throw conflict("BOM模块已变化，请刷新后保存");
    coverage.saved(draft, bom, now);
    scope.product().setCurrentEditVersionId(draft.getId()); scope.product().setProductStatus("EDITING");
    if(repository.updateProductPointers(scope.product(),request.getExpectedVersion(),now)!=1) throw conflict("产品资料已变化，请刷新后保存");
    repository.markTaskInProgress(scope.task().getId(),actor.userId(),now);
    return read(productId,null,actor);
  }
  private TechnicalBomContent prepare(Long productId,TechnicalBomContextQuery.Target target,TechnicalBomAbsenceQuery.Result missing,TechnicalBomSaveRequest request) {
    TechnicalBomRows.Product product; List<TechnicalBomRows.Row> rows; TechnicalBomContent.Reference reference=null;
    TechnicalBomContent.Upload upload=null; Map<String,TechnicalBomContent.ReferenceLine> lines=Map.of();
    if("REFERENCE".equals(request.getEntryMode())) {
      var input=referenceInput(target,request,false);
      rows=input.rows(); product=target.product(); reference=input.reference(); lines=input.lines();
    } else {
      if(request.getReferenceCode()!=null||request.getReferenceFingerprint()!=null||request.getQuantities()!=null||request.getReplacements()!=null) throw invalid("上传BOM请修改原表后重新上传，不接受客户端改写解析行");
      var stored=files.read(productId,request.getFileSha256()); var parsed=workbooks.parse(stored.fileName(),stored.bytes());
      requireValid(parsed.issues()); product=parsed.product(); rows=parsed.rows();
      upload=new TechnicalBomContent.Upload(stored.fileName(),stored.sha256(),stored.bytes().length,TechnicalBomWorkbook.ENTRY);
    }
    var checked=validation.validate(target.context(),target.product(),product,rows,lines,reference!=null); requireValid(checked.issues());
    var c=target.context();
    return new TechnicalBomContent(1,request.getEntryMode(),new TechnicalBomContent.Scope(c.oaFormItemId(),c.oaNo(),c.accountingMonth(),c.businessUnitType(),
        c.priceOrgCode(),c.materialOrganizationCode(),c.productCode()),product,checked.nodes(),reference,upload,missing.evidence(),LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE),request.getReplacements());
  }
  private record ReferenceInput(List<TechnicalBomRows.Row> rows, TechnicalBomContent.Reference reference,
      Map<String,TechnicalBomContent.ReferenceLine> lines) {}
  private ReferenceInput referenceInput(TechnicalBomContextQuery.Target target, TechnicalBomSaveRequest request, boolean preview) {
    if (request.getFileSha256()!=null) throw invalid("参考BOM不能带上传文件");
    var source=references.read(target.context(),request.getReferenceCode());
    if (!Objects.equals(source.evidence().fingerprint(),request.getReferenceFingerprint()))
      throw conflict("参考BOM已变化，请重新选择并核对用量");
    var rows=new LinkedHashMap<String,TechnicalBomRows.Row>();
    source.rows().forEach(row -> rows.put(row.hierarchy(), row));
    var lines=new LinkedHashMap<>(source.sourceLines());
    var replacements=request.getReplacements();
    if (replacements!=null) {
      if (replacements.size()>TechnicalBomRows.MAX_ROWS || replacements.keySet().stream().anyMatch(Objects::isNull))
        throw invalid("参考BOM替换节点无效");
      for (String key : replacements.keySet().stream().sorted().toList()) {
        var original=rows.get(key);
        if (original==null) throw invalid("参考BOM替换节点不存在："+key);
        if (Objects.equals(original.materialCode(), replacements.get(key))) continue;
        var replacement=references.replacement(target.context(), original, replacements.get(key));
        String prefix=key+".";
        rows.keySet().removeIf(path -> path.startsWith(prefix));
        lines.keySet().removeIf(path -> path.equals(key)||path.startsWith(prefix));
        rows.put(key,replacement.row());
        for (var child : replacement.children()) {
          String path=prefix+child.hierarchy();
          rows.put(path,withQuantity(child,path,child.lineNo(),child.quantity()));
          lines.put(path,replacement.lines().get(child.hierarchy()));
        }
        if (rows.size()>TechnicalBomRows.MAX_ROWS) throw invalid("替换后BOM子件不能超过5000行");
      }
    }
    var quantities=request.getQuantities();
    if (quantities==null || !rows.keySet().containsAll(quantities.keySet())
        || !preview && !quantities.keySet().equals(rows.keySet()))
      throw invalid("参考BOM节点不完整，请重新核对替换物料和用量");
    var ordered=new java.util.ArrayList<TechnicalBomRows.Row>();
    // A parent always sorts before its descendants; keep original row order for untouched drafts.
    var keys=replacements==null||replacements.isEmpty()?rows.keySet().stream():rows.keySet().stream().sorted(TechnicalBomApplicationService::compareTreePaths);
    keys.forEach(key -> {
      var row=rows.get(key);
      ordered.add(withQuantity(row,key,replacements==null||replacements.isEmpty()?row.lineNo():ordered.size()+2,
          quantities.getOrDefault(key,row.quantity())));
    });
    return new ReferenceInput(List.copyOf(ordered),source.evidence(),lines);
  }

  private static int compareTreePaths(String left, String right) {
    var a=left.split("\\.");
    var b=right.split("\\.");
    for (int index=0; index<Math.min(a.length,b.length); index++) {
      int compared=Integer.compare(a[index].length(),b[index].length());
      if (compared==0) compared=a[index].compareTo(b[index]);
      if (compared!=0) return compared;
    }
    return Integer.compare(a.length,b.length);
  }

  private static TechnicalBomRows.Row withQuantity(TechnicalBomRows.Row row, String hierarchy, int line, BigDecimal quantity) {
    return new TechnicalBomRows.Row(line,hierarchy,row.materialCode(),row.name(),row.model(),row.specification(),
        quantity,row.unit(),row.attribute(),row.material(),row.netWeight(),row.netWeightUnit());
  }
  public List<String> validateCurrent(QuoteTechProduct product,QuoteTechDataVersion version) {
    var bom=content.technicalBom(version); if(bom==null) return List.of("尚未保存完整BOM");
    try {
      var target=contexts.read(repository.findTask(product.getTaskId()).orElseThrow(),product);
      var missing=requireAbsent(target.context());
      var request=new TechnicalBomSaveRequest(); request.setEntryMode(bom.entryMode());
      if(bom.reference()!=null) { request.setReferenceCode(bom.reference().productCode()); request.setReferenceFingerprint(bom.reference().fingerprint());
        var quantities=new LinkedHashMap<String,BigDecimal>(); bom.nodes().forEach(row->quantities.put(row.input().hierarchy(),row.input().quantity())); request.setQuantities(quantities); request.setReplacements(bom.replacements()); }
      if(bom.upload()!=null) request.setFileSha256(bom.upload().sha256());
      var current=prepare(product.getId(),target,missing,request);
      if(!content.sameSourceEvidence(bom.scope(),current.scope())||!content.sameSourceEvidence(bom.nodes(),current.nodes())
          ||!content.sameSourceEvidence(bom.product(),current.product())) return List.of("完整BOM物料或当前产品依据已变化，请重新检查并保存");
      return List.of();
    } catch(RuntimeException error) { return List.of(Objects.toString(error.getMessage(),"完整BOM检查失败")); }
  }
  private TechnicalBomAbsenceQuery.Result requireAbsent(QuoteBomReadContext context) {
    var value=absence.read(context); if(!value.allowsEntry()) throw invalid(value.message()); return value;
  }
  private static void requireValid(List<TechnicalBomRows.Issue> issues) {
    if(!issues.isEmpty()) throw invalid(issues.stream().limit(20).map(row->(row.lineNo()==null?"":"第"+row.lineNo()+"行：")+row.message()).collect(java.util.stream.Collectors.joining("；")));
  }
  private Scope scope(Long productId,TechnicalDataActor actor,Integer expected) {
    var found=repository.findProduct(productId).orElseThrow(()->invalid("补录产品不存在"));
    var task=(expected==null?repository.findTask(found.getTaskId()):repository.lockTask(found.getTaskId())).orElseThrow();
    var product=expected==null?found:repository.lockProduct(productId).orElseThrow();
    if(expected!=null&&product.getCurrentEditVersionId()!=null) repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
    var modules=expected==null?tasks.findModules(productId):repository.lockModules(productId);
    if(actor==null||!actor.canReadTask(task,modules)) throw forbidden("无权读取此补录产品");
    var module=modules.stream().filter(row->"DRAWING_BOM".equals(row.getModuleType())).findFirst().orElseThrow(()->invalid("产品没有BOM补录模块"));
    if(expected!=null&&!Objects.equals(product.getRowVersion(),expected)) throw conflict("资料已被其他会话修改，请刷新后重试");
    return new Scope(task,product,module);
  }
  private boolean editable(Scope scope,TechnicalDataActor actor) {
    return Integer.valueOf(2).equals(scope.product().getContentSchemaVersion())&&Integer.valueOf(1).equals(scope.product().getActiveFlag())
        &&"MISSING".equals(scope.module().getSourceAvailability())&&actor.canEditModule(scope.task(),scope.module());
  }
  private void requireEditable(Scope scope,TechnicalDataActor actor) { if(!editable(scope,actor)) throw forbidden("BOM补录未分派给本人、无需补录或已提交"); }
  private QuoteTechDataVersion draft(QuoteTechProduct product,TechnicalDataActor actor,LocalDateTime now) {
    if(product.getCurrentEditVersionId()!=null) {
      var draft=repository.lockVersion(product.getCurrentEditVersionId()).orElseThrow();
      if(!Objects.equals(draft.getProductId(),product.getId())||!"DRAFT".equals(draft.getVersionStatus())) throw conflict("当前版本不能覆盖"); return draft;
    }
    var draft=new QuoteTechDataVersion(); var profile=snapshots.readProfile(product.getSourceSnapshotJson());
    draft.setProductId(product.getId()); draft.setVersionNo(repository.maxVersionNo(product.getId())+1); draft.setVersionStatus("DRAFT");
    draft.setContentSchemaVersion(2); draft.setProductModel(profile.productModel()); draft.setNewProductFlag(Boolean.TRUE.equals(profile.newProduct())?1:0);
    draft.setPackageTotalAmount(BigDecimal.ZERO); draft.setAuxiliaryTotalAmount(BigDecimal.ZERO); draft.setSalaryTotalAmount(BigDecimal.ZERO);
    draft.setRowVersion(0); draft.setCreatedBy(actor.userId()); draft.setUpdatedBy(actor.userId()); draft.setCreatedAt(now); draft.setUpdatedAt(now);
    return repository.insertVersion(draft);
  }
  private record Scope(QuoteTechTask task,QuoteTechProduct product,QuoteTechModule module) {}
  private static TechnicalDataTaskException invalid(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.INVALID_REQUEST,message); }
  private static TechnicalDataTaskException forbidden(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN,message); }
  private static TechnicalDataTaskException conflict(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT,message); }
}
