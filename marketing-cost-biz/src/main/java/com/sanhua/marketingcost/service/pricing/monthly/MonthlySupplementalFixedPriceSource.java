package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.PriceItem;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.technicaldata.TechnicalDataOaWorkflowRepository.Flow;
import com.sanhua.marketingcost.mapper.monthly.MonthlySupplementalFixedPriceSourceMapper;
import com.sanhua.marketingcost.mapper.monthly.MonthlySupplementalFixedPriceSourceMapper.Ownership;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.service.technicaldata.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionRetryPolicy.Failure;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyMaterialPrices.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/** Final effective supplemental fixed price; current submitted/publication flags alone never qualify. */
@Component
public final class MonthlySupplementalFixedPriceSource {
  public record VersionIdentity(Long id,Integer versionNo,String status,String contentFingerprint,String referenceSnapshotJson) {
    static VersionIdentity of(QuoteTechDataVersion value){return new VersionIdentity(value.getId(),value.getVersionNo(),value.getVersionStatus(),value.getContentFingerprint(),value.getReferenceSnapshotJson());}
  }
  public record Approval(Ownership original,VersionIdentity effective,VersionIdentity personal,Flow flow,String confirmationBasisHash) {}
  public record FixedSource(PriceFixedItem selected,PriceItem declaration,Approval approval,MaterialMasterRaw currentMaterial) {}
  public record Snapshot(int snapshotSchema,String sourceKind,FixedSource source,String unit,String currency,TaxBasis taxBasis) {}
  private record ProductCapture(List<QuoteTechModule> modules,Map<Long,QuoteTechDataVersion> versions,
      Map<Long,List<QuoteTechPackageItem>> packages,Map<Long,List<QuoteTechAuxItem>> auxiliary,
      Map<Long,List<QuoteTechSalaryItem>> salary) {}
  private record ApprovalKey(TechnicalDataSharedModuleRepository.Owner owner,Integer taskActive,Integer productActive,
      String productStatus,Long effective,Long form,Long flow,String consumerOrganization) {}
  private record ApprovalBasis(VersionIdentity effective,VersionIdentity personal,Flow flow,String confirmationBasisHash) {}
  private record Approved(ApprovalBasis proof,Map<String,PriceItem> personal,Map<String,PriceItem> effective,Problem error) {
    Approval approval(Ownership claim){return new Approval(claim,proof.effective(),proof.personal(),proof.flow(),proof.confirmationBasisHash());}
  }
  private final MonthlySupplementalFixedPriceSourceMapper rows;
  private final TechnicalDataCostingSources approvals;
  private final TechnicalDataVersionContentCodec content;
  private final OaMessageCodec json;
  private final MonthlyPriceDocumentCodec documents;

  public MonthlySupplementalFixedPriceSource(MonthlySupplementalFixedPriceSourceMapper rows,TechnicalDataCostingSources approvals,
      TechnicalDataVersionContentCodec content,OaMessageCodec json,MonthlyPriceDocumentCodec documents) {
    this.rows=rows;this.approvals=approvals;this.content=content;this.json=json;
    this.documents=documents;
  }

