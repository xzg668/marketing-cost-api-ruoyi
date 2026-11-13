package com.sanhua.marketingcost.controller;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomResponse;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.*;
import com.sanhua.marketingcost.service.materialshape.QuoteMaterialShapeInputs;
import java.security.Principal;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Quotation-local choice: public source classification is maintained separately by finance. */
@RestController
@RequestMapping("/api/v1/quote-requests/{oaNo}/items/{itemId}/effective-bom/material-shape")
public class QuoteMaterialShapeController {
  public record Change(String periodMonth,String materialCode,String organization,
      String selectedShape,Long expectedVersion,String expectedVariantHash) {}
  private final QuoteEffectiveBomApplicationService boms;
  private final QuoteMaterialShapeInputs inputs;
  private final QuoteCostingWorkspaceService workspaces;
  private final QuoteCostRunVersionInvalidationService versions;
  private final JdbcTemplate jdbc;
  @org.springframework.beans.factory.annotation.Autowired private com.sanhua.marketingcost.service.jjb.JjbVersionAccess access;
  @org.springframework.beans.factory.annotation.Autowired private com.sanhua.marketingcost.service.costing.MonthlyCostVersionService history;
  public QuoteMaterialShapeController(QuoteEffectiveBomApplicationService boms,QuoteMaterialShapeInputs inputs,
      QuoteCostingWorkspaceService workspaces,QuoteCostRunVersionInvalidationService versions,JdbcTemplate jdbc) {
    this.boms=boms;this.inputs=inputs;this.workspaces=workspaces;this.versions=versions;this.jdbc=jdbc;
  }

  @GetMapping
  @PreAuthorize("@ss.hasAnyPermi('ingest:quote:list')")
  public CommonResult<QuoteEffectiveBomResponse> historical(@PathVariable String oaNo,@PathVariable Long itemId,
      @RequestParam Long versionId) {
    var owned=access.read(oaNo,itemId,versionId);
    var stored=history.read(owned.businessUnit(),versionId);
    return CommonResult.success(stored.snapshot()==null ? null : stored.snapshot().pricingBom());
  }

  public record BatchChange(String periodMonth,java.util.List<Selection> selections,String expectedVariantHash) {}
  public record Selection(String materialCode,String organization,String selectedShape,Long expectedVersion) {}

  @PatchMapping
  @PreAuthorize("@ss.hasAnyPermi('quote:costing:bom:alternative-select')")
  @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  @com.sanhua.marketingcost.service.reprice.QuotationWrite(holdUntilCompletion=true)
  public CommonResult<QuoteEffectiveBomResponse> change(@PathVariable String oaNo,@PathVariable Long itemId,
      @RequestBody Change change,Principal actor) {
    if(change==null) throw new IllegalArgumentException("缺少形态选择");
    return apply(oaNo,itemId,new BatchChange(change.periodMonth(),List.of(new Selection(change.materialCode(),
        change.organization(),change.selectedShape(),change.expectedVersion())),change.expectedVariantHash()),actor);
  }

