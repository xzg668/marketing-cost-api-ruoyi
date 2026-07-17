package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.financequote.FinancePricePrepareGenerateResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateRequest;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareGenerateResult;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmBatch;
import com.sanhua.marketingcost.entity.QuoteBomConfirmation;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmBatchMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationMapper;
import com.sanhua.marketingcost.service.FinancePricePrepareService;
import com.sanhua.marketingcost.service.FinanceQuoteBasePriceService;
import com.sanhua.marketingcost.service.PricePrepareService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class FinancePricePrepareServiceImpl implements FinancePricePrepareService {
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String ITEM_STATUS_READY = "READY";
  private static final String REF_MAKE_PART = "MAKE_PART_PRICE";

  private final PricePrepareBatchMapper batchMapper;
  private final PricePrepareItemMapper itemMapper;
  private final QuotePriceTypeConfirmBatchMapper confirmBatchMapper;
  private final QuoteBomConfirmationMapper bomConfirmationMapper;
  private final MakePartPriceCalcRowMapper makePartRowMapper;
  private final FinanceQuoteBasePriceService financeBasePriceService;
  private final PricePrepareService pricePrepareService;

  public FinancePricePrepareServiceImpl(
      PricePrepareBatchMapper batchMapper,
      PricePrepareItemMapper itemMapper,
      QuotePriceTypeConfirmBatchMapper confirmBatchMapper,
      QuoteBomConfirmationMapper bomConfirmationMapper,
      MakePartPriceCalcRowMapper makePartRowMapper,
      FinanceQuoteBasePriceService financeBasePriceService,
      PricePrepareService pricePrepareService) {
    this.batchMapper = batchMapper;
    this.itemMapper = itemMapper;
    this.confirmBatchMapper = confirmBatchMapper;
    this.bomConfirmationMapper = bomConfirmationMapper;
    this.makePartRowMapper = makePartRowMapper;
    this.financeBasePriceService = financeBasePriceService;
    this.pricePrepareService = pricePrepareService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public FinancePricePrepareGenerateResult generateFromOa(String sourcePrepareNo) {
    String normalizedSourceNo = requireText(sourcePrepareNo, "sourcePrepareNo");
    PricePrepareBatch source = loadSourceBatch(normalizedSourceNo);
    validateSourceBatch(source);
    validatePriceTypeConfirmation(source);
    List<PricePrepareItem> sourceItems = loadItems(normalizedSourceNo);
    validateSourceItems(sourceItems);

    FinanceBasePrice financeBase = financeBasePriceService.getRequired(source.getPeriodMonth());
    if (financeBase.getPrice() == null || financeBase.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalStateException("财务 Cu 基准价格必须大于0");
    }
    if (!sameText(source.getBusinessUnitType(), financeBase.getBusinessUnitType())) {
      throw new IllegalStateException("OA价格准备批次与财务Cu基准不属于同一业务单元");
    }

    String scenarioGroupNo = ensureScenarioGroup(source);
    PricePrepareGenerateRequest request = financeRequest(source, scenarioGroupNo, financeBase);
    PricePrepareGenerateResult generated = pricePrepareService.generate(request);
    if (generated == null || !STATUS_SUCCESS.equals(generated.getStatus())) {
      throw new IllegalStateException(
          "财务Cu价格准备未成功：" + (generated == null ? "无生成结果" : generated.getMessage()));
    }
    if (!source.getPriceAsOfTime().equals(generated.getPriceAsOfTime())) {
      throw new IllegalStateException("财务与OA价格准备的取价时点不一致");
    }

    List<PricePrepareItem> financeItems = loadItems(generated.getPrepareNo());
    validateSameInputs(sourceItems, financeItems);
    validateMakePartInputs(sourceItems, financeItems);
    return new FinancePricePrepareGenerateResult(
        normalizedSourceNo,
        generated.getPrepareNo(),
        scenarioGroupNo,
        financeBase.getId(),
        financeBase.getPrice(),
        generated);
  }

  @Override
  @Transactional(readOnly = true)
  public FinancePricePrepareGenerateResult loadPreparedFromOa(String sourcePrepareNo) {
    String normalizedSourceNo = requireText(sourcePrepareNo, "sourcePrepareNo");
    PricePrepareBatch source = loadSourceBatch(normalizedSourceNo);
    validateSourceBatch(source);
    validatePriceTypeConfirmation(source);
    List<PricePrepareItem> sourceItems = loadItems(normalizedSourceNo);
    validateSourceItems(sourceItems);

    String scenarioGroupNo = requireText(source.getScenarioGroupNo(), "OA价格准备场景组编号");
    PricePrepareBatch finance =
        batchMapper.selectOne(
            Wrappers.lambdaQuery(PricePrepareBatch.class)
                .eq(PricePrepareBatch::getSourcePrepareNo, normalizedSourceNo)
                .eq(
                    PricePrepareBatch::getScenarioType,
                    QuotePriceScenarioType.FINANCE_QUOTE_BASE.name())
                .eq(PricePrepareBatch::getScenarioGroupNo, scenarioGroupNo)
                .orderByDesc(PricePrepareBatch::getId)
                .last("LIMIT 1"));
    if (finance == null) {
      throw new IllegalStateException("最终价格缺少已生成的财务场景，请重新生成最终价格");
    }
    validatePreparedFinanceBatch(source, finance);

    List<PricePrepareItem> financeItems = loadItems(finance.getPrepareNo());
    validateSameInputs(sourceItems, financeItems);
    validateMakePartInputs(sourceItems, financeItems);

    FinanceBasePrice financeBase = financeBasePriceService.getRequired(source.getPeriodMonth());
    if (financeBase.getPrice() == null || financeBase.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
      throw new IllegalStateException("财务 Cu 基准价格必须大于0");
    }
    if (!sameText(source.getBusinessUnitType(), financeBase.getBusinessUnitType())) {
      throw new IllegalStateException("OA价格准备批次与财务Cu基准不属于同一业务单元");
    }
    return new FinancePricePrepareGenerateResult(
        normalizedSourceNo,
        finance.getPrepareNo(),
        scenarioGroupNo,
        financeBase.getId(),
        financeBase.getPrice(),
        toGenerateResult(finance));
  }

  private PricePrepareBatch loadSourceBatch(String prepareNo) {
    PricePrepareBatch batch = batchMapper.selectOne(
        Wrappers.lambdaQuery(PricePrepareBatch.class)
            .eq(PricePrepareBatch::getPrepareNo, prepareNo)
            .last("LIMIT 1"));
    if (batch == null) {
      throw new IllegalArgumentException("OA价格准备批次不存在: " + prepareNo);
    }
    return batch;
  }

  private void validateSourceBatch(PricePrepareBatch source) {
    if (!QuotePriceScenarioType.OA_LOCKED.name().equals(source.getScenarioType())) {
      throw new IllegalArgumentException("sourcePrepareNo必须指向OA_LOCKED价格准备批次");
    }
    if (!STATUS_SUCCESS.equals(source.getStatus()) || value(source.getGapCount()) != 0) {
      throw new IllegalStateException("OA价格准备批次必须已成功且无缺口");
    }
    requireText(source.getOaNo(), "OA价格准备批次oaNo");
    requireText(source.getPeriodMonth(), "OA价格准备批次pricing_month");
    requireText(source.getPriceTypeConfirmNo(), "OA价格准备批次priceTypeConfirmNo");
    requireText(source.getBusinessUnitType(), "OA价格准备批次businessUnitType");
    if (source.getPriceAsOfTime() == null) {
      throw new IllegalStateException("OA价格准备批次缺price_as_of_time");
    }
    if (source.getOaFormItemId() == null || !StringUtils.hasText(source.getTopProductCode())) {
      throw new IllegalStateException("FCQ-05当前只支持一个产品的OA价格准备批次");
    }
  }

  private void validatePreparedFinanceBatch(
      PricePrepareBatch source, PricePrepareBatch finance) {
    if (!QuotePriceScenarioType.FINANCE_QUOTE_BASE.name().equals(finance.getScenarioType())) {
      throw new IllegalStateException("最终价格财务批次场景不正确，请重新生成最终价格");
    }
    if (!STATUS_SUCCESS.equals(finance.getStatus()) || value(finance.getGapCount()) != 0) {
      throw new IllegalStateException("最终价格财务批次未成功或仍有缺口，请重新生成最终价格");
    }
    requireSame("财务批次", "来源OA批次", source.getPrepareNo(), finance.getSourcePrepareNo());
    requireSame("财务批次", "场景组", source.getScenarioGroupNo(), finance.getScenarioGroupNo());
    requireSame("财务批次", "OA单号", source.getOaNo(), finance.getOaNo());
    requireSame("财务批次", "OA产品行", source.getOaFormItemId(), finance.getOaFormItemId());
    requireSame("财务批次", "顶层产品", source.getTopProductCode(), finance.getTopProductCode());
    requireSame(
        "财务批次", "价格类型确认批次", source.getPriceTypeConfirmNo(), finance.getPriceTypeConfirmNo());
    requireSame("财务批次", "计价月份", source.getPeriodMonth(), finance.getPeriodMonth());
    requireSame("财务批次", "取价时点", source.getPriceAsOfTime(), finance.getPriceAsOfTime());
    requireSame("财务批次", "业务单元", source.getBusinessUnitType(), finance.getBusinessUnitType());
    requireSame("财务批次", "BOM目的", source.getBomPurpose(), finance.getBomPurpose());
    requireSame("财务批次", "BOM来源", source.getSourceType(), finance.getSourceType());
  }

  private PricePrepareGenerateResult toGenerateResult(PricePrepareBatch batch) {
    PricePrepareGenerateResult result = new PricePrepareGenerateResult();
    result.setPrepareNo(batch.getPrepareNo());
    result.setOaNo(batch.getOaNo());
    result.setOaFormItemId(batch.getOaFormItemId());
    result.setTopProductCode(batch.getTopProductCode());
    result.setPriceTypeConfirmNo(batch.getPriceTypeConfirmNo());
    result.setPeriodMonth(batch.getPeriodMonth());
    result.setBomPurpose(batch.getBomPurpose());
    result.setSourceType(batch.getSourceType());
    result.setScenarioType(batch.getScenarioType());
    result.setScenarioGroupNo(batch.getScenarioGroupNo());
    result.setSourcePrepareNo(batch.getSourcePrepareNo());
    result.setStatus(batch.getStatus());
    result.setTotalCount(value(batch.getTotalCount()));
    result.setSuccessCount(value(batch.getSuccessCount()));
    result.setWarningCount(value(batch.getWarningCount()));
    result.setGapCount(value(batch.getGapCount()));
    result.setPriceAsOfTime(batch.getPriceAsOfTime());
    result.setPriceAsOfSource(batch.getPriceAsOfSource());
    result.setMessage(batch.getMessage());
    return result;
  }

  private void validatePriceTypeConfirmation(PricePrepareBatch source) {
    QuotePriceTypeConfirmBatch confirmation = confirmBatchMapper.selectOne(
        Wrappers.lambdaQuery(QuotePriceTypeConfirmBatch.class)
            .eq(QuotePriceTypeConfirmBatch::getConfirmNo, source.getPriceTypeConfirmNo())
            .eq(QuotePriceTypeConfirmBatch::getOaNo, source.getOaNo())
            .eq(QuotePriceTypeConfirmBatch::getOaFormItemId, source.getOaFormItemId())
            .eq(QuotePriceTypeConfirmBatch::getProductCode, source.getTopProductCode())
            .eq(QuotePriceTypeConfirmBatch::getPeriodMonth, source.getPeriodMonth())
            .eq(QuotePriceTypeConfirmBatch::getBusinessUnitType, source.getBusinessUnitType())
            .last("LIMIT 1"));
    if (confirmation == null
        || !QuotePriceTypeConfirmBatch.STATUS_CONFIRMED.equals(confirmation.getStatus())
        || !StringUtils.hasText(confirmation.getBomConfirmNo())) {
      throw new IllegalStateException("OA价格准备引用的BOM/价格类型确认批次已失效或不存在");
    }
    QuoteBomConfirmation bomConfirmation = bomConfirmationMapper.selectOne(
        Wrappers.lambdaQuery(QuoteBomConfirmation.class)
            .eq(QuoteBomConfirmation::getConfirmNo, confirmation.getBomConfirmNo())
            .eq(QuoteBomConfirmation::getOaNo, source.getOaNo())
            .eq(QuoteBomConfirmation::getOaFormItemId, source.getOaFormItemId())
            .eq(QuoteBomConfirmation::getTopProductCode, source.getTopProductCode())
            .eq(QuoteBomConfirmation::getPeriodMonth, source.getPeriodMonth())
            .eq(QuoteBomConfirmation::getBusinessUnitType, source.getBusinessUnitType())
            .last("LIMIT 1"));
    if (bomConfirmation == null
        || !QuoteBomConfirmation.STATUS_CONFIRMED.equals(
            bomConfirmation.getConfirmStatus())) {
      throw new IllegalStateException("OA价格准备引用的BOM确认批次已失效或不存在");
    }
  }

  private List<PricePrepareItem> loadItems(String prepareNo) {
    List<PricePrepareItem> items = itemMapper.selectList(
        Wrappers.lambdaQuery(PricePrepareItem.class)
            .eq(PricePrepareItem::getPrepareNo, prepareNo)
            .orderByAsc(PricePrepareItem::getId));
    return items == null ? List.of() : items;
  }

  private void validateSourceItems(List<PricePrepareItem> sourceItems) {
    if (sourceItems.isEmpty()) {
      throw new IllegalStateException("OA价格准备批次没有价格明细");
    }
    Map<String, PricePrepareItem> keyed = indexBySettlementKey(sourceItems, "OA");
    if (keyed.size() != sourceItems.size()) {
      throw new IllegalStateException("OA价格准备明细结算键不完整");
    }
    for (PricePrepareItem item : sourceItems) {
      if (!ITEM_STATUS_READY.equals(item.getStatus())) {
        throw new IllegalStateException("OA价格准备存在未就绪明细: " + item.getSettlementKey());
      }
    }
  }

  private PricePrepareGenerateRequest financeRequest(
      PricePrepareBatch source, String scenarioGroupNo, FinanceBasePrice financeBase) {
    PricePrepareGenerateRequest request = new PricePrepareGenerateRequest();
    request.setOaNo(source.getOaNo());
    request.setOaFormItemId(source.getOaFormItemId());
    request.setTopProductCode(source.getTopProductCode());
    request.setPriceTypeConfirmNo(source.getPriceTypeConfirmNo());
    request.setPeriodMonth(source.getPeriodMonth());
    request.setPriceAsOfTime(source.getPriceAsOfTime());
    request.setBusinessUnitType(source.getBusinessUnitType());
    request.setBomPurpose(source.getBomPurpose());
    request.setSourceType(source.getSourceType());
    request.setScenarioType(QuotePriceScenarioType.FINANCE_QUOTE_BASE);
    request.setScenarioGroupNo(scenarioGroupNo);
    request.setSourcePrepareNo(source.getPrepareNo());
    // 数据库价格口径已经是元/公斤；这里只传 Cu 一个键，绝不清空或覆盖 Zn/Al。
    request.setVariableOverrides(Map.of("Cu", financeBase.getPrice()));
    return request;
  }

  private String ensureScenarioGroup(PricePrepareBatch source) {
    if (StringUtils.hasText(source.getScenarioGroupNo())) {
      return source.getScenarioGroupNo().trim();
    }
    String groupNo = "FQG-" + UUID.randomUUID();
    source.setScenarioGroupNo(groupNo);
    if (batchMapper.updateById(source) != 1) {
      throw new IllegalStateException("OA价格准备场景组编号写入失败");
    }
    return groupNo;
  }

  private void validateSameInputs(
      List<PricePrepareItem> sourceItems, List<PricePrepareItem> financeItems) {
    Map<String, PricePrepareItem> sourceMap = indexBySettlementKey(sourceItems, "OA");
    Map<String, PricePrepareItem> financeMap = indexBySettlementKey(financeItems, "财务");
    if (!sourceMap.keySet().equals(financeMap.keySet())) {
      throw new IllegalStateException("财务与OA价格准备的结算明细范围不一致");
    }
    for (String key : sourceMap.keySet()) {
      PricePrepareItem oa = sourceMap.get(key);
      PricePrepareItem finance = financeMap.get(key);
      requireSame(key, "料号", oa.getMaterialCode(), finance.getMaterialCode());
      requireSame(key, "明细类型", oa.getItemType(), finance.getItemType());
      requireSame(key, "数量", oa.getQuantity(), finance.getQuantity());
      requireSame(key, "价格确认明细", oa.getPriceTypeConfirmItemId(), finance.getPriceTypeConfirmItemId());
      if (mustRemainExact(oa)) {
        requireSame(key, "非Cu单价", oa.getUnitPrice(), finance.getUnitPrice());
        requireSame(key, "非Cu金额", oa.getAmount(), finance.getAmount());
      }
    }
  }

  private boolean mustRemainExact(PricePrepareItem item) {
    String refType = item == null ? null : item.getResultRefType();
    return refType == null
        || "FIXED_PRICE".equals(refType)
        || "RANGE_PRICE".equals(refType)
        || refType.startsWith("PACKAGE");
  }

  private void validateMakePartInputs(
      List<PricePrepareItem> sourceItems, List<PricePrepareItem> financeItems) {
    Map<String, PricePrepareItem> financeMap = indexBySettlementKey(financeItems, "财务");
    for (PricePrepareItem sourceItem : sourceItems) {
      if (!REF_MAKE_PART.equals(sourceItem.getResultRefType())) {
        continue;
      }
      PricePrepareItem financeItem = financeMap.get(sourceItem.getSettlementKey());
      List<MakePartPriceCalcRow> sourceRows = loadMakeRows(sourceItem);
      List<MakePartPriceCalcRow> financeRows = loadMakeRows(financeItem);
      Map<String, MakePartPriceCalcRow> sourceMap = indexMakeRows(sourceRows);
      Map<String, MakePartPriceCalcRow> financeRowMap = indexMakeRows(financeRows);
      if (!sourceMap.keySet().equals(financeRowMap.keySet())) {
        throw new IllegalStateException(
            "制造件子原材料/废料范围不一致: " + sourceItem.getSettlementKey());
      }
      for (String componentKey : sourceMap.keySet()) {
        MakePartPriceCalcRow oa = sourceMap.get(componentKey);
        MakePartPriceCalcRow finance = financeRowMap.get(componentKey);
        requireSame(componentKey, "子项数量", oa.getQtyPerParent(), finance.getQtyPerParent());
        requireSame(componentKey, "毛重", oa.getGrossWeightG(), finance.getGrossWeightG());
        requireSame(componentKey, "净重", oa.getNetWeightG(), finance.getNetWeightG());
      }
    }
  }

  private List<MakePartPriceCalcRow> loadMakeRows(PricePrepareItem item) {
    if (item == null || item.getResultRefId() == null) {
      throw new IllegalStateException("制造件价格准备缺结果引用");
    }
    MakePartPriceCalcRow referenced = makePartRowMapper.selectById(item.getResultRefId());
    if (referenced == null || !StringUtils.hasText(referenced.getCalcBatchId())) {
      throw new IllegalStateException("制造件价格准备结果引用不存在: " + item.getResultRefId());
    }
    List<MakePartPriceCalcRow> rows = makePartRowMapper.selectList(
        Wrappers.lambdaQuery(MakePartPriceCalcRow.class)
            .eq(MakePartPriceCalcRow::getCalcBatchId, referenced.getCalcBatchId())
            .eq(MakePartPriceCalcRow::getParentMaterialNo, referenced.getParentMaterialNo())
            .orderByAsc(MakePartPriceCalcRow::getId));
    return rows == null ? List.of() : rows;
  }

  private Map<String, PricePrepareItem> indexBySettlementKey(
      List<PricePrepareItem> items, String label) {
    Map<String, PricePrepareItem> result = new LinkedHashMap<>();
    for (PricePrepareItem item : items) {
      String key = requireText(item.getSettlementKey(), label + "结算键");
      if (result.putIfAbsent(key, item) != null) {
        throw new IllegalStateException(label + "价格准备明细结算键重复: " + key);
      }
    }
    return result;
  }

  private Map<String, MakePartPriceCalcRow> indexMakeRows(List<MakePartPriceCalcRow> rows) {
    return rows.stream().collect(Collectors.toMap(
        row -> text(row.getChildMaterialNo()) + "|" + text(row.getScrapCode()),
        Function.identity(),
        (left, right) -> {
          throw new IllegalStateException("制造件组件键重复");
        },
        LinkedHashMap::new));
  }

  private void requireSame(String key, String field, Object left, Object right) {
    if (left instanceof BigDecimal a && right instanceof BigDecimal b) {
      if (a.compareTo(b) == 0) {
        return;
      }
    } else if (left == null ? right == null : left.equals(right)) {
      return;
    }
    throw new IllegalStateException(
        "财务与OA价格准备输入不一致: key="
            + key
            + ", field="
            + field
            + ", oa="
            + left
            + ", finance="
            + right);
  }

  private int value(Integer value) {
    return value == null ? 0 : value;
  }

  private boolean sameText(String left, String right) {
    return text(left).equals(text(right));
  }

  private String text(String value) {
    return StringUtils.hasText(value) ? value.trim() : "";
  }

  private String requireText(String value, String field) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return value.trim();
  }
}
