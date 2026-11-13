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

  @PatchMapping
  @PreAuthorize("@ss.hasAnyPermi('quote:costing:bom:alternative-select')")
  @Transactional(rollbackFor=Exception.class)
  @com.sanhua.marketingcost.service.reprice.QuotationWrite(holdUntilCompletion=true)
  public CommonResult<QuoteEffectiveBomResponse> change(@PathVariable String oaNo,@PathVariable Long itemId,
      @RequestBody Change change,Principal actor) {
    if(change==null || change.expectedVersion()==null || change.expectedVersion()<0
        || change.materialCode()==null || change.organization()==null)
      throw new IllegalArgumentException("缺少料号、组织或形态选择版本");
    String unit=BusinessUnitContext.getCurrentBusinessUnitType();
    if(!inputs.installed()) throw new IllegalStateException("物料形态切换尚未安装");
    var bom=boms.getEffectiveBom(oaNo,itemId);
    if(!Objects.equals(change.periodMonth(),bom.costPeriodMonth()))
      throw new IllegalArgumentException("形态选择月份与当前计价BOM不一致，请刷新");
    if(change.expectedVariantHash()==null || !change.expectedVariantHash().equals(bom.effectiveVariantHash()))
      throw new IllegalArgumentException("计价BOM已发生变化，请刷新后重新选择");
    var node=bom.nodes().stream().filter(n -> change.materialCode().equals(n.materialCode())
        && change.organization().equals(n.priceOrgCode())).findFirst()
        .orElseThrow(()->new IllegalArgumentException("该料号不在当前计价BOM中"));
    if(!node.multiPriceSource()) throw new IllegalArgumentException("只有公共多价格表中存在的物料允许切换形态");
    String shape=change.selectedShape()==null || change.selectedShape().isBlank() ? null : change.selectedShape().trim();
    if(shape!=null && !Set.of("PURCHASE","OUTSOURCE","MANUFACTURE").contains(shape))
      throw new IllegalArgumentException("不支持的报价形态");
    if(shape!=null && !node.availableMaterialShapes().contains(shape))
      throw new IllegalArgumentException("料号"+change.materialCode()+"尚无该形态的公共价格来源，请先补齐来源属性");
    workspaces.lockOrCreate(oaNo,itemId,bom.topProductCode(),bom.costPeriodMonth(),unit);
    int count;
    if(change.expectedVersion()==0) {
      try {
        count=jdbc.update("""
            INSERT INTO lp_quote_material_shape_selection(business_unit_type,oa_form_item_id,period_month,
              price_org_code,material_code,selected_shape,row_version,updated_by)
            VALUES(?,?,?,?,?,?,1,?)
            """,unit,itemId,bom.costPeriodMonth(),change.organization(),change.materialCode(),shape,actor.getName());
      } catch(org.springframework.dao.DuplicateKeyException conflict) {
        throw new IllegalArgumentException("形态选择已被其他人更新，请刷新",conflict);
      }
    } else count=jdbc.update("""
        UPDATE lp_quote_material_shape_selection SET selected_shape=?,row_version=row_version+1,updated_by=?,updated_at=CURRENT_TIMESTAMP(6)
        WHERE business_unit_type=? AND oa_form_item_id=? AND period_month=? AND price_org_code=? AND material_code=? AND row_version=?
        """,shape,actor.getName(),unit,itemId,bom.costPeriodMonth(),change.organization(),change.materialCode(),change.expectedVersion());
    if(count!=1) throw new IllegalArgumentException("形态选择已被其他人更新，请刷新");
    workspaces.markItemStale(itemId,bom.costPeriodMonth(),"MATERIAL_SHAPE_CHANGED");
    versions.invalidateProductAfterBomChange(oaNo,itemId,bom.topProductCode(),bom.costPeriodMonth());
    return CommonResult.success(boms.rebuildPreview(oaNo,itemId));
  }
}
