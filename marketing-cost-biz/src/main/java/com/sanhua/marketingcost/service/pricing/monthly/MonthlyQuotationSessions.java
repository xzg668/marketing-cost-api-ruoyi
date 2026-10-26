package com.sanhua.marketingcost.service.pricing.monthly;

import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.OaFormItemExtraFieldMapper;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Opens the same scoped read session for all existing base-price consumers of one preparation. */
@Component
public final class MonthlyQuotationSessions implements QuotationPriceSessions {
  private final OaFormMapper forms;
  private final OaFormItemMapper items;
  private final MonthlyQuotationPrices prices;
  private final MonthlyPriceScenarios scenarios;
  private final MonthlyPriceStorage storage;
  private final MonthlyPriceDocumentCodec codec;
  private final OaFormItemExtraFieldMapper extraFields;

  public MonthlyQuotationSessions(OaFormMapper forms, OaFormItemMapper items,
      MonthlyQuotationPrices prices, MonthlyPriceScenarios scenarios, MonthlyPriceStorage storage,
      MonthlyPriceDocumentCodec codec, OaFormItemExtraFieldMapper extraFields) {
    this.forms = forms; this.items = items; this.prices = prices; this.scenarios = scenarios;
    this.storage = storage; this.codec = codec;
    this.extraFields = extraFields;
  }

  public QuotationPriceSession open(String oaNo, String businessUnit, String month,
      LocalDateTime priceAsOfTime, PricePrepareScenarioContext scenario) {
    Objects.requireNonNull(scenario, "Explicit quotation price context is required");
    return open(new Request(oaNo, businessUnit, null, month, priceAsOfTime,
        scenario.supplierReviews(), scenario.monthlyPrices()));
  }

  @Override public QuotationPriceSession open(Request request) {
    var reviews = Objects.requireNonNull(request.supplierReviews(), "Quotation supplier context is required");
    var session = Objects.requireNonNull(request.scope(), "Quotation price scope is required").initialize(() -> {
      Long itemId = reviews.oaFormItemId();
      if (itemId == null) itemId = uniqueProductItem(request);
      var item = itemId == null ? null : items.selectById(itemId);
      var form = item == null ? null : forms.selectById(item.getOaFormId());
      if (form == null || !Objects.equals(request.oaNo(), form.getOaNo())
          || !Objects.equals(request.businessUnit(), item.getBusinessUnitType())
          || (request.productCode() != null && !request.productCode().equals(QuoteProductIdentityUtils.resolveCostingCode(item))))
        throw new IllegalArgumentException("月价备价必须绑定当前业务单元的报价产品行");
      var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
      String actor = authentication == null ? "QUOTE_COSTING" : authentication.getName();
      var scopedReviews = reviews.oaFormItemId() == null
          ? new com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext(itemId).forOrganization(reviews.priceOrgCode())
          : reviews;
      var context = new MonthlyMaterialPrices.Context(request.businessUnit(), request.oaNo(), itemId,
          QuoteProductIdentityUtils.resolveCostingCode(item), YearMonth.parse(request.month()),
          request.priceAsOfTime(), actor, scopedReviews.priceOrgCode(), scopedReviews,
          new AnnualUsageInput(item.getAnnualVolume(), item.getAnnualVolume() == null ? null : extraFields.selectAnnualVolumeUnit(itemId)));
      var copper = form.getCopperPrice();
      if (copper != null && copper.signum() <= 0) copper = null;
      BoardTerms board = form.getSus304Price() == null && form.getSus316lPrice() == null && !Boolean.TRUE.equals(form.getBoardTender())
          ? null : new BoardTerms(form.getSus304Price(), form.getSus316lPrice(), Boolean.TRUE.equals(form.getBoardTender()));
      return new MonthlyQuotationSession(context, copper, prices, scenarios, storage, codec, board);
    });
    var scope = session.context();
    if (!scope.oaNo().equals(request.oaNo()) || !scope.businessUnitType().equals(request.businessUnit())
        || !scope.month().equals(YearMonth.parse(request.month())) || !scope.priceAsOfTime().equals(request.priceAsOfTime())
        || (reviews.oaFormItemId() != null && !scope.oaFormItemId().equals(reviews.oaFormItemId()))
        || (request.productCode() != null && !scope.productCode().equals(request.productCode())))
      throw new IllegalArgumentException("备价会话不能跨报价产品、月份或取价时点复用");
    return session;
  }

  private Long uniqueProductItem(Request request) {
    if (request.productCode() == null || request.productCode().isBlank())
      throw new IllegalArgumentException("月价备价缺少报价产品行或产品身份");
    var form = forms.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
        .<com.sanhua.marketingcost.entity.OaForm>lambdaQuery()
        .eq(com.sanhua.marketingcost.entity.OaForm::getOaNo, request.oaNo())
        .eq(com.sanhua.marketingcost.entity.OaForm::getBusinessUnitType, request.businessUnit()));
    if (form == null) throw new IllegalArgumentException("当前业务单元不存在该报价");
    var matches = items.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
        .<com.sanhua.marketingcost.entity.OaFormItem>lambdaQuery()
        .eq(com.sanhua.marketingcost.entity.OaFormItem::getOaFormId, form.getId())
        .eq(com.sanhua.marketingcost.entity.OaFormItem::getBusinessUnitType, request.businessUnit())).stream()
        .filter(item -> request.productCode().equals(QuoteProductIdentityUtils.resolveCostingCode(item))).toList();
    if (matches.size() != 1) throw new IllegalArgumentException("报价产品行不唯一或不存在，请指定 oaFormItemId");
    return matches.getFirst().getId();
  }
}