  public Candidates read(Context context,Set<MonthlyPriceRequest> requests) {
    var values=new LinkedHashMap<MonthlyPriceRequest,Candidate>();var problems=new LinkedHashMap<MonthlyPriceRequest,Problem>();
    if(requests.isEmpty())return new Candidates(values,problems);
    for(var request:requests)if(!request.family().businessUnitType().equals(context.businessUnitType()) || !request.family().month().equals(context.month()))
      throw new MonthlyPriceProblem(Failure.INVALID_INPUT,"Technical source scope differs from request");
    Long currentForm=rows.currentForm(context.oaFormItemId(),context.oaNo(),context.businessUnitType());
    if(currentForm==null)throw new MonthlyPriceProblem(Failure.INVALID_INPUT,"Monthly technical source requires its actual quote item/form scope");
    var byCode=new TreeMap<String,List<MonthlyPriceRequest>>();requests.forEach(request->byCode.computeIfAbsent(request.family().materialCode(),ignored->new ArrayList<>()).add(request));
    var capturedProducts=new HashMap<Long,ProductCapture>();var capturedFlows=new HashMap<Long,Flow>();
    var capturedBasis=new HashMap<Long,String>();var approvalCache=new HashMap<ApprovalKey,Approved>();
    for(var batch:batches(byCode.keySet())) {
      var owners=new LinkedHashMap<String,Ownership>();
      rows.owners(context.businessUnitType(),batch).forEach(owner->{if(owners.putIfAbsent(owner.materialCode(),owner)!=null)throw new MonthlyPriceProblem(Failure.INVALID_INPUT,"Duplicate technical price claim");});
      var requiredProducts=new TreeSet<Long>();owners.values().forEach(owner->{if(!capturedProducts.containsKey(owner.productId()))requiredProducts.add(owner.productId());});
      for(var products:batches(requiredProducts))capture(products,owners.values(),capturedProducts);
      var requiredFlows=new TreeSet<Long>();owners.values().forEach(owner->{if(owner.flowId()!=null && !capturedBasis.containsKey(owner.flowId()))requiredFlows.add(owner.flowId());});
      for(var ids:batches(requiredFlows)) {
        rows.flows(ids).forEach(flow->capturedFlows.put(flow.id(),flow));
        var bases=new HashMap<Long,List<String>>();ids.forEach(id->bases.put(id,new ArrayList<>()));
        rows.submissions(ids).forEach(value->bases.get(value.flowId()).add(value.basis()));
        bases.forEach((id,basis)->capturedBasis.put(id,basis.toString()));
      }
      var fixed=new HashMap<String,List<PriceFixedItem>>();
      if(!owners.isEmpty()) {
        rows.fixed(context.businessUnitType(),owners.keySet()).forEach(row->fixed.computeIfAbsent(row.getMaterialCode(),ignored->new ArrayList<>()).add(row));
      }
      var masterByOrganization=new HashMap<String,Map<String,List<MaterialMasterRaw>>>();
      var organizations=new TreeSet<String>();batch.forEach(code->byCode.get(code).forEach(request->organizations.add(request.priceOrgCode())));
      for(String organization:organizations) {
        var found=new HashMap<String,List<MaterialMasterRaw>>();
        if(!owners.isEmpty())rows.materials(MaterialOrganization.fromPriceOrgCode(organization).getCode(),owners.keySet())
            .forEach(row->found.computeIfAbsent(row.getRequestedMaterialCode(),ignored->new ArrayList<>()).add(row.reference()));
        masterByOrganization.put(organization,found);
      }
      for(String code:batch)for(var request:byCode.get(code))try {
        var owner=owners.get(code);
        if(owner==null){problems.put(request,new Problem(Failure.MISSING,"当前料号没有技术补录办理来源"));continue;}
        // Supplemental declarations are untaxed. The other legacy tax probe is inapplicable,
        // rather than an invalid declaration that could conceal this quote's editable draft gap.
        if(request.family().taxBasis()!=TaxBasis.EXCLUDED){
          problems.put(request,new Problem(Failure.MISSING,"补录固定价没有适用的含税口径"));continue;
        }
        if(!"PRICE".equals(owner.moduleType()))throw new IllegalArgumentException("技术价格声明必须由PRICE模块办理");
        if(!owner.claimOrganization().equals(request.priceOrgCode()) || !owner.claimBusinessUnit().equals(context.businessUnitType())
            || !owner.unit().equals(request.family().unit()) || !owner.currency().equals(request.family().currency())
            || !"CNY".equals(owner.currency()))
          throw new IllegalArgumentException("技术价格声明的组织、业务单元、单位或币税口径不适用");
        var key=new ApprovalKey(owner.owner(),owner.taskActive(),owner.productActive(),owner.productStatus(),owner.effectiveVersionId(),owner.originalFormId(),owner.flowId(),request.priceOrgCode());
        var approved=approvalCache.computeIfAbsent(key,ignored->approve(context,currentForm,request,owner,capturedProducts.get(owner.productId()),capturedFlows.get(owner.flowId()),capturedBasis.get(owner.flowId())));
        if(approved.error()!=null){
          if(approved.error().reason()==Failure.MISSING && !fixed.getOrDefault(code,List.of()).isEmpty())
            throw new IllegalArgumentException("待编辑补录版本不能已有发布固定价");
          problems.put(request,approved.error());continue;
        }
        var fixedRows=fixed.getOrDefault(code,List.of()).stream().filter(row->Objects.equals(row.getTechnicalVersionId(),owner.sourceVersionId()) && "AVAILABLE".equals(row.getTechnicalPublicationStatus())).toList();
        if(fixedRows.size()>1)throw new IllegalArgumentException("同一最终补录料号存在多个已可用固定价");
        if(fixedRows.isEmpty()){problems.put(request,new Problem(Failure.MISSING,"没有最终有效补录固定价；联动价从财务导入的联动价格表读取"));continue;}
        var row=fixedRows.getFirst();String itemKey=row.getTechnicalItemKey();
        var declaration=approved.personal().get(itemKey);var effectiveItem=approved.effective().get(itemKey);
        if(declaration==null || effectiveItem==null || !content.sameSourceEvidence(declaration,effectiveItem))
          throw new IllegalArgumentException("实际价格项未一致归入最终生效版本");
        if(!TechnicalDataPriceRules.validate(declaration).isEmpty() || !"FIXED".equals(declaration.entryMode())
            || !code.equals(declaration.materialNo()) || !owner.claimOrganization().equals(declaration.organizationCode())
            || !owner.unit().equals(declaration.unit()) || !owner.currency().equals(declaration.currency()))
          throw new IllegalArgumentException("补录声明内容、身份或固定价类型无效");
        if(!request.priceOrgCode().equals(row.getOrgCode()) || !request.family().unit().equals(row.getUnit())
            || !context.businessUnitType().equals(row.getBusinessUnitType()) || !Integer.valueOf(0).equals(row.getTaxIncluded())
            || row.getFixedPrice()==null || row.getFixedPrice().signum()<0 || row.getFixedPrice().compareTo(declaration.unitPrice())!=0)
          throw new IllegalArgumentException("实际固定价金额、组织、单位或税口径与最终声明不一致");
        if(request.family().requestedSupplierCode()!=null && !request.family().requestedSupplierCode().equals(row.getSupplierCode()))
          throw new IllegalArgumentException("补录来源没有声明本次指定供应商");
        var masterRows=masterByOrganization.get(request.priceOrgCode()).getOrDefault(code,List.of());
        if(masterRows.size()!=1 || !row.getUnit().equals(masterRows.getFirst().getUnit()))
          throw new IllegalArgumentException("补录料品采购单位已变化或不唯一");
        var snapshot=new Snapshot(1,"TECH_FIXED",new FixedSource(row,declaration,approved.approval(owner),masterRows.getFirst()),row.getUnit(),"CNY",TaxBasis.EXCLUDED);
        var fact=PriceFact.normal(row.getFixedPrice(),SourceEvidence.ofJson(documents.unwrap(documents.encode(snapshot))),Set.of());
        var coverage=MonthlyPriceApplicability.of(new MonthlyPriceCoverage(Set.of(request.priceOrgCode()),QuantityInterval.all(),ConditionDomain.any()));
        values.put(request,new Candidate(new Selection(null,coverage,fact),null));
      }catch(IllegalArgumentException | IllegalStateException invalid){problems.put(request,new Problem(Failure.INVALID_INPUT,"最终技术价格来源无效："+invalid.getMessage()));}
    }
    return new Candidates(values,problems);
  }

