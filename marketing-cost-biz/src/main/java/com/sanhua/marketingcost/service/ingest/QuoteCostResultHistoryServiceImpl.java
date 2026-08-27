package com.sanhua.marketingcost.service.ingest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.MonthlyRepriceCostItemDto;
import com.sanhua.marketingcost.dto.MonthlyRepricePartItemDto;
import com.sanhua.marketingcost.dto.ingest.QuoteCostResultHistoryItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteCostResultHistoryResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteMonthlyCostResultDetailResponse;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.entity.MonthlyRepriceCostItem;
import com.sanhua.marketingcost.entity.MonthlyRepricePartItem;
import com.sanhua.marketingcost.entity.MonthlyRepriceResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceCostItemMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepricePartItemMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceResultMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.util.QuoteProductIdentityUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 报价产品结果历史查询。
 *
 * <p>原报价结果和月度调价结果只在这里汇总展示；月度调价不会改写报价成本版本，当前月重新核算也不会
 * 把历史结果伪装成当前工作区。
 */
@Service
public class QuoteCostResultHistoryServiceImpl implements QuoteCostResultHistoryService {
  private static final Set<String> VISIBLE_QUOTE_STATUSES =
      Set.of("HISTORY", "SUCCESS", "CONFIRMED");
  private static final String MONTHLY_CONFIRMED = "CONFIRMED";

  private final OaFormMapper formMapper;
  private final OaFormItemMapper itemMapper;
  private final QuoteCostRunVersionMapper versionMapper;
  private final MonthlyRepriceBatchMapper monthlyBatchMapper;
  private final MonthlyRepriceResultMapper monthlyResultMapper;
  private final MonthlyRepricePartItemMapper monthlyPartItemMapper;
  private final MonthlyRepriceCostItemMapper monthlyCostItemMapper;

