package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceReader;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceRequest;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 固定价：按组织选择每家供应商生失效日期最新的版本，再执行通用供货比率规则。
 *
 * <p>对应 Excel"价格来源 = 固定采购价"。
 */
@Component
public class FixedPriceResolver implements PriceResolver {

  private static final List<String> PURCHASE_FIXED_SOURCE_TYPES =
      List.of("PURCHASE_FIXED", "PURCHASE");

  private final TechnicalPriceSourceResolver technicalPrices;
  private final PriceFixedItemMapper priceFixedItemMapper;
  private final SupplierPriceSelectionService supplierPriceSelectionService;
  private final SettlementPriceReader settlementPrices;

  public FixedPriceResolver(
      PriceFixedItemMapper priceFixedItemMapper,
      SupplierPriceSelectionService supplierPriceSelectionService, TechnicalPriceSourceResolver technicalPrices,
      SettlementPriceReader settlementPrices) {
    this.technicalPrices = technicalPrices;
    this.priceFixedItemMapper = priceFixedItemMapper;
    this.supplierPriceSelectionService = supplierPriceSelectionService;
    this.settlementPrices = settlementPrices;
  }

  @Override
  public PriceTypeEnum priceType() {
    return PriceTypeEnum.FIXED;
  }

  @Override
  public PriceResolveResult resolve(String oaNo, CostRunPartItemDto item, PriceTypeRoute route) {
    return resolve(oaNo, item, route, null);
  }

