package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.enums.QuoteProductType;
import com.sanhua.marketingcost.service.QuoteEffectiveBomCostingService;
import com.sanhua.marketingcost.service.QuotePricingPeriod;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.QuoteProductTypeResolveService;
import com.sanhua.marketingcost.service.quotebom.*;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;

@Service
public class BareQuoteMaterialPreparation {
  private final QuoteProductTypeResolveService types;
  private final CurrentU9BomGateway u9;
  private final QuoteProductBomPreparationService preparations;
  private final QuoteEffectiveBomCostingService costing;
  private final QuotePricingPeriod periods;

  public BareQuoteMaterialPreparation(QuoteProductTypeResolveService types, CurrentU9BomGateway u9,
      QuoteProductBomPreparationService preparations, QuoteEffectiveBomCostingService costing, QuotePricingPeriod periods) {
    this.types = types;
    this.u9 = u9;
    this.preparations = preparations;
    this.costing = costing;
    this.periods = periods;
  }

  /** 调用方已锁本行及本月工作区。仅恢复从未成功生成报价物料的裸品。 */
  boolean recover(OaForm form, OaFormItem item, QuoteCostingWorkspace workspace, TechnicalDataProductSource source) {
    if (!"WAIT_BOM".equals(workspace.getWorkspaceStatus()) || workspace.getCurrentBomBuildBatchId() != null
        || workspace.getCurrentCostVersionId() != null || item.getMaterialNo() == null || item.getMaterialNo().isBlank()) return false;
    String month = workspace.getPeriodMonth();
    if (!month.equals(periods.currentMonth(form.getBusinessUnitType()))) return false;
    String code = item.getMaterialNo().trim();
    var type = types.resolve(code, source.materialOrganizationCode());
    if (type.productType() != QuoteProductType.BARE) return false;
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var context = new QuoteBomReadContext(form.getId(), item.getId(), form.getOaNo(), month, form.getBusinessUnitType(),
        code, item.getProductName(), item.getSpec(), item.getSunlModel(), source.applicableOrgCode(),
        source.materialOrganizationCode(), now.toLocalDate(), now);
    var current = u9.read(context);
    if (current == null || current.status() != CurrentU9BomResult.Status.AVAILABLE) return false;
    var preparation = preparations.prepareByOaFormItem(item.getId(), now.toLocalDate(), month);
    if (!preparation.ready()) return false;
    costing.prepareCurrent(form.getOaNo(), item.getId());
    return true;
  }
}
