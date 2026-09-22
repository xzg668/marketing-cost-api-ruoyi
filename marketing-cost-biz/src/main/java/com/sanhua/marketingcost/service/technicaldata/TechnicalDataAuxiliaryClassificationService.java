package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.dto.technicaldata.AuxiliaryClassificationResponse;
import com.sanhua.marketingcost.dto.technicaldata.AuxiliaryClassificationResponse.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataAuxiliaryClassificationRepository.Classification;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 财务只保存审批明细的科目映射；技术快照、金额和原文件均不改写。 */
@Service
public class TechnicalDataAuxiliaryClassificationService {
  private final QuoteTechnicalDataRepository technical;
  private final QuoteTechProductMapper products;
  private final QuoteTechModuleMapper modules;
  private final OaFormMapper forms;
  private final OaFormItemMapper items;
  private final TechnicalDataSharedModules shared;
  private final TechnicalDataOaWorkflowRepository workflow;
  private final TechnicalDataVersionContentCodec content;
  private final TechnicalDataAuxiliarySubjects subjects;
  private final TechnicalDataAuxiliaryClassificationRepository classifications;
  private final OaMessageCodec json;

  public TechnicalDataAuxiliaryClassificationService(QuoteTechnicalDataRepository technical,
      QuoteTechProductMapper products, QuoteTechModuleMapper modules, OaFormMapper forms, OaFormItemMapper items,
      TechnicalDataSharedModules shared, TechnicalDataOaWorkflowRepository workflow,
      TechnicalDataVersionContentCodec content, TechnicalDataAuxiliarySubjects subjects,
      TechnicalDataAuxiliaryClassificationRepository classifications, OaMessageCodec json) {
    this.technical=technical; this.products=products; this.modules=modules; this.forms=forms; this.items=items;
    this.shared=shared; this.workflow=workflow; this.content=content; this.subjects=subjects;
    this.classifications=classifications; this.json=json;
  }

