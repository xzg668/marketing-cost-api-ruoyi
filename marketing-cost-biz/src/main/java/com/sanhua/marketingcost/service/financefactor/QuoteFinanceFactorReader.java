package com.sanhua.marketingcost.service.financefactor;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import com.sanhua.marketingcost.service.pricing.FactorPriceUnit;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 报价预览与正式价格准备共用同一组织、月份及招标条件，禁止回查旧财务表。 */
@Service
public class QuoteFinanceFactorReader {
  private final OaFormMapper forms;
  private final OaFormItemMapper items;
  private final FinanceFactorPriceReader factors;

  public QuoteFinanceFactorReader(
      OaFormMapper forms, OaFormItemMapper items, FinanceFactorPriceReader factors) {
    this.forms = forms;
    this.items = items;
    this.factors = factors;
  }

  public PriceFactorContext read(String oaNo, Long itemId, String month, String businessUnit) {
    var form = forms.selectOne(Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, oaNo));
    var item = itemId == null ? null : items.selectById(itemId);
    if (form == null || item == null || !Objects.equals(form.getId(), item.getOaFormId())) {
      throw new IllegalArgumentException("报价产品与OA单号不匹配");
    }
    if (businessUnit == null || !businessUnit.equals(form.getBusinessUnitType())) {
      throw new IllegalArgumentException("报价与财务基价不属于同一业务单元");
    }
    var organization =
        MaterialOrganization.quoteDataForQuoteProduct(
            form.getProcessCode(),
            form.getOaNo(),
            item.getBusinessUnitType(),
            item.getProductName(),
            item.getSunlModel(),
            item.getMaterialNo());
    Condition condition =
        "220".equals(organization.priceOrgCode())
            ? (Boolean.TRUE.equals(form.getBoardTender()) ? Condition.TENDER : Condition.NON_TENDER)
            : Condition.GENERAL;
    var context =
        factors
            .readEffective(
                new FinanceFactorImportContext(
                    businessUnit, organization.priceOrgCode(), month, null, null),
                condition,
                item.getMaterialNo())
            .financialOnly();
    var copper = context.financial().required(PriceFactorContext.COPPER);
    if (copper.monthlyPriceId() == null
        || context.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_KG, true).signum()
            <= 0) {
      throw new IllegalStateException("已发布财务Cu价格或来源不完整");
    }
    return context.evidenceForFactors(Set.of(PriceFactorContext.COPPER));
  }
}
