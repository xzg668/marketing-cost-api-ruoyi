package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.util.*;
import org.springframework.stereotype.Component;

/** Scope-bound saved structure facts. This never grants approval or reads another product's draft. */
@Component
public class TechnicalBomSavedSourceQuery {
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataTaskRepository tasks;
  private final TechnicalDataVersionContentCodec codec;
  private final OaMessageCodec json;
  public TechnicalBomSavedSourceQuery(QuoteTechnicalDataRepository repository,TechnicalDataTaskRepository tasks,
      TechnicalDataVersionContentCodec codec,OaMessageCodec json) { this.repository=repository; this.tasks=tasks; this.codec=codec; this.json=json; }
  public record Saved(QuoteTechTask task,QuoteTechProduct product,QuoteTechModule module,QuoteTechDataVersion version,
      TechnicalBomContent bom,String fingerprint) {}
  public Saved read(QuoteBomReadContext context) {
    var product=repository.findActiveProduct(context.oaFormItemId(),context.accountingMonth()).orElse(null);
    if(product==null) return null;
    var module=tasks.findModules(product.getId()).stream().filter(row->"DRAWING_BOM".equals(row.getModuleType())).findFirst().orElse(null);
    if(module==null||module.getCurrentVersionId()==null) return null;
    var version=repository.findVersion(module.getCurrentVersionId()).orElseThrow(()->new IllegalStateException("BOM补录版本不存在"));
    var bom=codec.technicalBom(version); if(bom==null) return null;
    var task=repository.findTask(product.getTaskId()).orElseThrow();
    if(!Objects.equals(version.getProductId(),product.getId())||!Integer.valueOf(1).equals(task.getActiveFlag())
        ||!Objects.equals(task.getOaFormId(),context.oaFormId())||!Objects.equals(task.getOaNo(),context.oaNo())
        ||!Objects.equals(task.getBusinessUnitType(),context.businessUnitType())||!Objects.equals(task.getApplicableOrgCode(),context.priceOrgCode())
        ||bom.scope()==null||!Objects.equals(bom.scope().oaNo(),context.oaNo())
        ||!Objects.equals(bom.scope().businessUnit(),context.businessUnitType())||!Objects.equals(bom.scope().oaFormItemId(),context.oaFormItemId())
        ||!Objects.equals(bom.scope().accountingMonth(),context.accountingMonth())||!Objects.equals(bom.scope().priceOrgCode(),context.priceOrgCode())
        ||!Objects.equals(bom.scope().materialOrgCode(),context.materialOrganizationCode())||!Objects.equals(bom.scope().costingProductCode(),context.productCode()))
      throw new IllegalStateException("完整BOM不属于本次报价产品、月份或组织");
    if(!Set.of("READY","RETURNED","FROZEN","SUBMITTED","APPROVED").contains(module.getModuleStatus())
        ||!"TECH_BOM_VERIFIED".equals(module.getLastValidationCode())||bom.nodes().isEmpty())
      throw new IllegalStateException("完整BOM尚未校验保存，请先处理BOM补录");
    if(bom.nodes().stream().anyMatch(node->node.resolvedMaterialCode()==null||node.categoryCode()==null||node.purchasingUnit()==null
        ||node.quantityPerParent()==null||node.quantityPerParent().signum()<0||node.quantityPerTop()==null||node.quantityPerTop().signum()<0))
      throw new IllegalStateException("完整BOM物料、分类或用量依据不完整");
    return new Saved(task,product,module,version,bom,json.dataFingerprint(bom.inputBasis()));
  }
}