  @Override
  public PriceResolveResult resolve(
      String oaNo, CostRunPartItemDto item, PriceTypeRoute route, CostRunContext context) {
    if (route != null && route.supplemental()) return technicalPrices.resolve(item, route, context);
    if (route != null && route.settlement()) return settlementPrices.resolve(SettlementPriceRequest.from(item, route, context));
    String code = item.getPartCode();
    if (!StringUtils.hasText(code)) {
      return PriceResolveResult.miss("partCode 为空，无法查固定价");
    }
    LocalDate priceDate = context == null || context.getPriceAsOfTime() == null
        ? null : context.getPriceAsOfTime().toLocalDate();
    String orgCode = priceOrgCode(item, context);
    if (!StringUtils.hasText(orgCode) || priceDate == null || context == null
        || !StringUtils.hasText(context.getBusinessUnitType())) {
      return PriceResolveResult.miss("FIXED_PRICE_SCOPE_MISSING", "固定价缺少取价组织、业务单元或核算日期：" + code);
    }
    List<PriceFixedItem> rows = SupplyPriceDatePolicy.preferCurrent(
        selectRows(code, orgCode, context), priceDate,
        PriceFixedItem::getEffectiveFrom, PriceFixedItem::getEffectiveTo);
    var bySupplier = new LinkedHashMap<String, PriceFixedItem>();
    for (PriceFixedItem row : rows) {
      if (row.getFixedPrice() == null) continue;
      String supplier = SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierCode());
      if (!StringUtils.hasText(supplier)) {
        return PriceResolveResult.miss("FIXED_SUPPLIER_CODE_MISSING", "固定价缺供应商代码：" + code);
      }
      PriceFixedItem previous = bySupplier.get(supplier);
      if (previous == null || compareVersion(row, previous) > 0) bySupplier.put(supplier, row);
      else if (compareVersion(row, previous) == 0 && row.getFixedPrice().compareTo(previous.getFixedPrice()) != 0) {
        return PriceResolveResult.miss("FIXED_PRICE_VERSION_CONFLICT", "同一供应商相同有效期存在不同价格：" + code + "/" + supplier);
      }
    }
    if (bySupplier.isEmpty()) return PriceResolveResult.miss("固定价无采购报价：" + code);
    List<SupplierPriceCandidate> candidates = new ArrayList<>();
    bySupplier.forEach((supplier, row) -> candidates.add(new SupplierPriceCandidate(supplier,
        supplier, row.getSupplierName(), row.getFixedPrice(), row.getEffectiveFrom(), row.getEffectiveTo())));
    String businessUnit = context.getBusinessUnitType().trim();
    var reviewContext = context == null ? null : context.getSupplierPriceReviewContext();
    Long itemId = reviewContext == null ? (context == null ? null : context.getOaFormItemId())
        : reviewContext.oaFormItemId();
    SupplierPriceScope scope = new SupplierPriceScope(businessUnit, oaNo, itemId,
        context == null ? null : context.getPricingMonth(), priceDate, orgCode, code, "FIXED");
    var selection = supplierPriceSelectionService.select(scope, candidates);
    if (selection.review() != null && reviewContext != null) reviewContext.add(selection.review());
    if (selection.candidate() == null) {
      return PriceResolveResult.miss(SupplierPriceSelectionService.REVIEW_REQUIRED, selection.message());
    }
    return resolved(bySupplier.get(selection.candidate().key()), "固定采购价",
        selection.message(), priceDate, selection);
  }

  private int compareVersion(PriceFixedItem a, PriceFixedItem b) {
    return Comparator.comparing(PriceFixedItem::getEffectiveFrom,Comparator.nullsFirst(Comparator.naturalOrder()))
        .thenComparing(PriceFixedItem::getEffectiveTo,Comparator.nullsFirst(Comparator.naturalOrder())).compare(a, b);
  }

  private List<PriceFixedItem> selectRows(String code,
      String orgCode, CostRunContext context) {
    LambdaQueryWrapper<PriceFixedItem> query = Wrappers.lambdaQuery(PriceFixedItem.class)
        .eq(PriceFixedItem::getMaterialCode, code)
        .eq(PriceFixedItem::getSourceKind, "PUBLIC")
        .in(PriceFixedItem::getSourceType, PURCHASE_FIXED_SOURCE_TYPES)
        .isNotNull(PriceFixedItem::getFixedPrice);
    if (StringUtils.hasText(orgCode)) query.eq(PriceFixedItem::getOrgCode, orgCode);
    query.eq(PriceFixedItem::getBusinessUnitType, context.getBusinessUnitType().trim());
    query.orderByDesc(PriceFixedItem::getEffectiveFrom)
          .orderByDesc(PriceFixedItem::getEffectiveTo);
    return priceFixedItemMapper.selectList(query.orderByDesc(PriceFixedItem::getImportedAt)
        .orderByDesc(PriceFixedItem::getCreatedAt).orderByDesc(PriceFixedItem::getId));
  }

  private String priceOrgCode(CostRunPartItemDto item, CostRunContext context) {
    if (item != null && StringUtils.hasText(item.getPriceOrgCode())) {
      return item.getPriceOrgCode().trim();
    }
    return context != null && StringUtils.hasText(context.getPriceOrgCode())
        ? context.getPriceOrgCode().trim()
        : null;
  }

  private PriceResolveResult resolved(
      PriceFixedItem row,
      String priceSource,
      String trace,
      LocalDate priceDate,
      SupplierPriceSelectionService.Selection selection) {
    // SRM固定采购价每天按全量快照替换，自增ID不具备稳定业务含义。
    Long evidenceRecordId = isDailySrmFixedPrice(row) ? null : row.getId();
    PriceResolveEvidence evidence = PriceResolveEvidenceFactory.create(
        evidenceRecordId,
        row.getSourceBatchNo(),
        row.getSupplierName(),
        row.getSupplierCode(),
        selection == null || selection.ratio() == null ? null : selection.ratio().getSupplyRatio(),
        selection == null || selection.ratio() == null ? null : selection.ratio().getId(),
        row.getEffectiveFrom(),
        row.getEffectiveTo(),
        priceDate);
    if (selection != null && SupplierPriceSelectionService.FALLBACK_HIGH.equals(selection.rule())) {
      evidence = new PriceResolveEvidence(evidence.sourcePriceRecordId(), evidence.sourceBatchNo(),
          evidence.supplierName(), evidence.supplierCode(), evidence.supplyRatio(), evidence.supplyRatioRecordId(),
          evidence.effectiveFrom(), evidence.effectiveTo(), false,
          "供货比率不足，已确认按最高价取价，请关注成本偏高风险");
    }
    String warning = evidence.warningMessage();
    String remark = StringUtils.hasText(warning)
        ? (StringUtils.hasText(trace) ? trace + "；" : "") + warning
        : trace;
    return PriceResolveResult.hit(row.getFixedPrice(), priceSource, remark, evidenceRecordId, evidence);
  }

  private boolean isDailySrmFixedPrice(PriceFixedItem row) {
    return row != null
        && "PUBLIC".equalsIgnoreCase(row.getSourceKind())
        && "SRM".equalsIgnoreCase(row.getSourceSystem())
        && ("PURCHASE_FIXED".equalsIgnoreCase(row.getSourceType())
            || "PURCHASE".equalsIgnoreCase(row.getSourceType()));
  }

}