  public QuoteCostResultHistoryServiceImpl(
      OaFormMapper formMapper,
      OaFormItemMapper itemMapper,
      QuoteCostRunVersionMapper versionMapper,
      MonthlyRepriceBatchMapper monthlyBatchMapper,
      MonthlyRepriceResultMapper monthlyResultMapper,
      MonthlyRepricePartItemMapper monthlyPartItemMapper,
      MonthlyRepriceCostItemMapper monthlyCostItemMapper) {
    this.formMapper = formMapper;
    this.itemMapper = itemMapper;
    this.versionMapper = versionMapper;
    this.monthlyBatchMapper = monthlyBatchMapper;
    this.monthlyResultMapper = monthlyResultMapper;
    this.monthlyPartItemMapper = monthlyPartItemMapper;
    this.monthlyCostItemMapper = monthlyCostItemMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public QuoteCostResultHistoryResponse listHistory(String oaNo, Long oaFormItemId) {
    Scope scope = requireScope(oaNo, oaFormItemId);
    List<QuoteCostRunVersion> quoteVersions = visibleQuoteVersions(scope);
    QuoteCostRunVersion original = originalQuoteVersion(scope.form(), quoteVersions);

    List<QuoteCostResultHistoryItemResponse> rows = new ArrayList<>();
    quoteVersions.stream()
        .sorted(
            Comparator.comparing((QuoteCostRunVersion row) -> !sameId(row, original))
                .thenComparing(
                    QuoteCostRunVersion::getTrialFinishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(
                    QuoteCostRunVersion::getId,
                    Comparator.nullsLast(Comparator.reverseOrder())))
        .map(row -> toQuoteHistory(row, sameId(row, original)))
        .forEach(rows::add);

    confirmedMonthlyResults(scope).stream()
        .sorted(
            Comparator.comparing(
                    MonthlyRepriceResult::getPricingMonth,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(
                    MonthlyRepriceResult::getUpdatedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(
                    MonthlyRepriceResult::getId,
                    Comparator.nullsLast(Comparator.naturalOrder())))
        .map(this::toMonthlyHistory)
        .forEach(rows::add);

    QuoteCostResultHistoryResponse response = new QuoteCostResultHistoryResponse();
    response.setOaNo(scope.oaNo());
    response.setOaFormItemId(scope.item().getId());
    response.setProductCode(scope.productCode());
    response.setResults(rows);
    return response;
  }

  @Override
  @Transactional(readOnly = true)
  public QuoteMonthlyCostResultDetailResponse getMonthlyResult(
      String oaNo, Long oaFormItemId, Long resultId) {
    Scope scope = requireScope(oaNo, oaFormItemId);
    if (resultId == null || resultId <= 0) {
      throw new QuoteIngestException("月度调价结果 ID 必须大于0");
    }
    MonthlyRepriceResult result = monthlyResultMapper.selectById(resultId);
    if (result == null || !ownedByScope(scope, result)) {
      throw new QuoteIngestException("月度调价结果不属于当前报价产品行");
    }
    MonthlyRepriceBatch batch = confirmedBatch(scope, result.getRepriceNo());
    if (batch == null) {
      throw new QuoteIngestException("月度调价结果尚未审批发布，不能作为报价历史查看");
    }
    if (!StringUtils.hasText(result.getCalcObjectKey())) {
      throw new QuoteIngestException("月度调价结果缺少明细标识，无法查看明细");
    }

    QuoteMonthlyCostResultDetailResponse response = new QuoteMonthlyCostResultDetailResponse();
    response.setResult(toMonthlyHistory(result));
    response.setPartItems(
        monthlyPartItemMapper
            .selectList(
                Wrappers.lambdaQuery(MonthlyRepricePartItem.class)
                    .eq(MonthlyRepricePartItem::getRepriceNo, result.getRepriceNo())
                    .eq(MonthlyRepricePartItem::getCalcObjectKey, result.getCalcObjectKey())
                    .orderByAsc(MonthlyRepricePartItem::getLineNo)
                    .orderByAsc(MonthlyRepricePartItem::getId))
            .stream()
            .map(MonthlyRepricePartItemDto::fromEntity)
            .toList());
    response.setCostItems(
        monthlyCostItemMapper
            .selectList(
                Wrappers.lambdaQuery(MonthlyRepriceCostItem.class)
                    .eq(MonthlyRepriceCostItem::getRepriceNo, result.getRepriceNo())
                    .eq(MonthlyRepriceCostItem::getCalcObjectKey, result.getCalcObjectKey())
                    .orderByAsc(MonthlyRepriceCostItem::getLineNo)
                    .orderByAsc(MonthlyRepriceCostItem::getId))
            .stream()
            .map(MonthlyRepriceCostItemDto::fromEntity)
            .toList());
    return response;
  }

  private List<QuoteCostRunVersion> visibleQuoteVersions(Scope scope) {
    return versionMapper
        .selectList(
            Wrappers.lambdaQuery(QuoteCostRunVersion.class)
                .eq(QuoteCostRunVersion::getOaNo, scope.oaNo())
                .eq(QuoteCostRunVersion::getProductCode, scope.productCode())
                .in(QuoteCostRunVersion::getStatus, VISIBLE_QUOTE_STATUSES)
                .isNotNull(QuoteCostRunVersion::getTotalCost)
                .orderByAsc(QuoteCostRunVersion::getId))
        .stream()
        .filter(row -> ownedByScope(scope, row.getOaFormItemId()))
        .toList();
  }

  private List<MonthlyRepriceResult> confirmedMonthlyResults(Scope scope) {
    List<MonthlyRepriceResult> candidates =
        monthlyResultMapper
            .selectList(
                Wrappers.lambdaQuery(MonthlyRepriceResult.class)
                    .eq(MonthlyRepriceResult::getOaNo, scope.oaNo())
                    .eq(MonthlyRepriceResult::getProductCode, scope.productCode())
                    .eq(MonthlyRepriceResult::getCalcStatus, "SUCCESS")
                    .orderByAsc(MonthlyRepriceResult::getPricingMonth)
                    .orderByAsc(MonthlyRepriceResult::getId))
            .stream()
            .filter(row -> ownedByScope(scope, row))
            .toList();
    if (candidates.isEmpty()) {
      return List.of();
    }
    Set<String> repriceNos =
        candidates.stream()
            .map(MonthlyRepriceResult::getRepriceNo)
            .filter(StringUtils::hasText)
            .collect(Collectors.toSet());
    if (repriceNos.isEmpty()) {
      return List.of();
    }
    Map<String, MonthlyRepriceBatch> confirmedByNo =
        monthlyBatchMapper
            .selectList(
                Wrappers.lambdaQuery(MonthlyRepriceBatch.class)
                    .in(MonthlyRepriceBatch::getRepriceNo, repriceNos)
                    .eq(MonthlyRepriceBatch::getStatus, MONTHLY_CONFIRMED)
                    .eq(MonthlyRepriceBatch::getBusinessUnitType, scope.businessUnitType()))
            .stream()
            .collect(
                Collectors.toMap(
                    MonthlyRepriceBatch::getRepriceNo,
                    Function.identity(),
                    (first, ignored) -> first,
                    LinkedHashMap::new));
    return candidates.stream()
        .filter(row -> confirmedByNo.containsKey(row.getRepriceNo()))
        .toList();
  }

  private MonthlyRepriceBatch confirmedBatch(Scope scope, String repriceNo) {
    if (!StringUtils.hasText(repriceNo)) {
      return null;
    }
    return monthlyBatchMapper.selectOne(
        Wrappers.lambdaQuery(MonthlyRepriceBatch.class)
            .eq(MonthlyRepriceBatch::getRepriceNo, repriceNo.trim())
            .eq(MonthlyRepriceBatch::getStatus, MONTHLY_CONFIRMED)
            .eq(MonthlyRepriceBatch::getBusinessUnitType, scope.businessUnitType())
            .last("LIMIT 1"));
  }

  private QuoteCostRunVersion originalQuoteVersion(
      OaForm form, List<QuoteCostRunVersion> versions) {
    if (versions.isEmpty()) {
      return null;
    }
    String quoteMonth = trimToNull(form.getAccountingPeriodMonth());
    return versions.stream()
        .filter(
            row ->
                quoteMonth != null
                    && (quoteMonth.equals(trimToNull(row.getResultPeriod()))
                        || quoteMonth.equals(trimToNull(row.getPricingMonth()))))
        .min(Comparator.comparing(QuoteCostRunVersion::getId))
        .orElseGet(
            () ->
                versions.stream()
                    .filter(row -> "HISTORY".equals(normalize(row.getStatus())))
                    .min(Comparator.comparing(QuoteCostRunVersion::getId))
                    .orElseGet(
                        () -> versions.stream().min(Comparator.comparing(QuoteCostRunVersion::getId)).orElse(null)));
  }

  private QuoteCostResultHistoryItemResponse toQuoteHistory(
      QuoteCostRunVersion version, boolean original) {
    QuoteCostResultHistoryItemResponse row = new QuoteCostResultHistoryItemResponse();
    row.setResultType("QUOTE_COST");
    row.setResultTypeLabel(original ? "原报价结果" : "报价重新核算结果");
    row.setSourceId(version.getId());
    row.setVersionId(version.getId());
    row.setResultNo(firstText(version.getVersionNo(), version.getCostRunNo()));
    row.setPeriodMonth(firstText(version.getResultPeriod(), version.getPricingMonth()));
    row.setStatus(version.getStatus());
    row.setTotalCost(version.getTotalCost());
    row.setCompletedAt(firstTime(version.getConfirmedAt(), version.getTrialFinishedAt(), version.getUpdatedAt()));
    row.setDefaultResult(original);
    row.setFullCostSheetAvailable(version.getTotalCost() != null);
    return row;
  }

  private QuoteCostResultHistoryItemResponse toMonthlyHistory(MonthlyRepriceResult result) {
    QuoteCostResultHistoryItemResponse row = new QuoteCostResultHistoryItemResponse();
    row.setResultType("MONTHLY_REPRICE");
    row.setResultTypeLabel("月度调价结果");
    row.setSourceId(result.getId());
    row.setRepriceNo(result.getRepriceNo());
    row.setResultNo(result.getRepriceNo());
    row.setPeriodMonth(result.getPricingMonth());
    row.setStatus(MONTHLY_CONFIRMED);
    row.setTotalCost(result.getTotalCost());
    row.setMaterialCost(result.getMaterialCost());
    row.setLaborCost(result.getLaborCost());
    row.setAuxiliaryCost(result.getAuxiliaryCost());
    row.setManufacturingCost(result.getManufacturingCost());
    row.setManagementCost(result.getManagementCost());
    row.setSalesCost(result.getSalesCost());
    row.setFinanceCost(result.getFinanceCost());
    row.setCompletedAt(result.getUpdatedAt());
    row.setDefaultResult(false);
    row.setFullCostSheetAvailable(false);
    return row;
  }

  private Scope requireScope(String oaNo, Long itemId) {
    String oaNoValue = required("报价单号", oaNo);
    if (itemId == null || itemId <= 0) {
      throw new QuoteIngestException("报价产品行 ID 必须大于0");
    }
    OaForm form =
        formMapper.selectOne(
            Wrappers.lambdaQuery(OaForm.class)
                .eq(OaForm::getOaNo, oaNoValue)
                .last("LIMIT 1"));
    if (form == null) {
      throw new QuoteIngestException("报价单不存在: " + oaNoValue);
    }
    OaFormItem item = itemMapper.selectById(itemId);
    if (item == null || !Objects.equals(form.getId(), item.getOaFormId())) {
      throw new QuoteIngestException("报价产品行不存在或不属于当前报价单: " + itemId);
    }
    String businessUnit = currentBusinessUnit(form, item);
    String productCode =
        required(
            "产品料号、型号或图号",
            QuoteProductIdentityUtils.resolveCostingCode(item));
    var sameProduct =
        Wrappers.lambdaQuery(OaFormItem.class).eq(OaFormItem::getOaFormId, form.getId());
    if (StringUtils.hasText(item.getMaterialNo())) {
      sameProduct.eq(OaFormItem::getMaterialNo, item.getMaterialNo().trim());
    } else if (StringUtils.hasText(item.getSunlModel())) {
      sameProduct
          .and(
              material ->
                  material
                      .isNull(OaFormItem::getMaterialNo)
                      .or()
                      .eq(OaFormItem::getMaterialNo, ""))
          .eq(OaFormItem::getSunlModel, item.getSunlModel().trim());
    } else {
      sameProduct
          .and(
              material ->
                  material
                      .isNull(OaFormItem::getMaterialNo)
                      .or()
                      .eq(OaFormItem::getMaterialNo, ""))
          .and(
              model ->
                  model.isNull(OaFormItem::getSunlModel).or().eq(OaFormItem::getSunlModel, ""))
          .eq(OaFormItem::getCustomerDrawing, item.getCustomerDrawing().trim());
    }
    long activeSameProductCount = itemMapper.selectCount(sameProduct);
    return new Scope(
        form, item, oaNoValue, productCode, businessUnit, activeSameProductCount);
  }

  private String currentBusinessUnit(OaForm form, OaFormItem item) {
    String current = trimToNull(BusinessUnitContext.getCurrentBusinessUnitType());
    String formBusinessUnit = trimToNull(form.getBusinessUnitType());
    String itemBusinessUnit = trimToNull(item.getBusinessUnitType());
    if (current == null && BusinessUnitContext.isAdmin()) {
      current = firstText(itemBusinessUnit, formBusinessUnit);
    }
    if (current == null
        || (formBusinessUnit != null && !current.equals(formBusinessUnit))
        || (itemBusinessUnit != null && !current.equals(itemBusinessUnit))
        || (formBusinessUnit == null && itemBusinessUnit == null)) {
      throw new QuoteIngestException("当前业务单元无权查看该报价产品结果");
    }
    return current;
  }

  private boolean ownedByScope(Scope scope, MonthlyRepriceResult result) {
    return result != null
        && scope.oaNo().equals(trimToNull(result.getOaNo()))
        && scope.productCode().equals(trimToNull(result.getProductCode()))
        && ownedByScope(scope, result.getOaFormItemId());
  }

  private boolean ownedByScope(Scope scope, Long historicalItemId) {
    return Objects.equals(scope.item().getId(), historicalItemId)
        // 早期 OA 重导会删除旧产品行并生成新 ID；同报价同料号只有一个有效行时可安全归回。
        || scope.activeSameProductCount() == 1;
  }

  private boolean sameId(QuoteCostRunVersion left, QuoteCostRunVersion right) {
    return left != null && right != null && Objects.equals(left.getId(), right.getId());
  }

  private String required(String label, String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new QuoteIngestException(label + "不能为空");
    }
    return normalized;
  }

  private String firstText(String first, String second) {
    String normalized = trimToNull(first);
    return normalized == null ? trimToNull(second) : normalized;
  }

  private LocalDateTime firstTime(LocalDateTime... values) {
    for (LocalDateTime value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase();
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private record Scope(
      OaForm form,
      OaFormItem item,
      String oaNo,
      String productCode,
      String businessUnitType,
      long activeSameProductCount) {}
}
