package com.sanhua.marketingcost.service.pricing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 固定价 Resolver —— 查 lp_price_fixed_item，按 effective_from 倒序取最新一条。
 *
 * <p>对应 Excel"价格来源 = 固定采购价"。
 */
@Component
public class FixedPriceResolver implements PriceResolver {

  private static final List<String> PURCHASE_FIXED_SOURCE_TYPES =
      List.of("PURCHASE_FIXED", "PURCHASE");
  private static final List<String> SETTLE_FIXED_SOURCE_TYPES =
      List.of("SETTLE_FIXED", "SETTLE");
  private static final String U9_PAYABLE_PROCESS_NO = "U9C-应付单列表";
  private static final String U9_SOURCE_SYSTEM = "U9";

  private final PriceFixedItemMapper priceFixedItemMapper;
  private final SupplierPreferredPriceSelector supplierPreferredPriceSelector;

  public FixedPriceResolver(
      PriceFixedItemMapper priceFixedItemMapper,
      SupplierPreferredPriceSelector supplierPreferredPriceSelector) {
    this.priceFixedItemMapper = priceFixedItemMapper;
    this.supplierPreferredPriceSelector = supplierPreferredPriceSelector;
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
    String code = item.getPartCode();
    if (!StringUtils.hasText(code)) {
      return PriceResolveResult.miss("partCode 为空，无法查固定价");
    }
    FixedSourceKind sourceKind = resolveFixedSourceKind(route);
    LocalDate priceDate = pricingDate(route, context);
    List<PriceFixedItem> rows = selectRows(code, sourceKind, priceDate);
    rows = rows.stream()
        .filter(row -> row.getFixedPrice() != null)
        .toList();
    if (rows.isEmpty()) {
      return PriceResolveResult.miss("lp_price_fixed_item 无记录: " + code);
    }
    if (sourceKind == FixedSourceKind.SETTLE) {
      PriceFixedItem row = rows.get(0);
      return new PriceResolveResult(row.getFixedPrice(), "结算固定价", settleTrace(row));
    }
    rows = preferApprovalRows(rows);
    SupplierPreferredPriceSelection<PriceFixedItem> selected =
        supplierPreferredPriceSelector.select(
            rows,
            firstText(rows, PriceFixedItem::getBusinessUnitType),
            code,
            firstText(rows, PriceFixedItem::getMaterialName),
            firstText(rows, PriceFixedItem::getSpecModel),
            priceDate,
            PriceFixedItem::getSupplierName,
            PriceFixedItem::getSupplierCode);
    PriceFixedItem row = selected.row();
    if (row == null || row.getFixedPrice() == null) {
      return PriceResolveResult.miss("lp_price_fixed_item 无可用价格: " + code);
    }
    return new PriceResolveResult(row.getFixedPrice(), "固定采购价", selected.traceMessage());
  }

  private List<PriceFixedItem> selectRows(
      String code, FixedSourceKind sourceKind, LocalDate priceDate) {
    List<String> sourceTypes = sourceTypes(sourceKind);
    LambdaQueryWrapper<PriceFixedItem> query =
        Wrappers.lambdaQuery(PriceFixedItem.class)
            .eq(PriceFixedItem::getMaterialCode, code)
            // source_type 是固定采购价与结算固定价防串价的核心隔离条件。
            // 同时兼容旧值：PURCHASE -> PURCHASE_FIXED，SETTLE -> SETTLE_FIXED。
            .in(PriceFixedItem::getSourceType, sourceTypes)
            .isNotNull(PriceFixedItem::getFixedPrice);
    if (priceDate != null) {
      query.and(q -> q.le(PriceFixedItem::getEffectiveFrom, priceDate)
          .or()
          .isNull(PriceFixedItem::getEffectiveFrom));
      query.and(q -> q.ge(PriceFixedItem::getEffectiveTo, priceDate)
          .or()
          .isNull(PriceFixedItem::getEffectiveTo));
    }
    if (sourceKind == FixedSourceKind.SETTLE) {
      query.orderByDesc(PriceFixedItem::getEffectiveFrom)
          .orderByDesc(PriceFixedItem::getPricingMonth)
          .orderByDesc(PriceFixedItem::getId);
    } else {
      query.orderByDesc(PriceFixedItem::getEffectiveFrom)
          .orderByDesc(PriceFixedItem::getId);
    }
    return priceFixedItemMapper.selectList(query);
  }

  private List<PriceFixedItem> preferApprovalRows(List<PriceFixedItem> rows) {
    boolean hasApprovalRow = rows.stream().anyMatch(row -> !isU9PayableRow(row));
    if (!hasApprovalRow) {
      return rows;
    }
    return rows.stream()
        .filter(row -> !isU9PayableRow(row))
        .toList();
  }

  private boolean isU9PayableRow(PriceFixedItem row) {
    if (row == null) {
      return false;
    }
    String processNo = row.getProcessNo();
    if (StringUtils.hasText(processNo)
        && U9_PAYABLE_PROCESS_NO.equals(processNo.trim())) {
      return true;
    }
    String sourceSystem = row.getSourceSystem();
    return StringUtils.hasText(sourceSystem)
        && U9_SOURCE_SYSTEM.equalsIgnoreCase(sourceSystem.trim());
  }

  private List<String> sourceTypes(FixedSourceKind sourceKind) {
    return sourceKind == FixedSourceKind.SETTLE
        ? SETTLE_FIXED_SOURCE_TYPES
        : PURCHASE_FIXED_SOURCE_TYPES;
  }

  private FixedSourceKind resolveFixedSourceKind(PriceTypeRoute route) {
    String rawPriceType = route == null ? null : route.rawPriceType();
    if (StringUtils.hasText(rawPriceType)) {
      String text = rawPriceType.trim();
      if ("结算价".equals(text) || "家用结算价".equals(text) || "结算固定价".equals(text)) {
        return FixedSourceKind.SETTLE;
      }
      if ("固定采购价".equals(text) || "采购固定价".equals(text)) {
        return FixedSourceKind.PURCHASE;
      }
    }
    return FixedSourceKind.PURCHASE;
  }

  private String settleTrace(PriceFixedItem row) {
    List<String> parts = new java.util.ArrayList<>();
    if (StringUtils.hasText(row.getSourceSystem())) {
      parts.add("来源系统=" + row.getSourceSystem().trim());
    }
    if (StringUtils.hasText(row.getPricingMonth())) {
      parts.add("结算期间=" + row.getPricingMonth().trim());
    }
    if (row.getPlannedPrice() != null) {
      parts.add("计划价=" + row.getPlannedPrice());
    }
    if (row.getMarkupRatio() != null) {
      parts.add("上浮比例=" + row.getMarkupRatio());
    }
    parts.add("单价字段=最后一列铜价/锌价列");
    return String.join("；", parts);
  }

  private LocalDate pricingDate(PriceTypeRoute route, CostRunContext context) {
    if (context != null && context.getPriceAsOfTime() != null) {
      return context.getPriceAsOfTime().toLocalDate();
    }
    return route == null ? null : route.effectiveFrom();
  }

  private String firstText(List<PriceFixedItem> rows, java.util.function.Function<PriceFixedItem, String> getter) {
    for (PriceFixedItem row : rows) {
      String value = getter.apply(row);
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private enum FixedSourceKind {
    PURCHASE,
    SETTLE
  }
}