  @PatchMapping("/batch")
  @PreAuthorize("@ss.hasAnyPermi('quote:costing:bom:alternative-select')")
  @Transactional(rollbackFor=Exception.class,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
  @com.sanhua.marketingcost.service.reprice.QuotationWrite(holdUntilCompletion=true)
  public CommonResult<QuoteEffectiveBomResponse> batch(@PathVariable String oaNo,@PathVariable Long itemId,
      @RequestBody BatchChange change,Principal actor) { return apply(oaNo,itemId,change,actor); }

  private CommonResult<QuoteEffectiveBomResponse> apply(String oaNo,Long itemId,BatchChange change,Principal actor) {
    if(inputs.orderInstalled()) throw new IllegalArgumentException("请在OA需求单的多源物料形态页签统一调整，本单全部相关产品一起生效");
    if(change==null || change.selections()==null || change.selections().isEmpty() || change.selections().size()>1000)
      throw new IllegalArgumentException("请选择1至1000个物料");
    String unit=BusinessUnitContext.getCurrentBusinessUnitType();
    if(!inputs.installed()) throw new IllegalStateException("物料形态切换尚未安装");
    var bom=boms.getEffectiveBom(oaNo,itemId);
    if(!Objects.equals(change.periodMonth(),bom.costPeriodMonth()))
      throw new IllegalArgumentException("形态选择月份与当前计价BOM不一致，请刷新");
    if(change.expectedVariantHash()==null || !change.expectedVariantHash().equals(bom.effectiveVariantHash()))
      throw new IllegalArgumentException("计价BOM已发生变化，请刷新后重新选择");
    var seen=new HashSet<String>();var normalized=new ArrayList<Selection>();
    for(var selection:change.selections()) {
      if(selection==null || selection.expectedVersion()==null || selection.expectedVersion()<0
          || selection.materialCode()==null || selection.organization()==null
          || !seen.add(selection.organization()+"|"+selection.materialCode()))
        throw new IllegalArgumentException("批量形态选择缺少料号、组织、版本或包含重复料号");
      var node=bom.nodes().stream().filter(n -> selection.materialCode().equals(n.materialCode())
          && selection.organization().equals(n.priceOrgCode())).findFirst()
          .orElseThrow(()->new IllegalArgumentException("料号"+selection.materialCode()+"不在当前计价BOM中"));
      if("STRUCTURE_ROOT".equals(node.shapeResolutionSource())) throw new IllegalArgumentException("计价BOM结构根节点不能切换形态");
      String shape=selection.selectedShape()==null || selection.selectedShape().isBlank() ? null : selection.selectedShape().trim();
      String original=Objects.toString(node.sourceMaterialShape(),"").trim();
      boolean manufactured=Set.of("MANUFACTURE","制造件","自制件").contains(original);
      if(shape!=null && !Set.of("PURCHASE","OUTSOURCE","MANUFACTURE").contains(shape)) throw new IllegalArgumentException("不支持的报价形态");
      if("MANUFACTURE".equals(shape) && !manufactured)
        throw new IllegalArgumentException("料号"+selection.materialCode()+"的原始采购件或委外加工件不能改成制造件");
      if(!manufactured && !Set.of("OUTSOURCE","委外加工件").contains(original))
        throw new IllegalArgumentException("料号"+selection.materialCode()+"的BOM原始形态不是制造件或委外加工件，不能切换");
      // Discovery is committed separately; a rejected choice must still leave the sources available to classify.
      inputs.checkSelection(unit,bom.costPeriodMonth(),selection.organization(),selection.materialCode(),shape==null ? "MANUFACTURE" : shape,actor.getName());
      normalized.add(new Selection(selection.materialCode(),selection.organization(),shape,selection.expectedVersion()));
    }
    workspaces.lockOrCreate(oaNo,itemId,bom.topProductCode(),bom.costPeriodMonth(),unit);
    for(var selection:normalized.stream().sorted(Comparator.comparing(Selection::organization).thenComparing(Selection::materialCode)).toList()) {
      int count;
      if(selection.expectedVersion()==0) {
        try {
          count=jdbc.update("""
              INSERT INTO lp_quote_material_shape_selection(business_unit_type,oa_form_item_id,period_month,
                price_org_code,material_code,selected_shape,row_version,updated_by)
              VALUES(?,?,?,?,?,?,1,?)
              """,unit,itemId,bom.costPeriodMonth(),selection.organization(),selection.materialCode(),selection.selectedShape(),actor.getName());
        } catch(org.springframework.dao.DuplicateKeyException conflict) {
          throw new IllegalArgumentException("料号"+selection.materialCode()+"形态选择已被其他人更新，请刷新",conflict);
        }
      } else count=jdbc.update("""
          UPDATE lp_quote_material_shape_selection SET selected_shape=?,row_version=row_version+1,updated_by=?,updated_at=CURRENT_TIMESTAMP(6)
          WHERE business_unit_type=? AND oa_form_item_id=? AND period_month=? AND price_org_code=? AND material_code=? AND row_version=?
          """,selection.selectedShape(),actor.getName(),unit,itemId,bom.costPeriodMonth(),selection.organization(),selection.materialCode(),selection.expectedVersion());
      if(count!=1) throw new IllegalArgumentException("料号"+selection.materialCode()+"形态选择已被其他人更新，请刷新");
    }
    workspaces.markItemStale(itemId,bom.costPeriodMonth(),"MATERIAL_SHAPE_CHANGED");
    versions.invalidateProductAfterBomChange(oaNo,itemId,bom.topProductCode(),bom.costPeriodMonth());
    return CommonResult.success(boms.rebuildPreview(oaNo,itemId));
  }
}
