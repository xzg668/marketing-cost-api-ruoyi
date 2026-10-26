package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.PackagePriceDetailResult;
import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;
import com.sanhua.marketingcost.dto.PackageSnapshotRequest;
import com.sanhua.marketingcost.dto.PackageSnapshotResult;
import com.sanhua.marketingcost.entity.PackageComponentGapItem;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentPriceDetail;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.entity.PackageComponentSnapshotDetail;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.PackageComponentGapItemMapper;
import com.sanhua.marketingcost.mapper.PackageComponentPriceDetailMapper;
import com.sanhua.marketingcost.mapper.PackageComponentPriceMapper;
import com.sanhua.marketingcost.service.PackageComponentPriceService;
import com.sanhua.marketingcost.service.PackageComponentSnapshotService;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
  private static final String DETAIL_STATUS_MISSING_PRICE = "MISSING_PRICE";
  private static final String GAP_STATUS_PENDING_MAINTAIN = "PENDING_MAINTAIN";
  private static final String OA_PUSH_STATUS_NOT_PUSHED = "NOT_PUSHED";
  private static final int CALC_SCALE = 8;

  private final PackageComponentSnapshotService snapshotService;
  private final PackageComponentPriceMapper priceMapper;
  private final PackageComponentPriceDetailMapper priceDetailMapper;
  private final PackageComponentGapItemMapper gapItemMapper;
  private final QuotationPriceSessions monthlySessions;
  private final com.sanhua.marketingcost.mapper.MaterialMasterRawMapper materials;
  private final MonthlyPriceDocumentCodec monthlyCodec;

  @org.springframework.beans.factory.annotation.Autowired
  public PackageComponentPriceServiceImpl(PackageComponentSnapshotService snapshotService,
      PackageComponentPriceMapper priceMapper, PackageComponentPriceDetailMapper priceDetailMapper,
      PackageComponentGapItemMapper gapItemMapper,
      com.sanhua.marketingcost.mapper.MaterialMasterRawMapper materials,
      QuotationPriceSessions monthlySessions, MonthlyPriceDocumentCodec monthlyCodec) {
    this.snapshotService = snapshotService;
    this.priceMapper = priceMapper; this.priceDetailMapper = priceDetailMapper; this.gapItemMapper = gapItemMapper;
    this.materials = materials; this.monthlySessions = monthlySessions; this.monthlyCodec = monthlyCodec;
  }

  @Override
  @Transactional(readOnly = true)
  public PackagePriceResult calculatePrice(PackagePriceRequest request) {
    NormalizedRequest req = normalize(request);
    PackageSnapshotResult snapshotResult =
        snapshotService.previewSnapshot(toSnapshotRequest(req));
    PackageComponentSnapshot snapshot = snapshotResult.getSnapshot();
    PackageComponentPrice price = transientPrice(req, snapshot);

    if (snapshot == null || SNAPSHOT_STATUS_MISSING_STRUCTURE.equals(snapshot.getStatus())) {
      applyCalculatedPrice(price, snapshot, PRICE_STATUS_MISSING_STRUCTURE, null, false);
      PackagePriceResult result = PackagePriceResult.of(price, List.of(), snapshotResult);
      result.getWarnings().add("包装组件缺结构，无法生成子件价格");
      if (snapshotResult != null && snapshotResult.getWarnings() != null) {
        result.getWarnings().addAll(snapshotResult.getWarnings());
      }
      return result;
    }

    List<PackageComponentPriceDetail> details = resolveChildren(req, price, snapshotResult.getDetails());
    BigDecimal total = BigDecimal.ZERO;
    boolean complete = true;
    for (PackageComponentPriceDetail detail : details) {
      if (DETAIL_STATUS_PRICED.equals(detail.getPriceStatus())) {
        total = total.add(detail.getChildAmount());
      } else if (!PackageComponentPriceDetail.HOUSEHOLD_EXCLUDED.equals(detail.getPriceStatus())) {
        complete = false;
      }
    }
    applyCalculatedPrice(
        price,
        snapshot,
        complete ? PRICE_STATUS_PRICED : PRICE_STATUS_MISSING_CHILD_PRICE,
        complete ? applyPackageParentBaseQty(total, snapshot) : null,
        complete);
    PackagePriceResult result = PackagePriceResult.of(price, details, snapshotResult);
    if (!complete) {
      result.getWarnings().add("包装组件存在子件缺价，当前阶段只记录不阻断");
    }
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
  public PackagePriceResult ensurePrice(PackagePriceRequest request) {
    NormalizedRequest req = normalize(request);
    // 月价行无法代表不同OA的锁价输入和最新财务顺序；每次准备重新取子件价格。
    PackageSnapshotResult snapshotResult = req.sourceParentPath == null
        ? snapshotService.ensureSnapshot(toSnapshotRequest(req))
        : snapshotService.previewSnapshot(toSnapshotRequest(req));
    PackageComponentSnapshot snapshot = snapshotResult.getSnapshot();
    StoredPrice stored = ensurePriceRow(req, snapshot);
    PackageComponentPrice price = stored.price();
    // 新主记录尚无明细。外层核算事务可能是 REPEATABLE READ，对空范围 DELETE
    // 会持有间隙锁，使不同报价的首次包装明细插入互相死锁。
    if (!stored.created()) deleteDetails(price.getId());

    if (snapshot == null || SNAPSHOT_STATUS_MISSING_STRUCTURE.equals(snapshot.getStatus())) {
      updatePrice(price, snapshot, PRICE_STATUS_MISSING_STRUCTURE, null, false);
      PackagePriceResult result = PackagePriceResult.of(price, List.of(), snapshotResult);
      result.getWarnings().add("包装组件缺结构，无法生成子件价格");
      result.getWarnings().addAll(snapshotResult.getWarnings());
      return result;
    }

    List<PackageComponentPriceDetail> details = resolveChildren(req, price, snapshotResult.getDetails());
    BigDecimal total = BigDecimal.ZERO;
    boolean complete = true;
    for (PackageComponentPriceDetail detail : details) {
      priceDetailMapper.insert(detail);
      if (DETAIL_STATUS_PRICED.equals(detail.getPriceStatus())) {
        total = total.add(detail.getChildAmount());
      } else if (!PackageComponentPriceDetail.HOUSEHOLD_EXCLUDED.equals(detail.getPriceStatus())) {
        complete = false;
        upsertGap(buildGap(req, snapshot, detail));
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

  private List<PackageComponentPriceDetail> resolveChildren(NormalizedRequest req,
      PackageComponentPrice price, List<PackageComponentSnapshotDetail> source) {
    var details = source.stream().map(row -> baseDetail(price, row)).toList();
    for (String sourcePath : req.householdChildPaths.keySet()) {
      if (details.stream().noneMatch(detail -> sourcePath.equals(detail.getSourcePath())))
        throw new MonthlyPriceProblem(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT,
            "包装原始直接子件中无法定位独立家用路径：" + sourcePath);
    }
    var valid = new ArrayList<PackageComponentPriceDetail>();
    for (var detail : details) {
      String householdPath = detail.getSourcePath() == null ? null
          : req.householdChildPaths.get(detail.getSourcePath());
      if (householdPath != null) {
        var scope = req.monthlyPrices.bomScope().orElseThrow(() ->
            new IllegalArgumentException("包装家用排除缺少本次原树计价范围"));
        var use = scope.household().stream().filter(value ->
            MonthlyBomPricingScope.pathKey(value.path()).equals(householdPath)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("包装排除路径不属于本次家用计价范围"));
        if (!use.request().family().materialCode().equals(detail.getChildMaterialCode()))
          throw new IllegalArgumentException("包装排除路径与原始子件料号不一致");
        detail.setHouseholdUse(new MonthlyAdoptionPlan.Use(MonthlyBomPricingScope.pathKey(use.path()),
            use.request(), use.cumulativeQuantity()));
        detail.setPriceStatus(PackageComponentPriceDetail.HOUSEHOLD_EXCLUDED);
        detail.setPriceSource(PackageComponentPriceDetail.HOUSEHOLD_EXCLUDED);
        continue;
      }
      if (!StringUtils.hasText(detail.getChildMaterialCode())) {
        markMissing(detail, DETAIL_STATUS_MISSING_PRICE, null, "子件料号为空");
        continue;
      }
      var quantity = applyChildParentBaseQty(detail);
      if (quantity == null) {
        markMissing(detail, DETAIL_STATUS_MISSING_PRICE, MonthlyPreparationPrice.KIND,
            "子件数量缺失或母件底数为 0，无法计算子件金额");
      } else if (quantity.signum() == 0) {
        detail.setChildUnitPrice(BigDecimal.ZERO); detail.setChildAmount(BigDecimal.ZERO);
        detail.setPriceStatus(DETAIL_STATUS_PRICED);
      } else valid.add(detail);
    }
    if (valid.isEmpty()) return details;
    var codes = valid.stream().map(PackageComponentPriceDetail::getChildMaterialCode).distinct().toList();
    var masters = materials.selectByLatestBatchAndCodes(codes, null,
        MaterialOrganization.fromPriceOrgCode(req.priceOrgCode).getCode()).stream()
        .collect(java.util.stream.Collectors.groupingBy(com.sanhua.marketingcost.entity.MaterialMasterRaw::getMaterialCode));
    var inputs = new java.util.LinkedHashMap<PackageComponentPriceDetail, QuotationPriceSession.Input>();
    for (var detail : valid) {
      var code = detail.getChildMaterialCode();
      var matched = masters.getOrDefault(code, List.of());
      if (matched.size() != 1 || !StringUtils.hasText(matched.getFirst().getUnit())) {
        markMissing(detail, DETAIL_STATUS_MISSING_PRICE, MonthlyPreparationPrice.KIND,
            "包装子件缺少唯一的当前组织物料计价单位");
        detail.setFailureCode(MonthlyAdoptionRetryPolicy.Failure.INVALID_INPUT.name());
        continue;
      }
      var master = matched.getFirst();
      inputs.put(detail, new QuotationPriceSession.Input(code, req.priceOrgCode, master.getUnit(),
          StringUtils.hasText(detail.getChildMaterialSpec()) ? detail.getChildMaterialSpec() : master.getMaterialSpec(),
          null, null, req.monthlyPrices.conditions(req.priceOrgCode), detail.getQtyPerParent().abs(),
          QuotationPriceSession.Purpose.MATERIAL));
    }
    if (inputs.isEmpty()) return details;
    var read = req.monthlyPrices.read(inputs.values());
    inputs.forEach((detail, input) -> {
      var value = read.values().get(input);
      if (value == null) {
        var problem = java.util.Objects.requireNonNull(read.problems().get(input));
        markMissing(detail, DETAIL_STATUS_MISSING_PRICE, MonthlyPreparationPrice.KIND, problem.message());
        detail.setFailureCode(problem.reason().name());
      } else {
        BigDecimal unitPrice = req.priceScenarioType == com.sanhua.marketingcost.enums.QuotePriceScenarioType.FINANCE_QUOTE_BASE
            ? value.financialPrice() : value.customerPrice();
        detail.setChildUnitPrice(unitPrice);
        detail.setChildAmount(applyChildParentBaseQty(detail).multiply(unitPrice));
        detail.setPriceType(value.selection().fact().type().name());
        detail.setPriceSource(MonthlyPreparationPrice.KIND);
        detail.setInputSnapshotJson(monthlyCodec.encode(MonthlyPreparationPrice.read(value)));
        detail.setPriceStatus(DETAIL_STATUS_PRICED);
        detail.setMissingReason(null);
      }
    });
    return details;
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
      String priceSource,
      String reason) {
    detail.setChildUnitPrice(null);
    detail.setChildAmount(null);
    detail.setPriceSource(priceSource);
    detail.setPriceStatus(status);
    detail.setMissingReason(reason);
  }

  private PackageComponentPriceDetail baseDetail(
      PackageComponentPrice price, PackageComponentSnapshotDetail snapshotDetail) {
    PackageComponentPriceDetail detail = new PackageComponentPriceDetail();
    detail.setBusinessUnitType(price.getBusinessUnitType());
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
    detail.setSourcePath(snapshotDetail.getSourcePath());
    detail.setSourceHierarchyId(snapshotDetail.getSourceHierarchyId());
    return detail;
  }

  private record StoredPrice(PackageComponentPrice price, boolean created) {}

  private StoredPrice ensurePriceRow(NormalizedRequest req, PackageComponentSnapshot snapshot) {
    PackageComponentPrice existing =
        selectByUniqueKey(req);
    if (existing != null) {
      existing.setSnapshotId(snapshot == null ? null : snapshot.getId());
      existing.setPriceOrgCode(req.priceOrgCode);
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
      return new StoredPrice(existing, false);
    }

    PackageComponentPrice price = new PackageComponentPrice();
    price.setBusinessUnitType(req.businessUnitType);
    price.setSnapshotId(snapshot == null ? null : snapshot.getId());
    price.setPackageMaterialCode(req.packageMaterialCode);
    price.setPriceOrgCode(req.priceOrgCode);
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
      return new StoredPrice(price, true);
    } catch (DuplicateKeyException ex) {
      PackageComponentPrice concurrent =
          selectByUniqueKey(req);
      if (concurrent != null) {
        concurrent.setSnapshotId(snapshot == null ? null : snapshot.getId());
        concurrent.setPriceOrgCode(req.priceOrgCode);
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
        return new StoredPrice(concurrent, false);
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
    if (snapshot != null && snapshot.getId() == null) {
      // This occurrence uses captured original-tree evidence, not another occurrence's stored snapshot.
      priceMapper.update(null, Wrappers.<PackageComponentPrice>lambdaUpdate()
          .eq(PackageComponentPrice::getId, price.getId()).set(PackageComponentPrice::getSnapshotId, null));
    }
  }

  private PackageComponentPrice transientPrice(
      NormalizedRequest req, PackageComponentSnapshot snapshot) {
    PackageComponentPrice price = new PackageComponentPrice();
    price.setBusinessUnitType(req.businessUnitType);
    price.setSnapshotId(snapshot == null ? null : snapshot.getId());
    price.setPackageMaterialCode(req.packageMaterialCode);
    price.setPriceOrgCode(req.priceOrgCode);
    price.setPackageMaterialName(snapshot == null ? null : snapshot.getPackageMaterialName());
    price.setPeriodMonth(req.periodMonth);
    price.setOaNo(blankIfNull(req.oaNo));
    price.setPriceAsOfTime(req.priceAsOfTime);
    price.setCalcBatchId(req.calcBatchId);
    price.setGeneratedAt(LocalDateTime.now());
    applyPackageUsage(price, snapshot);
    applySourceContext(price, snapshot, req);
    return price;
  }

  private void applyCalculatedPrice(
      PackageComponentPrice price,
      PackageComponentSnapshot snapshot,
      String status,
      BigDecimal totalPrice,
      boolean complete) {
    price.setSnapshotId(snapshot == null ? null : snapshot.getId());
    price.setPackageMaterialName(
        snapshot == null ? price.getPackageMaterialName() : snapshot.getPackageMaterialName());
    applyPackageUsage(price, snapshot);
    applySourceContext(price, snapshot, null);
    price.setPriceStatus(status);
    price.setPriceComplete(complete);
    price.setTotalPrice(totalPrice);
    price.setGeneratedAt(LocalDateTime.now());
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
      price.setSourceRawHierarchyId(snapshot.getSourceRawHierarchyId());
      price.setSourcePath(snapshot.getSourcePath());
      price.setPriceOrgCode(snapshot.getPriceOrgCode());
      price.setSourceTopProductCode(snapshot.getSourceTopProductCode());
      price.setSourceBomPurpose(snapshot.getSourceBomPurpose());
      price.setSourceBomSourceType(snapshot.getSourceBomSourceType());
      return;
    }
    if (req != null) {
      price.setPriceOrgCode(req.priceOrgCode);
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

  private PackageComponentPrice selectByUniqueKey(NormalizedRequest req) {
    List<PackageComponentPrice> rows = priceMapper.selectList(
        Wrappers.<PackageComponentPrice>lambdaQuery()
            .eq(PackageComponentPrice::getPeriodMonth, req.periodMonth)
            .eq(PackageComponentPrice::getPackageMaterialCode, req.packageMaterialCode)
            .eq(PackageComponentPrice::getPriceOrgCode, req.priceOrgCode)
            .eq(PackageComponentPrice::getSourceTopProductCode, req.topProductCode)
            .eq(PackageComponentPrice::getPriceAsOfTime, req.priceAsOfTime)
            .orderByDesc(PackageComponentPrice::getId)
            .last("LIMIT 1"));
    return rows == null || rows.isEmpty() ? null : rows.get(0);
  }

  private PackageSnapshotRequest toSnapshotRequest(NormalizedRequest req) {
    PackageSnapshotRequest snapshotRequest = new PackageSnapshotRequest();
    snapshotRequest.setPackageMaterialCode(req.packageMaterialCode);
    snapshotRequest.setPriceOrgCode(req.priceOrgCode);
    snapshotRequest.setPeriodMonth(req.periodMonth);
    snapshotRequest.setQuoteNo(req.quoteNo);
    snapshotRequest.setOaNo(req.oaNo);
    snapshotRequest.setTopProductCode(req.topProductCode);
    snapshotRequest.setBomPurpose(req.bomPurpose);
    snapshotRequest.setSourceType(req.sourceType);
    snapshotRequest.setAsOfDate(req.quoteDate);
    snapshotRequest.setSourceParentPath(req.sourceParentPath);
    snapshotRequest.setSourceParentHierarchyId(req.sourceParentHierarchyId);
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
      periodMonth = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingMonth();
    }
    boolean explicitPriceAsOfTime = request.getPriceAsOfTime() != null;
    LocalDate quoteDate = request.getAsOfDate() == null
        ? (explicitPriceAsOfTime ? request.getPriceAsOfTime().toLocalDate()
            : com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingDate())
        : request.getAsOfDate();
    LocalDateTime priceAsOfTime = explicitPriceAsOfTime
        ? request.getPriceAsOfTime()
        : quoteDate.atStartOfDay();
    String priceOrgCode = requiredPriceOrgCode(request.getPriceOrgCode());
    String businessUnit = StringUtils.hasText(request.getBusinessUnitType()) ? request.getBusinessUnitType().trim()
        : com.sanhua.marketingcost.security.BusinessUnitContext.getCurrentBusinessUnitType();
    var reviews = request.getSupplierPriceReviewContext() == null
        ? new com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext(request.getOaFormItemId()).forOrganization(priceOrgCode)
        : request.getSupplierPriceReviewContext().forOrganization(priceOrgCode);
    var monthly = monthlySessions.open(new QuotationPriceSessions.Request(
        trimToNull(request.getOaNo()), businessUnit, topProductCode, periodMonth, priceAsOfTime, reviews, request.getMonthlyPrices()));
    return new NormalizedRequest(
        packageMaterialCode,
        priceOrgCode,
        periodMonth,
        trimToNull(request.getQuoteNo()),
        trimToNull(request.getOaNo()),
        topProductCode,
        DEFAULT_BOM_PURPOSE,
        trimToNull(request.getSourceType()) == null ? DEFAULT_BOM_SOURCE_TYPE : trimToNull(request.getSourceType()),
        quoteDate,
        priceAsOfTime,
        explicitPriceAsOfTime,
        trimToNull(request.getCalcBatchId()),
        StringUtils.hasText(request.getBusinessUnitType()) ? request.getBusinessUnitType().trim()
            : com.sanhua.marketingcost.security.BusinessUnitContext.getCurrentBusinessUnitType(),
        request.getPriceScenarioType() == null ? com.sanhua.marketingcost.enums.QuotePriceScenarioType.OA_LOCKED : request.getPriceScenarioType(),
        monthly, java.util.Map.copyOf(request.getHouseholdChildPaths()), trimToNull(request.getSourceParentPath()),
        request.getSourceParentHierarchyId());
  }

  private String requiredPriceOrgCode(String priceOrgCode) {
    if (!StringUtils.hasText(priceOrgCode)) {
      throw new IllegalArgumentException("priceOrgCode 必须由上游报价产品行传入");
    }
    return MaterialOrganization.fromPriceOrgCode(priceOrgCode).getPriceOrgCode();
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String blankIfNull(String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? "" : trimmed;
  }

  private record NormalizedRequest(
      String packageMaterialCode,
      String priceOrgCode,
      String periodMonth,
      String quoteNo,
      String oaNo,
      String topProductCode,
      String bomPurpose,
      String sourceType,
      LocalDate quoteDate,
      LocalDateTime priceAsOfTime,
      boolean explicitPriceAsOfTime,
      String calcBatchId,
      String businessUnitType,
      com.sanhua.marketingcost.enums.QuotePriceScenarioType priceScenarioType,
      QuotationPriceSession monthlyPrices,
      java.util.Map<String, String> householdChildPaths,
      String sourceParentPath,
      Long sourceParentHierarchyId) {}
}
