package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.PackagePriceDetailResult;
import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;
import com.sanhua.marketingcost.dto.PackageSnapshotRequest;
import com.sanhua.marketingcost.dto.PackageSnapshotResult;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.entity.PackageComponentGapItem;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentPriceDetail;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.PackageComponentGapItemMapper;
import com.sanhua.marketingcost.mapper.PackageComponentPriceDetailMapper;
import com.sanhua.marketingcost.mapper.PackageComponentPriceMapper;
import com.sanhua.marketingcost.service.MaterialPriceRouterService;
import com.sanhua.marketingcost.service.PackageComponentPriceService;
import com.sanhua.marketingcost.service.PackageComponentSnapshotService;
import com.sanhua.marketingcost.service.pricing.PriceResolveResult;
import com.sanhua.marketingcost.service.pricing.PriceResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PackageComponentPriceServiceImpl implements PackageComponentPriceService {

  private static final String DEFAULT_BOM_SOURCE_TYPE = "U9";
  private static final String DEFAULT_BOM_PURPOSE = "主制造";
  private static final String SNAPSHOT_STATUS_MISSING_STRUCTURE = "MISSING_STRUCTURE";
  private static final String PRICE_STATUS_PRICED = "PRICED";
  private static final String PRICE_STATUS_MISSING_STRUCTURE = "MISSING_STRUCTURE";
  private static final String PRICE_STATUS_MISSING_CHILD_PRICE = "MISSING_CHILD_PRICE";
  private static final String DETAIL_STATUS_PRICED = "PRICED";
  private static final String DETAIL_STATUS_MISSING_ROUTE = "MISSING_ROUTE";
  private static final String DETAIL_STATUS_MISSING_PRICE = "MISSING_PRICE";
  private static final String GAP_STATUS_PENDING_MAINTAIN = "PENDING_MAINTAIN";
  private static final String OA_PUSH_STATUS_NOT_PUSHED = "NOT_PUSHED";
  private static final int CALC_SCALE = 8;

  private final PackageComponentSnapshotService snapshotService;
  private final MaterialPriceRouterService materialPriceRouterService;
  private final PackageComponentPriceMapper priceMapper;
  private final PackageComponentPriceDetailMapper priceDetailMapper;
  private final PackageComponentGapItemMapper gapItemMapper;
  private final Map<PriceTypeEnum, PriceResolver> resolverMap;

  public PackageComponentPriceServiceImpl(
      PackageComponentSnapshotService snapshotService,
      MaterialPriceRouterService materialPriceRouterService,
      PackageComponentPriceMapper priceMapper,
      PackageComponentPriceDetailMapper priceDetailMapper,
      PackageComponentGapItemMapper gapItemMapper,
      List<PriceResolver> priceResolvers) {
    this.snapshotService = snapshotService;
    this.materialPriceRouterService = materialPriceRouterService;
    this.priceMapper = priceMapper;
    this.priceDetailMapper = priceDetailMapper;
    this.gapItemMapper = gapItemMapper;
    Map<PriceTypeEnum, PriceResolver> map = new EnumMap<>(PriceTypeEnum.class);
    for (PriceResolver resolver : priceResolvers) {
      map.put(resolver.priceType(), resolver);
    }
    this.resolverMap = Map.copyOf(map);
  }

  @Override
  @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
  public PackagePriceResult ensurePrice(PackagePriceRequest request) {
    NormalizedRequest req = normalize(request);
    if (!req.forceRefresh) {
      PackageComponentPrice reusable =
          selectReusableCompletePrice(req);
      if (reusable != null) {
        return PackagePriceResult.of(reusable, loadDetails(reusable.getId()), null);
      }
    }

    PackageSnapshotResult snapshotResult = snapshotService.ensureSnapshot(toSnapshotRequest(req));
    PackageComponentSnapshot snapshot = snapshotResult.getSnapshot();
    PackageComponentPrice price = ensurePriceRow(req, snapshot);
    if (!req.forceRefresh && isReusableCompletePrice(price)) {
      return PackagePriceResult.of(price, loadDetails(price.getId()), snapshotResult);
    }
    deleteDetails(price.getId());

    if (snapshot == null || SNAPSHOT_STATUS_MISSING_STRUCTURE.equals(snapshot.getStatus())) {
      updatePrice(price, snapshot, PRICE_STATUS_MISSING_STRUCTURE, null, false);
      PackagePriceResult result = PackagePriceResult.of(price, List.of(), snapshotResult);
      result.getWarnings().add("包装组件缺结构，无法生成子件价格");
      result.getWarnings().addAll(snapshotResult.getWarnings());
      return result;
    }

    List<PackageComponentPriceDetail> details = new ArrayList<>();
    BigDecimal total = BigDecimal.ZERO;
    boolean complete = true;
    for (PackageComponentSnapshotDetail snapshotDetail : snapshotResult.getDetails()) {
      ResolvedChild child = resolveChild(req, price, snapshot, snapshotDetail);
      priceDetailMapper.insert(child.detail());
      details.add(child.detail());
      if (DETAIL_STATUS_PRICED.equals(child.detail().getPriceStatus())) {
        total = total.add(child.detail().getChildAmount());
      } else {
        complete = false;
        upsertGap(buildGap(req, snapshot, child.detail()));
      }
    }

    if (complete) {
      updatePrice(price, snapshot, PRICE_STATUS_PRICED, applyPackageParentBaseQty(total, snapshot), true);
    } else {
      updatePrice(price, snapshot, PRICE_STATUS_MISSING_CHILD_PRICE, null, false);
    }

    PackagePriceResult result = PackagePriceResult.of(price, details, snapshotResult);
    if (!complete) {
      result.getWarnings().add("包装组件存在子件缺价，当前阶段只记录不阻断");
    }
    return result;
  }

  @Override
  public PackagePriceDetailResult getPriceDetail(Long priceId) {
    PackagePriceDetailResult result = new PackagePriceDetailResult();
    if (priceId == null) {
      return result;
    }
    PackageComponentPrice price = priceMapper.selectById(priceId);
    result.setPrice(price);
    if (price != null) {
      result.setDetails(
          priceDetailMapper.selectList(
              Wrappers.<PackageComponentPriceDetail>lambdaQuery()
                  .eq(PackageComponentPriceDetail::getPriceId, priceId)
                  .orderByAsc(PackageComponentPriceDetail::getLineNo)));
    }
    return result;
  }

  private ResolvedChild resolveChild(
      NormalizedRequest req,
      PackageComponentPrice price,
      PackageComponentSnapshot snapshot,
      PackageComponentSnapshotDetail snapshotDetail) {
    PackageComponentPriceDetail detail = baseDetail(price, snapshotDetail);
    String childCode = trimToNull(snapshotDetail.getChildMaterialCode());
    if (childCode == null) {
      markMissing(detail, DETAIL_STATUS_MISSING_ROUTE, null, null, "子件料号为空");
      return new ResolvedChild(detail);
    }

    List<PriceTypeRoute> candidates =
        materialPriceRouterService.listCandidates(childCode, req.periodMonth, req.quoteDate);
    if (candidates.isEmpty()) {
      PriceResolveResult noRoute = PriceResolveResult.noRoute(childCode);
      markMissing(detail, DETAIL_STATUS_MISSING_ROUTE, null, null, noRoute.remark());
      detail.setPriceSource(noRoute.priceSource());
      return new ResolvedChild(detail);
    }

    List<String> attempted = new ArrayList<>();
    String lastMissReason = null;
    PriceTypeRoute lastRoute = null;
    for (PriceTypeRoute route : candidates) {
      lastRoute = route;
      applyRoute(detail, route);
      PriceResolver resolver = route.priceType() == null ? null : resolverMap.get(route.priceType());
      if (resolver == null) {
        attempted.add(routeLabel(route) + "(无Resolver)");
        lastMissReason = "价格类型无 Resolver(price_type=" + routeLabel(route) + ")";
        continue;
      }
      attempted.add(routeLabel(route));
      CostRunPartItemDto item = toCostRunItem(req, snapshot, snapshotDetail);
      PriceResolveResult resolved = resolver.resolve(req.oaNo, item, route, priceContext(req));
      if (resolved.unitPrice() != null) {
        return new ResolvedChild(applyHit(detail, route, resolved));
      }
      if (StringUtils.hasText(resolved.remark())) {
        lastMissReason = resolved.remark();
      }
    }

    String reason = "路由=" + attempted + " 但未取到有效价格"
        + (lastMissReason == null ? "" : ": " + lastMissReason);
    markMissing(detail, DETAIL_STATUS_MISSING_PRICE, lastRoute, PriceResolveResult.SOURCE_ERROR, reason);
    return new ResolvedChild(detail);
  }

  private PackageComponentPriceDetail applyHit(
      PackageComponentPriceDetail detail, PriceTypeRoute route, PriceResolveResult resolved) {
    applyRoute(detail, route);
    detail.setChildUnitPrice(resolved.unitPrice());
    if (detail.getQtyPerParent() == null) {
      markMissing(detail, DETAIL_STATUS_MISSING_PRICE, route, resolved.priceSource(), "qty_per_parent 为空，无法计算子件金额");
      return detail;
    }
    BigDecimal effectiveQty = applyChildParentBaseQty(detail);
    if (effectiveQty == null) {
      markMissing(detail, DETAIL_STATUS_MISSING_PRICE, route, resolved.priceSource(), "子件母件底数为 0，无法计算子件金额");
      return detail;
    }
    detail.setChildAmount(effectiveQty.multiply(resolved.unitPrice()));
    detail.setPriceSource(resolved.priceSource());
    detail.setPriceStatus(DETAIL_STATUS_PRICED);
    detail.setMissingReason(null);
    return detail;
  }

  private BigDecimal applyChildParentBaseQty(PackageComponentPriceDetail detail) {
    BigDecimal qty = detail.getQtyPerParent();
    BigDecimal baseQty = detail.getChildParentBaseQty();
    if (qty == null || baseQty == null) {
      return qty;
    }
    if (BigDecimal.ZERO.compareTo(baseQty) == 0) {
      return null;
    }
    return qty.divide(baseQty, CALC_SCALE, RoundingMode.HALF_UP);
  }

  private void markMissing(
      PackageComponentPriceDetail detail,
      String status,
      PriceTypeRoute route,
      String priceSource,
      String reason) {
    if (route != null) {
      applyRoute(detail, route);
    }
    detail.setChildUnitPrice(null);
    detail.setChildAmount(null);
    detail.setPriceSource(priceSource);
    detail.setPriceStatus(status);
    detail.setMissingReason(reason);
  }

  private void applyRoute(PackageComponentPriceDetail detail, PriceTypeRoute route) {
    if (route == null) {
      return;
    }
    detail.setPriceType(route.priceType() == null ? null : route.priceType().getDbText());
    detail.setSourcePriceTypeText(priceTypeText(route));
  }

  private PackageComponentPriceDetail baseDetail(
      PackageComponentPrice price, PackageComponentSnapshotDetail snapshotDetail) {
    PackageComponentPriceDetail detail = new PackageComponentPriceDetail();
    detail.setPriceId(price.getId());
    detail.setSnapshotDetailId(snapshotDetail.getId());
    detail.setPackageMaterialCode(price.getPackageMaterialCode());
    detail.setPeriodMonth(price.getPeriodMonth());
    detail.setLineNo(snapshotDetail.getLineNo());
    detail.setChildMaterialCode(snapshotDetail.getChildMaterialCode());
    detail.setChildMaterialName(snapshotDetail.getChildMaterialName());
    detail.setChildMaterialSpec(snapshotDetail.getChildMaterialSpec());
    detail.setQtyPerParent(snapshotDetail.getQtyPerParent());
    detail.setChildParentBaseQty(snapshotDetail.getChildParentBaseQty());
    return detail;
  }

  private CostRunPartItemDto toCostRunItem(
      NormalizedRequest req,
      PackageComponentSnapshot snapshot,
      PackageComponentSnapshotDetail snapshotDetail) {
    CostRunPartItemDto item = new CostRunPartItemDto();
    item.setOaNo(req.oaNo);
    item.setProductCode(snapshot.getSourceTopProductCode());
    item.setPartCode(snapshotDetail.getChildMaterialCode());
    item.setPartName(snapshotDetail.getChildMaterialName());
    item.setPartQty(snapshotDetail.getQtyPerParent());
    item.setPartDrawingNo(snapshotDetail.getChildMaterialSpec());
    item.setShapeAttr(snapshotDetail.getChildShapeAttr());
    item.setMaterialShape(snapshotDetail.getChildShapeAttr());
    return item;
  }

  private CostRunContext priceContext(NormalizedRequest req) {
    return CostRunContext.quote(
        req.oaNo,
        null,
        req.topProductCode,
        null,
        null,
        null,
        req.periodMonth,
        req.priceAsOfTime,
        null);
  }

  private PackageComponentPrice ensurePriceRow(NormalizedRequest req, PackageComponentSnapshot snapshot) {
    PackageComponentPrice existing =
        selectByPeriodPackageAndTop(req);
    if (existing != null) {
      existing.setSnapshotId(snapshot == null ? null : snapshot.getId());
      existing.setPackageMaterialName(snapshot == null ? existing.getPackageMaterialName() : snapshot.getPackageMaterialName());
      applyPackageUsage(existing, snapshot);
      applySourceContext(existing, snapshot, req);
      if (!StringUtils.hasText(existing.getOaNo())) {
        existing.setOaNo(blankIfNull(req.oaNo));
      }
      existing.setCalcBatchId(req.calcBatchId);
      existing.setPriceAsOfTime(req.priceAsOfTime);
      existing.setGeneratedAt(LocalDateTime.now());
      priceMapper.updateById(existing);
      return existing;
    }

    PackageComponentPrice price = new PackageComponentPrice();
    price.setSnapshotId(snapshot == null ? null : snapshot.getId());
    price.setPackageMaterialCode(req.packageMaterialCode);
    price.setPackageMaterialName(snapshot == null ? null : snapshot.getPackageMaterialName());
    price.setPeriodMonth(req.periodMonth);
    price.setOaNo(blankIfNull(req.oaNo));
    applySourceContext(price, snapshot, req);
    price.setPriceAsOfTime(req.priceAsOfTime);
    price.setTotalPrice(null);
    applyPackageUsage(price, snapshot);
    price.setPriceStatus(PRICE_STATUS_MISSING_CHILD_PRICE);
    price.setPriceComplete(false);
    price.setGeneratedAt(LocalDateTime.now());
    price.setCalcBatchId(req.calcBatchId);
    try {
      priceMapper.insert(price);
      return price;
    } catch (DuplicateKeyException ex) {
      PackageComponentPrice concurrent =
          selectByPeriodPackageAndTop(req);
      if (concurrent != null) {
        if (!req.forceRefresh && isReusableCompletePrice(concurrent)) {
          return concurrent;
        }
        concurrent.setSnapshotId(snapshot == null ? null : snapshot.getId());
        concurrent.setPackageMaterialName(snapshot == null ? concurrent.getPackageMaterialName() : snapshot.getPackageMaterialName());
        applyPackageUsage(concurrent, snapshot);
        applySourceContext(concurrent, snapshot, req);
        if (!StringUtils.hasText(concurrent.getOaNo())) {
          concurrent.setOaNo(blankIfNull(req.oaNo));
        }
        concurrent.setCalcBatchId(req.calcBatchId);
        concurrent.setPriceAsOfTime(req.priceAsOfTime);
        concurrent.setGeneratedAt(LocalDateTime.now());
        priceMapper.updateById(concurrent);
        return concurrent;
      }
      throw ex;
    }
  }

  private void updatePrice(
      PackageComponentPrice price,
      PackageComponentSnapshot snapshot,
      String status,
      BigDecimal totalPrice,
      boolean complete) {
    price.setSnapshotId(snapshot == null ? null : snapshot.getId());
    price.setPackageMaterialName(snapshot == null ? price.getPackageMaterialName() : snapshot.getPackageMaterialName());
    applyPackageUsage(price, snapshot);
    applySourceContext(price, snapshot, null);
    price.setPriceStatus(status);
    price.setPriceComplete(complete);
    price.setTotalPrice(totalPrice);
    price.setGeneratedAt(LocalDateTime.now());
    priceMapper.updateById(price);
  }

  private void applyPackageUsage(PackageComponentPrice price, PackageComponentSnapshot snapshot) {
    if (price == null || snapshot == null) {
      return;
    }
    price.setPackageQtyPerParent(snapshot.getPackageQtyPerParent());
    price.setPackageQtyPerTop(snapshot.getPackageQtyPerTop());
    price.setPackageParentBaseQty(snapshot.getPackageParentBaseQty());
  }

  private void applySourceContext(
      PackageComponentPrice price, PackageComponentSnapshot snapshot, NormalizedRequest req) {
    if (price == null) {
      return;
    }
    if (snapshot != null) {
      price.setSourceTopProductCode(snapshot.getSourceTopProductCode());
      price.setSourceBomPurpose(snapshot.getSourceBomPurpose());
      price.setSourceBomSourceType(snapshot.getSourceBomSourceType());
      return;
    }
    if (req != null) {
      price.setSourceTopProductCode(req.topProductCode);
      price.setSourceBomPurpose(req.bomPurpose);
      price.setSourceBomSourceType(req.sourceType);
    }
  }

  private BigDecimal applyPackageParentBaseQty(BigDecimal total, PackageComponentSnapshot snapshot) {
    if (total == null || snapshot == null || snapshot.getPackageParentBaseQty() == null) {
      return total;
    }
    BigDecimal baseQty = snapshot.getPackageParentBaseQty();
    if (BigDecimal.ZERO.compareTo(baseQty) == 0) {
      return total;
    }
    return total.divide(baseQty, CALC_SCALE, RoundingMode.HALF_UP);
  }

  private PackageComponentGapItem buildGap(
      NormalizedRequest req,
      PackageComponentSnapshot snapshot,
      PackageComponentPriceDetail detail) {
    PackageComponentGapItem gap = new PackageComponentGapItem();
    gap.setPeriodMonth(req.periodMonth);
    gap.setQuoteNo(req.quoteNo);
    gap.setOaNo(req.oaNo);
    gap.setTopProductCode(req.topProductCode);
    gap.setPackageMaterialCode(req.packageMaterialCode);
    gap.setPackageMaterialName(snapshot.getPackageMaterialName());
    gap.setLineNo(detail.getLineNo());
    gap.setChildMaterialCode(detail.getChildMaterialCode());
    gap.setChildMaterialName(detail.getChildMaterialName());
    gap.setGapType(detail.getPriceStatus());
    gap.setPriceType(detail.getSourcePriceTypeText());
    gap.setMissingMaterialCode(detail.getChildMaterialCode());
    gap.setMissingReason(detail.getMissingReason());
    gap.setStatus(GAP_STATUS_PENDING_MAINTAIN);
    gap.setOaPushStatus(OA_PUSH_STATUS_NOT_PUSHED);
    return gap;
  }

  private void upsertGap(PackageComponentGapItem gap) {
    Long existingId = findExistingGapId(gap);
    if (existingId == null) {
      gapItemMapper.insert(gap);
      return;
    }
    gap.setId(existingId);
    gapItemMapper.updateById(gap);
  }

  private Long findExistingGapId(PackageComponentGapItem gap) {
    if (gap == null
        || !StringUtils.hasText(gap.getPeriodMonth())
        || !StringUtils.hasText(gap.getPackageMaterialCode())
        || !StringUtils.hasText(gap.getGapType())) {
      return null;
    }
    var query = Wrappers.lambdaQuery(PackageComponentGapItem.class)
        .eq(PackageComponentGapItem::getPeriodMonth, trimToNull(gap.getPeriodMonth()))
        .eq(PackageComponentGapItem::getPackageMaterialCode, trimToNull(gap.getPackageMaterialCode()))
        .eq(PackageComponentGapItem::getGapType, trimToNull(gap.getGapType()))
        .orderByDesc(PackageComponentGapItem::getId)
        .last("LIMIT 1");
    eqNullable(query, PackageComponentGapItem::getQuoteNo, gap.getQuoteNo());
    eqNullable(query, PackageComponentGapItem::getOaNo, gap.getOaNo());
    eqNullable(query, PackageComponentGapItem::getTopProductCode, gap.getTopProductCode());
    eqNullable(query, PackageComponentGapItem::getLineNo, gap.getLineNo());
    eqNullable(query, PackageComponentGapItem::getChildMaterialCode, gap.getChildMaterialCode());
    eqNullable(query, PackageComponentGapItem::getMissingMaterialCode, gap.getMissingMaterialCode());
    List<PackageComponentGapItem> existingRows = gapItemMapper.selectList(query);
    if (existingRows == null || existingRows.isEmpty()) {
      return null;
    }
    return existingRows.get(0).getId();
  }

  private <T> void eqNullable(
      com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PackageComponentGapItem> query,
      com.baomidou.mybatisplus.core.toolkit.support.SFunction<PackageComponentGapItem, T> column,
      T value) {
    if (value instanceof String text) {
      String trimmed = trimToNull(text);
      if (trimmed == null) {
        query.isNull(column);
      } else {
        query.eq(column, trimmed);
      }
      return;
    }
    if (value == null) {
      query.isNull(column);
    } else {
      query.eq(column, value);
    }
  }

  private void deleteDetails(Long priceId) {
    if (priceId == null) {
      return;
    }
    priceDetailMapper.delete(
        Wrappers.<PackageComponentPriceDetail>lambdaQuery()
            .eq(PackageComponentPriceDetail::getPriceId, priceId));
  }

  private List<PackageComponentPriceDetail> loadDetails(Long priceId) {
    if (priceId == null) {
      return List.of();
    }
    return priceDetailMapper.selectList(
        Wrappers.<PackageComponentPriceDetail>lambdaQuery()
            .eq(PackageComponentPriceDetail::getPriceId, priceId)
            .orderByAsc(PackageComponentPriceDetail::getLineNo));
  }

  private PackageComponentPrice selectReusableCompletePrice(NormalizedRequest req) {
    PackageComponentPrice existing = selectByPeriodPackageAndTop(req);
    if (existing == null) {
      return null;
    }
    return isReusableCompletePrice(existing) ? existing : null;
  }

  private boolean isReusableCompletePrice(PackageComponentPrice existing) {
    if (existing == null) {
      return false;
    }
    boolean complete = Boolean.TRUE.equals(existing.getPriceComplete());
    boolean priced = PRICE_STATUS_PRICED.equals(existing.getPriceStatus());
    return complete && priced && existing.getTotalPrice() != null;
  }

  private PackageComponentPrice selectByPeriodPackageAndTop(NormalizedRequest req) {
    var query = Wrappers.<PackageComponentPrice>lambdaQuery()
        .eq(PackageComponentPrice::getPeriodMonth, req.periodMonth)
        .eq(PackageComponentPrice::getPackageMaterialCode, req.packageMaterialCode)
        .eq(PackageComponentPrice::getSourceTopProductCode, req.topProductCode);
    if (req.explicitPriceAsOfTime) {
      // 月度调价重试只复用同一 price_as_of_time 的包装价，不能读到后续刷新出的当前价。
      query.eq(PackageComponentPrice::getPriceAsOfTime, req.priceAsOfTime);
    }
    List<PackageComponentPrice> rows = priceMapper.selectList(
        query.orderByDesc(PackageComponentPrice::getGeneratedAt)
            .orderByDesc(PackageComponentPrice::getId)
            .last("LIMIT 1"));
    return rows == null || rows.isEmpty() ? null : rows.get(0);
  }

  private PackageSnapshotRequest toSnapshotRequest(NormalizedRequest req) {
    PackageSnapshotRequest snapshotRequest = new PackageSnapshotRequest();
    snapshotRequest.setPackageMaterialCode(req.packageMaterialCode);
    snapshotRequest.setPeriodMonth(req.periodMonth);
    snapshotRequest.setQuoteNo(req.quoteNo);
    snapshotRequest.setOaNo(req.oaNo);
    snapshotRequest.setTopProductCode(req.topProductCode);
    snapshotRequest.setBomPurpose(req.bomPurpose);
    snapshotRequest.setSourceType(req.sourceType);
    snapshotRequest.setAsOfDate(req.quoteDate);
    return snapshotRequest;
  }

  private NormalizedRequest normalize(PackagePriceRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request 必填");
    }
    String packageMaterialCode = trimToNull(request.getPackageMaterialCode());
    if (packageMaterialCode == null) {
      throw new IllegalArgumentException("packageMaterialCode 必填");
    }
    String topProductCode = trimToNull(request.getTopProductCode());
    if (topProductCode == null) {
      throw new IllegalArgumentException("topProductCode 必填：包装组件价格必须指定来源顶层产品");
    }
    String periodMonth = trimToNull(request.getPeriodMonth());
    if (periodMonth == null) {
      periodMonth = YearMonth.now().toString();
    }
    boolean explicitPriceAsOfTime = request.getPriceAsOfTime() != null;
    LocalDate quoteDate = request.getAsOfDate() == null
        ? (explicitPriceAsOfTime ? request.getPriceAsOfTime().toLocalDate() : LocalDate.now())
        : request.getAsOfDate();
    LocalDateTime priceAsOfTime = explicitPriceAsOfTime
        ? request.getPriceAsOfTime()
        : quoteDate.atStartOfDay();
    return new NormalizedRequest(
        packageMaterialCode,
        periodMonth,
        trimToNull(request.getQuoteNo()),
        trimToNull(request.getOaNo()),
        topProductCode,
        DEFAULT_BOM_PURPOSE,
        trimToNull(request.getSourceType()) == null ? DEFAULT_BOM_SOURCE_TYPE : trimToNull(request.getSourceType()),
        request.getAsOfDate(),
        quoteDate,
        priceAsOfTime,
        explicitPriceAsOfTime,
        trimToNull(request.getCalcBatchId()),
        request.isForceRefresh());
  }

  private String routeLabel(PriceTypeRoute route) {
    String text = priceTypeText(route);
    return text == null ? "UNKNOWN" : text;
  }

  private String priceTypeText(PriceTypeRoute route) {
    if (route != null && StringUtils.hasText(route.rawPriceType())) {
      return route.rawPriceType().trim();
    }
    return route == null || route.priceType() == null ? null : route.priceType().getDbText();
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String blankIfNull(String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? "" : trimmed;
  }

  private record ResolvedChild(PackageComponentPriceDetail detail) {}

  private record NormalizedRequest(
      String packageMaterialCode,
      String periodMonth,
      String quoteNo,
      String oaNo,
      String topProductCode,
      String bomPurpose,
      String sourceType,
      LocalDate asOfDate,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      boolean explicitPriceAsOfTime,
      String calcBatchId,
      boolean forceRefresh) {}
}