  @Transactional(readOnly = true)
  public List<AuxiliaryClassificationResponse> list(String oaNo, String month, TechnicalDataActor actor) {
    var found=forms.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo,oaNo));
    if (found.size()!=1) throw new IllegalArgumentException("报价单不存在或不唯一");
    return items.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(OaFormItem.class)
        .eq(OaFormItem::getOaFormId,found.getFirst().getId())).stream()
        .map(item -> get(oaNo,item.getId(),month,actor)).toList();
  }

  @Transactional(readOnly = true)
  public AuxiliaryClassificationResponse get(String oaNo, Long itemId, String month, TechnicalDataActor actor) {
    return view(scope(oaNo,itemId,month,actor,false),actor);
  }

  @Transactional(readOnly = true)
  public Preview preview(String oaNo, Long itemId, String month, List<Row> rows, TechnicalDataActor actor) {
    var scope=scope(oaNo,itemId,month,actor,false);
    var view=view(scope,actor);
    requireEditable(view);
    return validate(view, rows);
  }

  @Transactional
  public AuxiliaryClassificationResponse confirm(String oaNo, Long itemId, String month,
      List<Row> rows, String expectedFingerprint, TechnicalDataActor actor) {
    var scope=scope(oaNo,itemId,month,actor,true);
    var view=view(scope,actor);
    requireEditable(view);
    var checked=validate(view,rows);
    if (!checked.valid()) throw new IllegalArgumentException("归类文件校验未通过："+checked.issues().getFirst().message());
    if (!Objects.equals(expectedFingerprint,checked.fingerprint())) throw conflict("审批版本、科目或归类结果已变化，请重新预检");
    for (var row:rows) {
      var subject=subjects.require(view.subjects(),row.columns().getLast());
      classifications.save(view.technicalVersionId(),new Classification(Long.valueOf(row.columns().get(4)),
          view.contentFingerprint(),subject.code(),subject.name()),actor.userId());
    }
    return view(scope,actor);
  }

  /** 成本消费与页面使用相同的有效科目校验；只返回映射结果，绝不修改审批实体。 */
  public List<EffectiveTechnicalDataInput.AuxiliaryLine> costingLines(QuoteTechDataVersion version,
      List<QuoteTechAuxItem> rows, String businessUnit) {
    var definitions=subjects.list(businessUnit);
    var mappings=classifications.find(version.getId());
    List<EffectiveTechnicalDataInput.AuxiliaryLine> result=new ArrayList<>();
    for (var row:rows) {
      String code=row.getSubjectCode(), name=row.getSubjectName();
      if ("UPLOAD_AMOUNT".equals(row.getPricingMethod())) {
        var mapping=validMapping(version,row,mappings,definitions);
        if (mapping==null) throw new IllegalArgumentException("辅料待财务归类："+row.getAuxiliaryName());
        code=mapping.subjectCode(); name=mapping.subjectName();
      }
      result.add(new EffectiveTechnicalDataInput.AuxiliaryLine(row.getId(),row.getLineNo(),code,name,
          row.getAuxiliaryName(),row.getStandardQuantity(),row.getStandardUnit(),row.getReferenceUnitPrice(),row.getLossRate(),row.getAmount()));
    }
    return List.copyOf(result);
  }

  private AuxiliaryClassificationResponse view(Scope scope, TechnicalDataActor actor) {
    if (scope.product()==null) return empty(scope,"NOT_REQUIRED","本次没有待归类的技术辅料");
    var product=scope.product(); var task=scope.task();
    if (!Integer.valueOf(1).equals(product.getActiveFlag()) || !Integer.valueOf(1).equals(task.getActiveFlag())) {
      return empty(scope,"WAIT_APPROVAL","原辅料任务已停用，请核对当前有效来源");
    }
    if (!"APPROVED".equals(product.getProductStatus()) || !"APPROVED".equals(task.getTaskStatus())
        || !"PASSED".equals(task.getReviewStatus()) || product.getEffectiveVersionId()==null) {
      return empty(scope,"WAIT_APPROVAL","等待全部补录资料审批通过并回到财务");
    }
    var version=technical.findVersion(product.getEffectiveVersionId()).orElseThrow(() -> conflict("辅料审批版本不存在"));
    if (!Objects.equals(version.getProductId(),product.getId()) || !"APPROVED".equals(version.getVersionStatus())) throw conflict("辅料审批版本已失效");
    var allRows=technical.findAuxItems(version.getId());
    String actual=content.fingerprint(version,content.readReferenceSnapshot(version.getReferenceSnapshotJson()),
        technical.findPackageItems(version.getId()),allRows,technical.findSalaryItems(version.getId()));
    if (!Objects.equals(actual,version.getContentFingerprint())) throw conflict("审批内容与冻结指纹不一致，请核对原任务");
    var uploaded=allRows.stream().filter(row -> "UPLOAD_AMOUNT".equals(row.getPricingMethod())).toList();
    if (uploaded.isEmpty()) return empty(scope,"NOT_REQUIRED","已有 CMS 科目，无需财务归类");
    var flow=task.getOaFlowId()==null?null:workflow.findFlow(task.getOaFlowId());
    if (flow==null || !flow.financeReady()) return empty(scope,"WAIT_FINANCE","等待全部资料审批通过并进入 OA 财务核算节点");
    boolean editable=actor!=null && !actor.shortSession() && (actor.admin()
        || actor.has("ingest:quote:cost-run:execute") && Objects.equals(actor.userId(),flow.financeUserId()));
    var definitions=subjects.list(task.getBusinessUnitType()); var mappings=classifications.find(version.getId());
    List<Item> result=new ArrayList<>(); List<Total> totals=new ArrayList<>(); boolean complete=true;
    for (var row:uploaded) {
      var mapping=validMapping(version,row,mappings,definitions); complete &= mapping!=null;
      var evidence=content.auxiliaryEvidence(row);
      if (evidence==null || evidence.upload()==null) throw conflict("审批辅料缺少上传来源证据");
      var origin=evidence.upload().item();
      List<String> columns=Arrays.asList(scope.oaNo(),text(scope.itemId()),scope.month(),text(version.getId()),text(row.getId()),
          version.getContentFingerprint(),text(origin.partName()),text(origin.sequence()),text(origin.processName()),
          text(origin.materialNo()),text(origin.name()),text(origin.priceExcludingTax()),text(origin.volumeOrArea()),
          text(origin.processableQuantity()),text(origin.amountPerProduct()),text(origin.category()),text(origin.remark()),
          text(row.getAmount()),evidence.upload().fileSha256(),text(evidence.upload().sheetName()),text(origin.sheetRow()));
      // 技术自填的名称仅作为财务提示，未经验证不视为归类完成。
      result.add(new Item(row.getId(),row.getAuxiliaryMaterialNo(),row.getAuxiliaryName(),text(row.getAmount()),
          mapping==null?null:mapping.subjectCode(),mapping==null?origin.secondarySubjectName():mapping.subjectName(),columns));
      if (mapping!=null) totals.add(new Total(mapping.subjectCode(),mapping.subjectName(),text(row.getAmount())));
    }
    return new AuxiliaryClassificationResponse(scope.itemId(),scope.month(),version.getId(),complete?"CLASSIFIED":"PENDING",
        complete?"辅料已归类":"辅料待财务归类",editable,version.getContentFingerprint(),List.copyOf(result),definitions,
        complete?totals(totals):List.of());
  }

  private Classification validMapping(QuoteTechDataVersion version, QuoteTechAuxItem row,
      List<Classification> mappings, List<Subject> definitions) {
    var found=mappings.stream().filter(m -> m.detailId().equals(row.getId()) && m.contentFingerprint().equals(version.getContentFingerprint())).toList();
    if (found.size()!=1) return null;
    var value=found.getFirst();
    try { return subjects.require(definitions,value.subjectName()).code().equals(value.subjectCode())?value:null; }
    catch (IllegalArgumentException invalid) { return null; }
  }

  private Preview validate(AuxiliaryClassificationResponse view, List<Row> rows) {
    List<Issue> issues=new ArrayList<>(); Set<Long> seen=new HashSet<>(); List<Total> amounts=new ArrayList<>();
    Map<Long,Item> expected=new LinkedHashMap<>(); view.items().forEach(item -> expected.put(item.detailId(),item));
    if (rows==null || rows.isEmpty()) return new Preview(false,null,List.of(new Issue(null,"文件中没有辅料明细")),List.of());
    for (var row:rows) {
      try {
        if (row.columns().size()!=22) throw new IllegalArgumentException("表头或列数不正确，请使用下载的辅料归类表");
        Long id;
        try { id=Long.valueOf(row.columns().get(4)); } catch (NumberFormatException e) { throw new IllegalArgumentException("明细标识无效"); }
        var original=expected.get(id);
        if (original==null) throw new IllegalArgumentException("明细不属于本产品当前审批版本");
        if (!seen.add(id)) throw new IllegalArgumentException("明细重复");
        if (!original.approvedColumns().equals(row.columns().subList(0,21))) {
          throw new IllegalArgumentException("产品、月份、审批版本、技术资料或金额被修改；只能填写二级科目名称");
        }
        var subject=subjects.require(view.subjects(),row.columns().getLast());
        amounts.add(new Total(subject.code(),subject.name(),original.amount()));
      } catch (IllegalArgumentException invalid) { issues.add(new Issue(row.sheetRow(),invalid.getMessage())); }
    }
    if (!seen.equals(expected.keySet())) issues.add(new Issue(null,"请完整导入全部已审批辅料明细，不能遗漏"));
    String fingerprint=json.canonicalHash(List.of(view,rows));
    return new Preview(issues.isEmpty(),fingerprint,List.copyOf(issues),issues.isEmpty()?totals(amounts):List.of());
  }

  private List<Total> totals(List<Total> amounts) {
    Map<String,BigDecimal> totals=new TreeMap<>(); Map<String,String> names=new HashMap<>();
    for (var amount:amounts) { totals.merge(amount.subjectCode(),new BigDecimal(amount.amount()),BigDecimal::add); names.put(amount.subjectCode(),amount.subjectName()); }
    return totals.entrySet().stream().map(e -> new Total(e.getKey(),names.get(e.getKey()),e.getValue().toPlainString())).toList();
  }

  private Scope scope(String oaNo, Long itemId, String month, TechnicalDataActor actor, boolean lock) {
    try { YearMonth.parse(month); } catch (RuntimeException e) { throw new IllegalArgumentException("核算月份必须为 yyyy-MM"); }
    if (actor==null || actor.shortSession() || !(actor.admin() || actor.has("ingest:quote:list") || actor.has("ingest:quote:cost-run:execute"))) throw forbidden("无权读取报价辅料归类");
    var item=items.selectById(itemId); var form=item==null?null:forms.selectById(item.getOaFormId());
    if (form==null || !Objects.equals(oaNo,form.getOaNo())) throw forbidden("产品行不属于当前报价单");
    String businessUnit=item.getBusinessUnitType()==null?form.getBusinessUnitType():item.getBusinessUnitType();
    if (!actor.admin() && !Objects.equals(businessUnit,BusinessUnitContext.getCurrentBusinessUnitType())) throw forbidden("无权操作其他业务单元的报价");
    var candidates=products.selectActiveCandidatesByItemAndMonth(itemId,month);
    if (candidates.size()>1) throw conflict("同一产品月份存在重复活动资料");
    QuoteTechProduct product=candidates.isEmpty()?null:candidates.getFirst();
    // 其他报价仅复用原产品的同一辅料来源，不产生第二份财务归类。
    var owner=shared.find(item.getMaterialNo(),itemId,"AUXILIARY");
    if (owner!=null) {
      if (!Objects.equals(owner.businessUnit(),businessUnit)) throw conflict("原辅料补录业务单元与本产品不符");
      product=technical.findProduct(owner.productId()).orElseThrow(() -> conflict("原辅料产品不存在"));
    }
    if (product!=null && modules.selectByProductId(product.getId()).stream().noneMatch(m -> "AUXILIARY".equals(m.getModuleType()) && Integer.valueOf(1).equals(m.getRequiredFlag()))) product=null;
    if (product==null) return new Scope(oaNo,itemId,month,null,null);
    var task=lock?technical.lockTask(product.getTaskId()).orElseThrow():technical.findTask(product.getTaskId()).orElseThrow();
    if (lock) { product=technical.lockProduct(product.getId()).orElseThrow(); if(task.getOaFlowId()!=null) workflow.lockFlow(task.getOaFlowId()); }
    return new Scope(oaNo,itemId,month,task,product);
  }

  private AuxiliaryClassificationResponse empty(Scope scope,String status,String message) {
    return new AuxiliaryClassificationResponse(scope.itemId(),scope.month(),null,status,message,false,null,List.of(),List.of(),List.of());
  }
  private void requireEditable(AuxiliaryClassificationResponse view) {
    if (!view.canClassify() || view.items().isEmpty()) throw forbidden("仅当前 OA 财务节点报价员或管理员可为已审批上传辅料归类："+view.message());
  }
  private static String text(Object value) { return value==null?"":value instanceof BigDecimal decimal?decimal.toPlainString():value.toString(); }
  private TechnicalDataTaskException conflict(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT,message); }
  private TechnicalDataTaskException forbidden(String message) { return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN,message); }
  private record Scope(String oaNo,Long itemId,String month,QuoteTechTask task,QuoteTechProduct product) {}
}