  private Approved approve(Context context,Long form,MonthlyPriceRequest request,Ownership owner,ProductCapture data,Flow flow,String basis) {
    try {
      var readContext=new QuoteBomReadContext(form,context.oaFormItemId(),context.oaNo(),context.month().toString(),context.businessUnitType(),context.productCode(),null,null,null,request.priceOrgCode(),MaterialOrganization.fromPriceOrgCode(request.priceOrgCode()).getCode(),context.priceAsOfTime().toLocalDate(),context.priceAsOfTime());
      var captured=new TechnicalDataCostingSources.Captured(owner.task(),owner.product(),data.versions(),data.modules(),data.packages(),data.auxiliary(),data.salary(),flow,basis);
      var qualified=approvals.requireCapturedSource(readContext,owner.owner(),captured);
      var personal=data.versions().get(owner.sourceVersionId());
      var proof=new ApprovalBasis(VersionIdentity.of(qualified.version()),VersionIdentity.of(personal),flow,basis==null?null:json.canonicalHash(basis));
      return new Approved(proof,items(personal),items(qualified.version()),null);
    }catch(EffectiveTechnicalDataException blocked) {
      // Dispatch claims its own missing materials before the technician has a submitted price.
      // Keep that editable demand missing; no pending declaration is a monthly-price candidate.
      if("TECH_DATA_TASK_NOT_SUBMITTED".equals(blocked.errorCode()) && ownEditableGap(context,form,owner,data))
        return new Approved(null,Map.of(),Map.of(),new Problem(Failure.MISSING,blocked.getMessage()));
      var waiting=Set.of("TECH_DATA_TASK_NOT_SUBMITTED","TECH_DATA_FINANCE_CONFIRMATION_REQUIRED").contains(blocked.errorCode());
      return new Approved(null,Map.of(),Map.of(),new Problem(waiting?Failure.WAITING:Failure.INVALID_INPUT,blocked.getMessage()));
    }catch(IllegalArgumentException | IllegalStateException | NullPointerException invalid) {
      return new Approved(null,Map.of(),Map.of(),new Problem(Failure.INVALID_INPUT,"技术生效依据捕获无效："+invalid.getMessage()));
    }
  }

