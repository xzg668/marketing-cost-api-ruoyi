package com.sanhua.marketingcost.service.technicaldata;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import com.sanhua.marketingcost.service.quotebom.MonthlyBomSnapshotDetailService;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Converts the scoped, saved full BOM to immutable monthly rows consumed by the existing builder. */
@Service
public class TechnicalBomPreparationSource {
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalBomContextQuery contexts;
  private final TechnicalBomSavedSourceQuery saved;
  private final TechnicalBomAbsenceQuery absence;
  private final TechnicalDataSharedModules shared;
  private final TechnicalDataCostingSources approvals;
  private final QuoteBomMonthlySnapshotMapper snapshots;
  private final MonthlyBomSnapshotDetailService details;
  private final OaFormItemMapper items;
  public TechnicalBomPreparationSource(QuoteTechnicalDataRepository repository,TechnicalBomContextQuery contexts,
      TechnicalBomSavedSourceQuery saved,TechnicalBomAbsenceQuery absence,TechnicalDataSharedModules shared,
      TechnicalDataCostingSources approvals,QuoteBomMonthlySnapshotMapper snapshots,MonthlyBomSnapshotDetailService details,OaFormItemMapper items) {
    this.repository=repository; this.contexts=contexts; this.saved=saved; this.absence=absence; this.shared=shared;
    this.approvals=approvals; this.snapshots=snapshots; this.details=details; this.items=items;
  }
  public static boolean isTechnical(String source) { return "TECH_REFERENCE".equals(source)||"TECH_UPLOAD".equals(source); }

  /** Price preparation can inspect saved draft rows, without publishing the BOM or approving a cost. */
  public boolean matchesSavedPreparation(QuoteBomPreparationRecord record, List<QuoteEffectiveBomNode> nodes) {
    if (nodes.isEmpty() || nodes.stream().noneMatch(node -> isTechnical(node.getSourceBomType()))) return false;
    var product = repository.findActiveProduct(record.getOaFormItemId(), record.getCostPeriodMonth()).orElse(null);
    if (product == null) return false;
    var task = repository.findTask(product.getTaskId()).orElseThrow();
    var current = saved.read(contexts.read(task, product).context());
    if (current == null) return false;
    var snapshot = snapshots.selectById(nodes.getFirst().getOriginMonthlySnapshotId());
    return snapshot != null && isTechnical(snapshot.getBomSource())
        && Objects.equals(snapshot.getSourceOaFormItemId(), record.getOaFormItemId())
        && Objects.equals(snapshot.getCostPeriodMonth(), record.getCostPeriodMonth())
        && Objects.equals(snapshot.getPriceOrgCode(), record.getPriceOrgCode())
        && Objects.equals(snapshot.getStructureFingerprint(), current.fingerprint())
        && nodes.stream().allMatch(node -> Objects.equals(node.getOriginMonthlySnapshotId(), snapshot.getId())
            && (isTechnical(node.getSourceBomType()) && Objects.equals(node.getSourceBomBatchId(), snapshot.getBomBatchId())
                || Set.of("TECH_PACKAGE", "TECH_SOLDER").contains(Objects.toString(node.getSourceBomType(), ""))));
  }

