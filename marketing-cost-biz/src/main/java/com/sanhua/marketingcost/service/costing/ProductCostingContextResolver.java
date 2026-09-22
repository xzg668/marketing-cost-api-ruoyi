package com.sanhua.marketingcost.service.costing;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.quotecosting.ProductCostingRequest;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.CostInputRevisionService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 只负责请求归属校验和输入修订读取，不生成BOM、价格或成本。 */
@Component
public class ProductCostingContextResolver {
  private final OaFormMapper formMapper;
  private final OaFormItemMapper itemMapper;
  private final CostInputRevisionService revisionService;

  public ProductCostingContextResolver(
      OaFormMapper formMapper, OaFormItemMapper itemMapper, CostInputRevisionService revisionService) {
    this.formMapper = formMapper;
    this.itemMapper = itemMapper;
    this.revisionService = revisionService;
  }

  public ProductCostingContext resolve(ProductCostingRequest request) {
    if (request == null) throw new IllegalArgumentException("产品核算请求不能为空");
    if (!StringUtils.hasText(request.oaNo())) throw new IllegalArgumentException("OA单号不能为空");
    if (request.oaFormItemId() == null || request.oaFormItemId() <= 0) {
      throw new IllegalArgumentException("报价产品行 ID 必须大于0");
    }
    String period = CostPricingPeriodUtils.requireCurrentPricingMonth(request.periodMonth());
    OaForm form = formMapper.selectOne(Wrappers.lambdaQuery(OaForm.class)
        .eq(OaForm::getOaNo, request.oaNo().trim()).last("LIMIT 1"));
    OaFormItem item = itemMapper.selectById(request.oaFormItemId());
    if (form == null || item == null || !Objects.equals(form.getId(), item.getOaFormId())) {
      throw new QuoteIngestException("报价产品行不存在或不属于当前报价单");
    }
    requireOaCostingInputs(form);
    String productCode = QuoteProductIdentityUtils.resolveCostingCode(item);
    if (!StringUtils.hasText(productCode)) {
      throw new QuoteIngestException("产品料号、三花型号和客户图号至少填写一个");
    }
    return new ProductCostingContext(form, item, productCode, period,
        StringUtils.hasText(request.initiatedBy()) ? request.initiatedBy().trim() : "system", null);
  }

  private void requireOaCostingInputs(OaForm form) {
    if (!"WEAVER_OA".equals(form.getSourceType())) return;
    // I01 允许保存未填全的原单，但不能让下游把缺值当作“否”或使用默认取率维度。
    if (form.getApplyDate() == null) throw new QuoteIngestException("OA需求缺少申请日期，请更正原单后重新推送");
    if (!StringUtils.hasText(form.getSourceBusinessDivision())) {
      throw new QuoteIngestException("OA需求缺少产品事业部，请更正原单后重新推送");
    }
    if (!StringUtils.hasText(form.getApplicantDept()) && !StringUtils.hasText(form.getApplicantOffice())) {
      throw new QuoteIngestException("OA需求缺少申请部门或申请处室，不能确定费率");
    }
    // SC020 表单本身没有这一独立字段，沿用该表既有直销取率规则。
    if (!"FI-SC-020".equals(form.getProcessCode())
        && !java.util.Set.of("是", "否").contains(java.util.Objects.toString(form.getOverseasSalesMode(), ""))) {
      throw new QuoteIngestException("OA需求缺少有效的海外销售标识，不能默认按直销核算，请补充是或否");
    }
  }

  public ProductCostingContext resolveRevision(ProductCostingContext context) {
    return context.withRevision(revisionService.currentRevision(
        context.form(), context.item(), context.periodMonth()));
  }
}