  private boolean ownEditableGap(Context context,Long form,Ownership owner,ProductCapture data) {
    if(!Objects.equals(form,owner.originalFormId()) || !Objects.equals(context.oaFormItemId(),owner.quoteItemId())
        || !context.month().toString().equals(owner.originalMonth())
        || !Integer.valueOf(1).equals(owner.taskActive()) || !Integer.valueOf(1).equals(owner.productActive())
        || owner.effectiveVersionId()!=null
        || !Set.of("PENDING","IN_PROGRESS","PARTIALLY_RETURNED").contains(owner.taskStatus())
        || !Set.of("PENDING","EDITING","RETURNED").contains(owner.productStatus())
        || !Set.of("PENDING","EDITING","READY","RETURNED").contains(owner.moduleStatus()))return false;
    var module=data.modules().stream().filter(value->Objects.equals(value.getId(),owner.moduleId())).findFirst().orElse(null);
    if(module==null || !Objects.equals(module.getProductId(),owner.productId()) || !"PRICE".equals(module.getModuleType())
        || !Integer.valueOf(1).equals(module.getRequiredFlag()) || !Integer.valueOf(1).equals(module.getOaEditAllowed())
        || module.getAssigneeUserId()==null || !Objects.equals(module.getAssigneeUserId(),owner.assigneeId())
        || !Objects.equals(module.getModuleStatus(),owner.moduleStatus())
        || !Objects.equals(module.getCurrentVersionId(),owner.sourceVersionId()))return false;
    if(owner.sourceVersionId()==null)return "PENDING".equals(owner.moduleStatus());
    var draft=data.versions().get(owner.sourceVersionId());
    if(draft==null || !Objects.equals(draft.getProductId(),owner.productId()) || !"DRAFT".equals(draft.getVersionStatus()))return false;
    // A malformed stored draft is an error, while incomplete editable price fields remain editable.
    content.prices(draft);
    return true;
  }

