package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Resolve the target from its OA row, never from the uploaded/reference product code. */
@Component
public class TechnicalBomContextQuery {
  private final OaFormItemMapper items;
  private final OaFormMapper forms;
  private final QuoteBomContextResolver organizations;
  public TechnicalBomContextQuery(OaFormItemMapper items, OaFormMapper forms, QuoteBomContextResolver organizations) {
    this.items = items; this.forms = forms; this.organizations = organizations;
  }
  public record Target(QuoteBomReadContext context, TechnicalBomRows.Product product) {}
  public Target read(QuoteTechTask task, QuoteTechProduct product) {
    var item = items.selectById(product.getOaFormItemId());
    var form = item == null ? null : forms.selectById(item.getOaFormId());
    if (form == null || !Objects.equals(form.getId(), task.getOaFormId())
        || !Objects.equals(form.getOaNo(), task.getOaNo())
        || !Objects.equals(form.getBusinessUnitType(), task.getBusinessUnitType()))
      throw new IllegalArgumentException("BOM补录不属于当前报价产品");
    YearMonth.parse(product.getAccountingMonth());
    var org = organizations.resolveOrganization(form, item);
    if (!Objects.equals(org.priceOrgCode(), task.getApplicableOrgCode()))
      throw new IllegalArgumentException("当前产品组织与补录任务不一致，请刷新核实");
    return target(form, item, product.getAccountingMonth(), org);
  }

  /** 管理员试查参考BOM使用报价行身份，不为查看创建补录任务或草稿。 */
  public Target readQuote(Long itemId, String accountingMonth, TechnicalDataActor actor) {
    if (actor == null || actor.userId() == null || !actor.admin() || actor.oaSession())
      throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, "仅管理员可试查BOM填写内容");
    try { YearMonth.parse(accountingMonth); }
    catch (RuntimeException error) { throw new IllegalArgumentException("核算月份应为 YYYY-MM"); }
    var item = itemId == null ? null : items.selectById(itemId);
    var form = item == null ? null : forms.selectById(item.getOaFormId());
    if (form == null || Integer.valueOf(1).equals(item.getDeleted()) || Integer.valueOf(1).equals(form.getDeleted()))
      throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.PRODUCT_NOT_FOUND, "报价产品不存在");
    String unit = BusinessUnitContext.getCurrentBusinessUnitType();
    if (!actor.canAccessForm(form.getId()) || unit != null && !unit.isBlank() && !Objects.equals(unit, form.getBusinessUnitType()))
      throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.FORBIDDEN, "无权查看当前业务单元的报价产品");
    return target(form, item, accountingMonth, organizations.resolveOrganization(form, item));
  }

  private Target target(OaForm form, OaFormItem item, String accountingMonth, QuoteDataOrganization org) {
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var context = new QuoteBomReadContext(form.getId(), item.getId(), form.getOaNo(), accountingMonth,
        form.getBusinessUnitType(), QuoteProductIdentityUtils.resolveCostingCode(item), item.getProductName(), item.getSpec(),
        item.getSunlModel(), org.priceOrgCode(), org.materialOrganizationCode(), now.toLocalDate(), now);
    return new Target(context, new TechnicalBomRows.Product(item.getMaterialNo(), item.getProductName(), item.getSunlModel(), item.getSpec()));
  }
}