  @Transactional
  public QuoteBomMonthlySnapshot snapshot(Long itemId,String month,String businessUnit,String organization,String customer,String packageMethod) {
    var product=repository.findActiveProduct(itemId,month).orElse(null); if(product==null) return null;
    var task=repository.findTask(product.getTaskId()).orElseThrow();
    var context=contexts.read(task,product).context();
    var current=saved.read(context); if(current==null) return null;
    if(!Objects.equals(businessUnit,context.businessUnitType())||!Objects.equals(organization,context.priceOrgCode()))
      throw new IllegalArgumentException("完整BOM组织与本次核算不一致");
    var available=absence.read(context);
    if("U9".equals(available.state())||"GALLERY".equals(available.state())) return null;
    if(!available.allowsEntry()) throw new IllegalStateException(available.message());
    boolean submitted=TechnicalDataSubmissionState.submitted(product.getProductStatus())
        &&TechnicalDataSubmissionState.submitted(current.module().getModuleStatus())&&product.getEffectiveVersionId()!=null;
    Long versionId=current.version().getId();
    if(submitted) {
      var owner=shared.find(product.getMaterialNo(),itemId,"DRAWING_BOM");
      if(owner==null||!Objects.equals(owner.productId(),product.getId())) throw new IllegalStateException("完整BOM的当前办理来源不一致");
      versionId=approvals.requireSource(context,owner).version().getId();
    }
    String batch="TB:"+versionId+":"+current.fingerprint().substring(0,32);
    String status=submitted?"SUCCESS":"DRAFT";
    items.selectForCostingWrite(itemId);
    var found=snapshots.selectList(Wrappers.<QuoteBomMonthlySnapshot>lambdaQuery()
        .eq(QuoteBomMonthlySnapshot::getSourceOaFormItemId,itemId).eq(QuoteBomMonthlySnapshot::getCostPeriodMonth,month)
        .eq(QuoteBomMonthlySnapshot::getPriceOrgCode,organization).eq(QuoteBomMonthlySnapshot::getBusinessUnitType,businessUnit)
        .eq(QuoteBomMonthlySnapshot::getCustomerCode,Objects.toString(customer,""))
        .eq(QuoteBomMonthlySnapshot::getPackageMethod,Objects.toString(packageMethod,""))
        .eq(QuoteBomMonthlySnapshot::getBomBatchId,batch).eq(QuoteBomMonthlySnapshot::getSyncStatus,status));
    if(found.size()>1) throw new IllegalStateException("完整技术BOM快照重复，请核实来源");
    if(!found.isEmpty()) {
      if(!Objects.equals(found.getFirst().getStructureFingerprint(),current.fingerprint())||details.load(found.getFirst().getId()).isEmpty())
        throw new IllegalStateException("完整技术BOM快照不完整，请核实保存依据");
      return found.getFirst();
    }
    var now=LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var rows=rows(current.bom(),versionId,batch,now);
    var result=new QuoteBomMonthlySnapshot(); result.setProductCode(context.productCode()); result.setPriceOrgCode(organization);
    result.setBusinessUnitType(businessUnit); result.setMaterialOrganizationCode(context.materialOrganizationCode());
    result.setCustomerCode(Objects.toString(customer,"")); result.setPackageMethod(Objects.toString(packageMethod,"")); result.setCostPeriodMonth(month);
    result.setBomSource("TECH_"+current.bom().entryMode()); result.setBomPurpose("主制造"); result.setBomVersion("TB-"+versionId);
    result.setSyncType("TECH_PREPARATION"); result.setSyncStatus(status); result.setSyncAt(now); result.setSyncBy("系统");
    result.setSourceOaNo(context.oaNo()); result.setSourceOaFormItemId(itemId); result.setBomBatchId(batch);
    result.setStructureFingerprint(current.fingerprint()); result.setLineCount(rows.size()); result.setActiveFlag(1);
    snapshots.insert(result); details.save(result.getId(),rows); return result;
  }
  static List<BomRawHierarchy> rows(TechnicalBomContent bom,Long versionId,String batch,LocalDateTime now) {
    var scope=bom.scope(); var result=new ArrayList<BomRawHierarchy>();
    var root=new BomRawHierarchy(); root.setTopProductCode(scope.costingProductCode()); root.setParentCode(scope.costingProductCode());
    root.setMaterialCode(scope.costingProductCode()); root.setMaterialName(bom.product().name()); root.setMaterialSpec(bom.product().specification());
    root.setPath("/"+scope.costingProductCode()+"/"); root.setLevel(0); root.setSortSeq(0); root.setQtyPerParent(BigDecimal.ONE); root.setQtyPerTop(BigDecimal.ONE);
    root.setShapeAttr("制造件"); root.setSourceCategory("制造件"); root.setIsLeaf(0); root.setSourceLineKey("TB:"+versionId+":ROOT"); result.add(root);
    var byHierarchy=new LinkedHashMap<String,TechnicalBomContent.Node>(); bom.nodes().forEach(node->byHierarchy.put(node.input().hierarchy(),node));
    var parents=new HashSet<String>(); bom.nodes().forEach(node->parents.add(TechnicalBomRows.parent(node.input().hierarchy())));
    var paths=new HashMap<String,String>();
    for(var node:bom.nodes()) {
      var input=node.input(); var row=new BomRawHierarchy(); String parent=TechnicalBomRows.parent(input.hierarchy());
      row.setTopProductCode(scope.costingProductCode()); row.setParentCode(parent==null?scope.costingProductCode():byHierarchy.get(parent).resolvedMaterialCode());
      row.setMaterialCode(node.resolvedMaterialCode()); row.setMaterialName(input.name()); row.setMaterialSpec(input.specification());
      row.setPath(path(input.hierarchy(),byHierarchy,paths,root.getPath())); row.setLevel(input.hierarchy().split("\\.").length); row.setSortSeq(result.size());
      row.setQtyPerParent(node.quantityPerParent()); row.setQtyPerTop(node.quantityPerTop()); row.setShapeAttr(input.attribute()); row.setSourceCategory(input.attribute());
      row.setPriceOrgCode(node.reference() == null ? scope.priceOrgCode() : Objects.toString(node.reference().priceOrgCode(), scope.priceOrgCode()));
      row.setCostElementCode(node.costElementCode()); row.setMaterialCategory1(node.categoryCode()); row.setMaterialCategory2(node.categoryName());
      row.setIsLeaf(parents.contains(input.hierarchy())?0:1); row.setSourceLineKey("TB:"+versionId+":"+input.hierarchy()); result.add(row);
    }
    for(var row:result) {
      if (row.getPriceOrgCode() == null) row.setPriceOrgCode(scope.priceOrgCode());
      row.setBusinessUnitType(scope.businessUnit()); row.setBomPurpose("主制造"); row.setBomVersion("TB-"+versionId);
      row.setBomStatus("APPROVED"); row.setChildType("标准"); row.setSourceType("TECH_"+bom.entryMode());
      row.setSourceImportBatchId(batch); row.setBuildBatchId(batch); row.setBuiltAt(now); row.setEffectiveFrom(YearMonth.parse(scope.accountingMonth()).atDay(1));
    }
    return List.copyOf(result);
  }
  private static String path(String hierarchy,Map<String,TechnicalBomContent.Node> nodes,Map<String,String> paths,String root) {
    if(paths.containsKey(hierarchy)) return paths.get(hierarchy);
    String parent=TechnicalBomRows.parent(hierarchy);
    String value=(parent==null?root:path(parent,nodes,paths,root))+nodes.get(hierarchy).resolvedMaterialCode()+"@TB_"+hierarchy.replace('.','_')+"/";
    paths.put(hierarchy,value); return value;
  }
}