  private Map<String,PriceItem> items(QuoteTechDataVersion version) {
    var prices=content.prices(version);var result=new LinkedHashMap<String,PriceItem>();
    if(prices!=null)for(var item:prices.items())if(result.putIfAbsent(item.itemKey(),item)!=null)
      throw new IllegalArgumentException("最终补录版本存在重复价格项身份");
    return Map.copyOf(result);
  }

  private void capture(List<Long> products,Collection<Ownership> owners,Map<Long,ProductCapture> result) {
    var moduleRows=new HashMap<Long,List<QuoteTechModule>>();rows.modules(products).forEach(row->moduleRows.computeIfAbsent(row.getProductId(),ignored->new ArrayList<>()).add(row));
    // Freeze explicit version identities before any detail read; later live pointer changes cannot add rows.
    var capturedIds=new TreeSet<Long>();moduleRows.values().forEach(roster->roster.stream().map(QuoteTechModule::getCurrentVersionId).filter(Objects::nonNull).forEach(capturedIds::add));
    owners.stream().filter(owner->products.contains(owner.productId())).forEach(owner->{if(owner.sourceVersionId()!=null)capturedIds.add(owner.sourceVersionId());if(owner.effectiveVersionId()!=null)capturedIds.add(owner.effectiveVersionId());});
    var versionRows=new HashMap<Long,QuoteTechDataVersion>();var packageRows=emptyDetails(capturedIds,QuoteTechPackageItem.class);
    var auxiliaryRows=emptyDetails(capturedIds,QuoteTechAuxItem.class);var salaryRows=emptyDetails(capturedIds,QuoteTechSalaryItem.class);
    if(!capturedIds.isEmpty()) {
      rows.versions(capturedIds).forEach(row->versionRows.put(row.getId(),row));
      rows.packages(capturedIds).forEach(row->addDetail(packageRows,row.getVersionId(),row));
      rows.auxiliary(capturedIds).forEach(row->addDetail(auxiliaryRows,row.getVersionId(),row));
      rows.salary(capturedIds).forEach(row->addDetail(salaryRows,row.getVersionId(),row));
    }
    var header=new HashMap<Long,Ownership>();owners.forEach(owner->header.putIfAbsent(owner.productId(),owner));
    for(Long product:products) {
      var roster=moduleRows.getOrDefault(product,List.of());var ids=new HashSet<Long>();
      roster.stream().map(QuoteTechModule::getCurrentVersionId).filter(Objects::nonNull).forEach(ids::add);
      if(header.get(product).effectiveVersionId()!=null)ids.add(header.get(product).effectiveVersionId());
      var versions=new HashMap<Long,QuoteTechDataVersion>();var packages=new HashMap<Long,List<QuoteTechPackageItem>>();
      var auxiliary=new HashMap<Long,List<QuoteTechAuxItem>>();var salary=new HashMap<Long,List<QuoteTechSalaryItem>>();
      for(Long id:ids)if(versionRows.containsKey(id)){versions.put(id,versionRows.get(id));packages.put(id,packageRows.get(id));auxiliary.put(id,auxiliaryRows.get(id));salary.put(id,salaryRows.get(id));}
      result.put(product,new ProductCapture(roster,versions,packages,auxiliary,salary));
    }
  }
  private static <T> Map<Long,List<T>> emptyDetails(Collection<Long> ids,Class<T> type) {
    var values=new HashMap<Long,List<T>>();ids.forEach(id->values.put(id,new ArrayList<>()));return values;
  }
  private static <T> void addDetail(Map<Long,List<T>> captured,Long id,T row) {
    var rows=captured.get(id);
    if(rows==null)throw new MonthlyPriceProblem(Failure.REPLAN_REQUIRED,"技术版本明细超出本次已捕获范围，请重新规划");
    rows.add(row);
  }
  private static <T extends Comparable<? super T>> List<List<T>> batches(Collection<T> values) {
    var all=new ArrayList<>(new TreeSet<>(values));var result=new ArrayList<List<T>>();
    for(int start=0;start<all.size();start+=256)result.add(all.subList(start,Math.min(start+256,all.size())));return result;
  }
}
