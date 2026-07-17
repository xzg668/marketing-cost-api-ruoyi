package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.financequote.QuoteCuMaterialDiffResult;
import com.sanhua.marketingcost.entity.FactorQuoteBaseMapping;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PricePrepareBatch;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.entity.QuoteCuMaterialDiffItem;
import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import com.sanhua.marketingcost.enums.LinkedPriceFactorSource;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.mapper.FactorQuoteBaseMappingMapper;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PricePrepareBatchMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.PricePrepareSettlementKeyGenerator;
import com.sanhua.marketingcost.service.QuoteCuMaterialDiffService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteCuMaterialDiffServiceImpl implements QuoteCuMaterialDiffService {
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String ITEM_STATUS_READY = "READY";
  private static final String REF_LINKED_PRICE = "LINKED_PRICE";
  private static final String REF_MAKE_PART = "MAKE_PART_PRICE";
  private static final String DETAIL_SETTLEMENT = "SETTLEMENT";
  private static final String DETAIL_RAW_COMPONENT = "RAW_COMPONENT";
  private static final String CU_VARIABLE_CODE = "cu";
  private static final String FACTOR_IDENTITY_PREFIX = "factor_identity_";
  private static final int MONEY_SCALE = 8;

  private final QuoteCostRunVersionMapper versionMapper;
  private final PricePrepareBatchMapper batchMapper;
  private final PricePrepareItemMapper itemMapper;
  private final MakePartPriceCalcRowMapper makePartRowMapper;
  private final PriceLinkedCalcItemMapper linkedCalcItemMapper;
  private final FactorQuoteBaseMappingMapper factorQuoteBaseMappingMapper;
  private final ObjectMapper objectMapper;

  public QuoteCuMaterialDiffServiceImpl(
      QuoteCostRunVersionMapper versionMapper,
      PricePrepareBatchMapper batchMapper,
      PricePrepareItemMapper itemMapper,
      MakePartPriceCalcRowMapper makePartRowMapper,
      PriceLinkedCalcItemMapper linkedCalcItemMapper,
      FactorQuoteBaseMappingMapper factorQuoteBaseMappingMapper,
      ObjectMapper objectMapper) {
    this.versionMapper = versionMapper;
    this.batchMapper = batchMapper;
    this.itemMapper = itemMapper;
    this.makePartRowMapper = makePartRowMapper;
    this.linkedCalcItemMapper = linkedCalcItemMapper;
    this.factorQuoteBaseMappingMapper = factorQuoteBaseMappingMapper;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public QuoteCuMaterialDiffResult calculate(Long costRunVersionId) {
    if (costRunVersionId == null) {
      throw new IllegalArgumentException("costRunVersionId不能为空");
    }
    QuoteCostRunVersion version = versionMapper.selectById(costRunVersionId);
    validateVersion(version, costRunVersionId);
    PricePrepareBatch oaBatch = loadBatch(version.getOaPricePrepareNo(), "OA");
    PricePrepareBatch financeBatch = loadBatch(version.getFinancePricePrepareNo(), "财务");
    validateBatchPair(version, oaBatch, financeBatch);

    Map<String, PricePrepareItem> oaItems = loadAndIndexItems(oaBatch, "OA");
    Map<String, PricePrepareItem> financeItems = loadAndIndexItems(financeBatch, "财务");
    if (!oaItems.keySet().equals(financeItems.keySet())) {
      throw new IllegalStateException("OA与财务价格准备结算键范围不一致，不能计算差异");
    }

    Map<String, MakeRowsPair> makePairs = new LinkedHashMap<>();
    Set<String> linkedCodes = new LinkedHashSet<>();
    for (String settlementKey : oaItems.keySet()) {
      PricePrepareItem oa = oaItems.get(settlementKey);
      PricePrepareItem finance = financeItems.get(settlementKey);
      validateSettlementInputs(settlementKey, oa, finance);
      if (REF_LINKED_PRICE.equals(oa.getResultRefType())) {
        linkedCodes.add(requireText(oa.getMaterialCode(), "联动价料号"));
      }
      if (REF_MAKE_PART.equals(oa.getResultRefType())) {
        MakeRowsPair pair = loadAndValidateMakeRows(settlementKey, oa, finance);
        makePairs.put(settlementKey, pair);
        collectMakeLinkedCodes(pair, linkedCodes);
      }
    }

    Map<String, LinkedEvidence> oaLinked =
        loadLinkedEvidence(oaBatch, linkedCodes, LinkedPriceFactorSource.OA_LOCKED.getCode());
    Map<String, LinkedEvidence> financeLinked = loadLinkedEvidence(
        financeBatch, linkedCodes, LinkedPriceFactorSource.FINANCE_QUOTE_BASE.getCode());
    Set<String> cuVariableCodes = resolveCuVariableCodes(oaLinked, financeLinked);
    validateScenarioCu("OA", oaLinked, version.getOaCuPrice(), cuVariableCodes);
    validateScenarioCu("财务", financeLinked, version.getFinanceCuPrice(), cuVariableCodes);

    List<QuoteCuMaterialDiffItem> rows = new ArrayList<>();
    for (String settlementKey : oaItems.keySet()) {
      PricePrepareItem oa = oaItems.get(settlementKey);
      PricePrepareItem finance = financeItems.get(settlementKey);
      if (REF_MAKE_PART.equals(oa.getResultRefType())) {
        rows.addAll(buildMakeRows(
            version,
            oaBatch,
            financeBatch,
            settlementKey,
            oa,
            finance,
            makePairs.get(settlementKey),
            oaLinked,
            financeLinked,
            cuVariableCodes));
      } else {
        rows.add(buildSettlementRow(
            version,
            oaBatch,
            financeBatch,
            settlementKey,
            oa,
            finance,
            oaLinked,
            financeLinked,
            cuVariableCodes));
      }
    }

    int lineNo = 1;
    BigDecimal adjustment = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    int settlementCount = 0;
    int componentCount = 0;
    int cuAffectedSettlementCount = 0;
    for (QuoteCuMaterialDiffItem row : rows) {
      row.setLineNo(lineNo++);
      if (Integer.valueOf(1).equals(row.getContributesToAdjustment())) {
        adjustment = adjustment.add(row.getDiffAmount());
        settlementCount++;
        if (Integer.valueOf(1).equals(row.getCuAffected())) {
          cuAffectedSettlementCount++;
        }
      } else {
        componentCount++;
      }
    }
    adjustment = money(adjustment);

    return new QuoteCuMaterialDiffResult(
        version.getId(),
        version.getCostRunNo(),
        adjustment,
        settlementCount,
        componentCount,
        cuAffectedSettlementCount,
        rows);
  }

  private void validateVersion(QuoteCostRunVersion version, Long expectedId) {
    if (version == null) {
      throw new IllegalArgumentException("成本版本不存在: " + expectedId);
    }
    if (version.getId() == null || !version.getId().equals(expectedId)) {
      throw new IllegalStateException("成本版本ID不一致");
    }
    requireText(version.getCostRunNo(), "成本版本costRunNo");
    requireText(version.getOaNo(), "成本版本oaNo");
    requireText(version.getProductCode(), "成本版本productCode");
    requireText(version.getPricingMonth(), "成本版本pricingMonth");
    requireText(version.getBusinessUnitType(), "成本版本businessUnitType");
    requireText(version.getOaPricePrepareNo(), "成本版本oaPricePrepareNo");
    requireText(version.getFinancePricePrepareNo(), "成本版本financePricePrepareNo");
    if (version.getOaFormItemId() == null) {
      throw new IllegalStateException("FCQ-06只支持单产品成本版本");
    }
  }

  private PricePrepareBatch loadBatch(String prepareNo, String label) {
    String normalized = requireText(prepareNo, label + "价格准备批次号");
    PricePrepareBatch batch = batchMapper.selectOne(
        Wrappers.lambdaQuery(PricePrepareBatch.class)
            .eq(PricePrepareBatch::getPrepareNo, normalized)
            .last("LIMIT 1"));
    if (batch == null) {
      throw new IllegalStateException(label + "价格准备批次不存在: " + normalized);
    }
    return batch;
  }

  private void validateBatchPair(
      QuoteCostRunVersion version, PricePrepareBatch oa, PricePrepareBatch finance) {
    if (!QuotePriceScenarioType.OA_LOCKED.name().equals(oa.getScenarioType())) {
      throw new IllegalStateException("OA价格准备批次场景必须是OA_LOCKED");
    }
    if (!QuotePriceScenarioType.FINANCE_QUOTE_BASE.name().equals(finance.getScenarioType())) {
      throw new IllegalStateException("财务价格准备批次场景必须是FINANCE_QUOTE_BASE");
    }
    if (!STATUS_SUCCESS.equals(oa.getStatus())
        || !STATUS_SUCCESS.equals(finance.getStatus())
        || value(oa.getGapCount()) != 0
        || value(finance.getGapCount()) != 0) {
      throw new IllegalStateException("OA与财务价格准备批次必须成功且无缺口");
    }
    requireText(oa.getPriceTypeConfirmNo(), "OA价格类型确认批次");
    if (oa.getPriceAsOfTime() == null) {
      throw new IllegalStateException("OA价格准备批次缺取价时点");
    }
    requireSame("批次", "source_prepare_no", oa.getPrepareNo(), finance.getSourcePrepareNo());
    String oaGroup = requireText(oa.getScenarioGroupNo(), "OA场景组编号");
    requireSame("批次", "scenario_group_no", oaGroup, finance.getScenarioGroupNo());
    requireSame("批次", "oa_no", oa.getOaNo(), finance.getOaNo());
    requireSame("批次", "OA产品行ID", oa.getOaFormItemId(), finance.getOaFormItemId());
    requireSame("批次", "产品料号", oa.getTopProductCode(), finance.getTopProductCode());
    requireSame("批次", "价格类型确认批次", oa.getPriceTypeConfirmNo(), finance.getPriceTypeConfirmNo());
    requireSame("批次", "计价月份", oa.getPeriodMonth(), finance.getPeriodMonth());
    requireSame("批次", "取价时点", oa.getPriceAsOfTime(), finance.getPriceAsOfTime());
    requireSame("批次", "业务单元", oa.getBusinessUnitType(), finance.getBusinessUnitType());
    requireSame("批次", "BOM目的", oa.getBomPurpose(), finance.getBomPurpose());
    requireSame("批次", "BOM来源", oa.getSourceType(), finance.getSourceType());

    requireSame("成本版本", "OA批次", version.getOaPricePrepareNo(), oa.getPrepareNo());
    requireSame("成本版本", "财务批次", version.getFinancePricePrepareNo(), finance.getPrepareNo());
    requireSame("成本版本", "oa_no", version.getOaNo(), oa.getOaNo());
    requireSame("成本版本", "OA产品行ID", version.getOaFormItemId(), oa.getOaFormItemId());
    requireSame("成本版本", "产品料号", version.getProductCode(), oa.getTopProductCode());
    requireSame("成本版本", "计价月份", version.getPricingMonth(), oa.getPeriodMonth());
    requireSame("成本版本", "业务单元", version.getBusinessUnitType(), oa.getBusinessUnitType());
  }

  private Map<String, PricePrepareItem> loadAndIndexItems(
      PricePrepareBatch batch, String label) {
    List<PricePrepareItem> items = itemMapper.selectList(
        Wrappers.lambdaQuery(PricePrepareItem.class)
            .eq(PricePrepareItem::getPrepareNo, batch.getPrepareNo())
            .orderByAsc(PricePrepareItem::getId));
    if (items == null || items.isEmpty()) {
      throw new IllegalStateException(label + "价格准备批次没有结算明细");
    }
    Map<String, PricePrepareItem> indexed = new LinkedHashMap<>();
    for (PricePrepareItem item : items) {
      if (item == null || !ITEM_STATUS_READY.equals(item.getStatus())) {
        throw new IllegalStateException(label + "价格准备存在未就绪明细");
      }
      validateItemScope(batch, item, label);
      String key = requireText(item.getSettlementKey(), label + "结算键");
      if (indexed.putIfAbsent(key, item) != null) {
        throw new IllegalStateException(label + "价格准备结算键重复: " + key);
      }
      validatePreparedAmount(label + "结算键=" + key, item);
    }
    return indexed;
  }

  private void validateItemScope(
      PricePrepareBatch batch, PricePrepareItem item, String label) {
    if (item.getId() == null) {
      throw new IllegalStateException(label + "价格准备明细缺ID");
    }
    requireSame(label + "明细", "prepare_no", batch.getPrepareNo(), item.getPrepareNo());
    requireSame(label + "明细", "oa_no", batch.getOaNo(), item.getOaNo());
    requireSame(
        label + "明细", "OA产品行ID", batch.getOaFormItemId(), item.getOaFormItemId());
    requireSame(
        label + "明细", "顶层产品", batch.getTopProductCode(), item.getTopProductCode());
    requireSame(
        label + "明细", "价格类型确认批次", batch.getPriceTypeConfirmNo(), item.getPriceTypeConfirmNo());
    requireSame(label + "明细", "计价月份", batch.getPeriodMonth(), item.getPeriodMonth());
    requireSame(
        label + "明细", "业务单元", batch.getBusinessUnitType(), item.getBusinessUnitType());
    requireText(item.getResultRefType(), label + "价格结果类型");
  }

  private void validatePreparedAmount(String label, PricePrepareItem item) {
    if (item.getQuantity() == null || item.getUnitPrice() == null || item.getAmount() == null) {
      throw new IllegalStateException(label + "缺数量、单价或金额");
    }
    BigDecimal expected = money(item.getQuantity().multiply(item.getUnitPrice()));
    requireDecimal(label, "金额=数量×单价", expected, money(item.getAmount()));
  }

  private void validateSettlementInputs(
      String settlementKey, PricePrepareItem oa, PricePrepareItem finance) {
    requireSame(settlementKey, "料号", oa.getMaterialCode(), finance.getMaterialCode());
    requireSame(settlementKey, "材料名称", oa.getMaterialName(), finance.getMaterialName());
    requireSame(settlementKey, "明细类型", oa.getItemType(), finance.getItemType());
    requireSame(settlementKey, "BOM行ID", oa.getBomRowId(), finance.getBomRowId());
    requireSame(settlementKey, "OA产品行ID", oa.getOaFormItemId(), finance.getOaFormItemId());
    requireSame(settlementKey, "顶层产品", oa.getTopProductCode(), finance.getTopProductCode());
    requireSame(
        settlementKey,
        "价格确认明细",
        oa.getPriceTypeConfirmItemId(),
        finance.getPriceTypeConfirmItemId());
    requireSame(settlementKey, "价格结果类型", oa.getResultRefType(), finance.getResultRefType());
    requireSame(settlementKey, "价格来源", oa.getPriceSource(), finance.getPriceSource());
    requireSame(settlementKey, "业务单元", oa.getBusinessUnitType(), finance.getBusinessUnitType());
    requireDecimal(settlementKey, "数量", oa.getQuantity(), finance.getQuantity());
  }

  private MakeRowsPair loadAndValidateMakeRows(
      String settlementKey, PricePrepareItem oaItem, PricePrepareItem financeItem) {
    List<MakePartPriceCalcRow> oaRows =
        loadMakeRows(oaItem, QuotePriceScenarioType.OA_LOCKED);
    List<MakePartPriceCalcRow> financeRows =
        loadMakeRows(financeItem, QuotePriceScenarioType.FINANCE_QUOTE_BASE);
    Map<String, MakePartPriceCalcRow> oaMap = indexMakeRows(oaRows, "OA", settlementKey);
    Map<String, MakePartPriceCalcRow> financeMap =
        indexMakeRows(financeRows, "财务", settlementKey);
    if (!oaMap.keySet().equals(financeMap.keySet())) {
      throw new IllegalStateException("制造件组件范围不一致: " + settlementKey);
    }
    for (String componentIdentity : oaMap.keySet()) {
      MakePartPriceCalcRow oa = oaMap.get(componentIdentity);
      MakePartPriceCalcRow finance = financeMap.get(componentIdentity);
      requireSame(componentIdentity, "父制造件", oa.getParentMaterialNo(), finance.getParentMaterialNo());
      requireSame(componentIdentity, "子材料", oa.getChildMaterialNo(), finance.getChildMaterialNo());
      requireSame(componentIdentity, "废料", oa.getScrapCode(), finance.getScrapCode());
      requireSame(componentIdentity, "加工类型", oa.getItemProcessType(), finance.getItemProcessType());
      requireSame(componentIdentity, "库存单位", oa.getStockUnit(), finance.getStockUnit());
      requireSame(componentIdentity, "原材料价格类型", oa.getRawPriceType(), finance.getRawPriceType());
      requireSame(componentIdentity, "废料价格类型", oa.getScrapPriceType(), finance.getScrapPriceType());
      requireSame(
          componentIdentity, "无废料确认", oa.getNoScrapConfirmed(), finance.getNoScrapConfirmed());
      requireDecimal(componentIdentity, "子项数量", oa.getQtyPerParent(), finance.getQtyPerParent());
      requireDecimal(componentIdentity, "毛重", oa.getGrossWeightG(), finance.getGrossWeightG());
      requireDecimal(componentIdentity, "净重", oa.getNetWeightG(), finance.getNetWeightG());
      requireDecimal(componentIdentity, "委外费", oa.getOutsourceFee(), finance.getOutsourceFee());
    }
    validateMakeTotals(settlementKey, oaItem, oaRows, "OA");
    validateMakeTotals(settlementKey, financeItem, financeRows, "财务");
    return new MakeRowsPair(oaMap, financeMap);
  }

  private List<MakePartPriceCalcRow> loadMakeRows(
      PricePrepareItem item, QuotePriceScenarioType expectedScenario) {
    if (item.getResultRefId() == null) {
      throw new IllegalStateException("制造件价格准备缺结果引用: " + item.getSettlementKey());
    }
    MakePartPriceCalcRow referenced = makePartRowMapper.selectById(item.getResultRefId());
    if (referenced == null || !StringUtils.hasText(referenced.getCalcBatchId())) {
      throw new IllegalStateException("制造件价格结果引用不存在: " + item.getResultRefId());
    }
    if (!expectedScenario.name().equals(referenced.getPriceScenarioType())) {
      throw new IllegalStateException("制造件价格结果引用场景不正确: " + item.getResultRefId());
    }
    List<MakePartPriceCalcRow> rows = makePartRowMapper.selectList(
        Wrappers.lambdaQuery(MakePartPriceCalcRow.class)
            .eq(MakePartPriceCalcRow::getCalcBatchId, referenced.getCalcBatchId())
            .eq(MakePartPriceCalcRow::getParentMaterialNo, referenced.getParentMaterialNo())
            .orderByAsc(MakePartPriceCalcRow::getId));
    if (rows == null || rows.isEmpty()) {
      throw new IllegalStateException("制造件价格结果没有组件明细: " + item.getResultRefId());
    }
    for (MakePartPriceCalcRow row : rows) {
      if (!expectedScenario.name().equals(row.getPriceScenarioType())) {
        throw new IllegalStateException("制造件价格结果混入其他场景: " + row.getId());
      }
      if (!"OK".equals(row.getStatus()) || row.getCostPrice() == null) {
        throw new IllegalStateException("制造件价格组件未就绪: " + row.getId());
      }
    }
    return rows;
  }

  private Map<String, MakePartPriceCalcRow> indexMakeRows(
      List<MakePartPriceCalcRow> rows, String label, String settlementKey) {
    Map<String, MakePartPriceCalcRow> indexed = new LinkedHashMap<>();
    for (MakePartPriceCalcRow row : rows) {
      String child = requireText(row.getChildMaterialNo(), label + "制造件子材料");
      String identity = child + "|" + text(row.getScrapCode());
      if (indexed.putIfAbsent(identity, row) != null) {
        throw new IllegalStateException(label + "制造件组件键重复: " + settlementKey + "/" + identity);
      }
    }
    return indexed;
  }

  private void validateMakeTotals(
      String settlementKey,
      PricePrepareItem item,
      List<MakePartPriceCalcRow> rows,
      String label) {
    BigDecimal unitTotal = BigDecimal.ZERO;
    for (MakePartPriceCalcRow row : rows) {
      unitTotal = unitTotal.add(row.getCostPrice());
      requireDecimal(
          settlementKey,
          label + "制造件父项总单价",
          item.getUnitPrice(),
          row.getParentTotalCostPrice());
    }
    requireDecimal(settlementKey, label + "制造件组件单价之和", item.getUnitPrice(), money(unitTotal));
  }

  private void collectMakeLinkedCodes(MakeRowsPair pair, Set<String> linkedCodes) {
    for (MakePartPriceCalcRow row : pair.oaRows().values()) {
      if (isLinkedPriceType(row.getRawPriceType())) {
        linkedCodes.add(requireText(row.getChildMaterialNo(), "制造件联动价子材料"));
      }
      if (isLinkedPriceType(row.getScrapPriceType()) && StringUtils.hasText(row.getScrapCode())) {
        linkedCodes.add(row.getScrapCode().trim());
      }
    }
  }

  private Map<String, LinkedEvidence> loadLinkedEvidence(
      PricePrepareBatch batch, Set<String> materialCodes, String factorSource) {
    if (materialCodes.isEmpty()) {
      return Map.of();
    }
    List<PriceLinkedCalcItem> rows = linkedCalcItemMapper.selectList(
        Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
            .eq(PriceLinkedCalcItem::getCalcScene, LinkedPriceCalcScene.QUOTE.getCode())
            .eq(PriceLinkedCalcItem::getFactorSource, factorSource)
            .eq(PriceLinkedCalcItem::getOaNo, batch.getOaNo())
            .eq(PriceLinkedCalcItem::getBusinessUnitType, batch.getBusinessUnitType())
            .eq(PriceLinkedCalcItem::getPricingMonth, batch.getPeriodMonth())
            .eq(PriceLinkedCalcItem::getPriceAsOfTime, batch.getPriceAsOfTime())
            .eq(PriceLinkedCalcItem::getCalcStatus, "OK")
            .in(PriceLinkedCalcItem::getItemCode, materialCodes)
            .orderByDesc(PriceLinkedCalcItem::getId));
    Map<String, LinkedEvidence> indexed = new LinkedHashMap<>();
    if (rows != null) {
      for (PriceLinkedCalcItem row : rows) {
        String code = requireText(row.getItemCode(), "联动价结果料号");
        if (indexed.putIfAbsent(code, parseLinkedEvidence(row)) != null) {
          throw new IllegalStateException("同一场景联动价结果重复: " + factorSource + "/" + code);
        }
      }
    }
    for (String code : materialCodes) {
      if (!indexed.containsKey(code)) {
        throw new IllegalStateException("缺联动价计算证据: " + factorSource + "/" + code);
      }
    }
    return indexed;
  }

  private LinkedEvidence parseLinkedEvidence(PriceLinkedCalcItem row) {
    if (!StringUtils.hasText(row.getTraceJson())) {
      throw new IllegalStateException("联动价结果缺trace: " + row.getId());
    }
    try {
      JsonNode trace = objectMapper.readTree(row.getTraceJson());
      if (trace == null || !trace.isObject() || trace.has("error")) {
        throw new IllegalStateException("联动价trace无效: " + row.getId());
      }
      String formula = textNode(trace, "normalizedExpr");
      if (!StringUtils.hasText(formula)) {
        formula = textNode(trace, "rawExpr");
      }
      if (!StringUtils.hasText(formula)) {
        throw new IllegalStateException("联动价trace缺公式: " + row.getId());
      }
      Map<String, BigDecimal> variables = decimalVariables(trace.get("variables"), row.getId());
      Map<String, String> sources = variableSources(trace.get("variableDetails"), row.getId());
      return new LinkedEvidence(row, formula.trim(), variables, sources, trace);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("联动价trace不是合法JSON: " + row.getId(), ex);
    }
  }

  private Map<String, BigDecimal> decimalVariables(JsonNode node, Long refId) {
    Map<String, BigDecimal> variables = new LinkedHashMap<>();
    if (node == null || node.isNull()) {
      return variables;
    }
    if (!node.isObject()) {
      throw new IllegalStateException("联动价trace.variables格式错误: " + refId);
    }
    node.fields().forEachRemaining(entry -> {
      String code = variableCode(entry.getKey());
      JsonNode value = entry.getValue();
      if (value == null || !value.isNumber()) {
        throw new IllegalStateException("联动价变量不是数字: " + entry.getKey());
      }
      if (variables.putIfAbsent(code, value.decimalValue()) != null) {
        throw new IllegalStateException("联动价变量重复: " + entry.getKey());
      }
    });
    return variables;
  }

  private Map<String, String> variableSources(JsonNode node, Long refId) {
    Map<String, String> sources = new LinkedHashMap<>();
    if (node == null || node.isNull()) {
      return sources;
    }
    if (!node.isArray()) {
      throw new IllegalStateException("联动价trace.variableDetails格式错误: " + refId);
    }
    for (JsonNode detail : node) {
      if (detail == null || !detail.isObject() || !detail.hasNonNull("code")) {
        continue;
      }
      String code = variableCode(detail.get("code").asText());
      String source = detail.hasNonNull("source") ? detail.get("source").asText().trim() : "";
      if (sources.putIfAbsent(code, source) != null) {
        throw new IllegalStateException("联动价变量来源重复: " + code);
      }
    }
    return sources;
  }

  private boolean validateLinkedPair(
      String context,
      LinkedEvidence oa,
      LinkedEvidence finance,
      Set<String> cuVariableCodes) {
    requireSame(context, "联动价公式", oa.formula(), finance.formula());
    boolean oaHasCu = containsCu(oa.variables(), cuVariableCodes);
    boolean financeHasCu = containsCu(finance.variables(), cuVariableCodes);
    if (oaHasCu != financeHasCu) {
      throw new IllegalStateException("OA与财务联动价Cu变量结构不一致: " + context);
    }
    Map<String, BigDecimal> oaNonCu = withoutCu(oa.variables(), cuVariableCodes);
    Map<String, BigDecimal> financeNonCu = withoutCu(finance.variables(), cuVariableCodes);
    if (!oaNonCu.keySet().equals(financeNonCu.keySet())) {
      throw new IllegalStateException("OA与财务联动价非Cu变量范围不一致: " + context);
    }
    for (String code : oaNonCu.keySet()) {
      requireDecimal(context, "非Cu变量" + code, oaNonCu.get(code), financeNonCu.get(code));
    }
    Map<String, String> oaSources = withoutCuText(oa.variableSources(), cuVariableCodes);
    Map<String, String> financeSources = withoutCuText(finance.variableSources(), cuVariableCodes);
    if (!oaSources.equals(financeSources)) {
      throw new IllegalStateException("OA与财务联动价非Cu变量来源不一致: " + context);
    }
    return oaHasCu;
  }

  private void validateScenarioCu(
      String label,
      Map<String, LinkedEvidence> evidence,
      BigDecimal versionCuPrice,
      Set<String> cuVariableCodes) {
    BigDecimal scenarioCu = null;
    for (Map.Entry<String, LinkedEvidence> entry : evidence.entrySet()) {
      BigDecimal current = cuValue(entry.getKey(), entry.getValue().variables(), cuVariableCodes);
      if (current == null) {
        continue;
      }
      if (scenarioCu == null) {
        scenarioCu = current;
      } else if (scenarioCu.compareTo(current) != 0) {
        throw new IllegalStateException(label + "场景不同联动价使用了不同Cu值: " + entry.getKey());
      }
    }
    if (versionCuPrice != null && scenarioCu != null) {
      requireDecimal(label + "场景", "成本版本Cu价格", versionCuPrice, scenarioCu);
    }
  }

  private QuoteCuMaterialDiffItem buildSettlementRow(
      QuoteCostRunVersion version,
      PricePrepareBatch oaBatch,
      PricePrepareBatch financeBatch,
      String settlementKey,
      PricePrepareItem oa,
      PricePrepareItem finance,
      Map<String, LinkedEvidence> oaLinked,
      Map<String, LinkedEvidence> financeLinked,
      Set<String> cuVariableCodes) {
    BigDecimal diff = difference(oa.getAmount(), finance.getAmount());
    boolean cuAffected = false;
    LinkedEvidence oaEvidence = null;
    LinkedEvidence financeEvidence = null;
    if (REF_LINKED_PRICE.equals(oa.getResultRefType())) {
      String code = requireText(oa.getMaterialCode(), "联动价料号");
      oaEvidence = requireEvidence(oaLinked, code, "OA");
      financeEvidence = requireEvidence(financeLinked, code, "财务");
      requireSame(
          settlementKey, "OA联动价结果引用", oa.getResultRefId(), oaEvidence.row().getId());
      requireSame(
          settlementKey,
          "财务联动价结果引用",
          finance.getResultRefId(),
          financeEvidence.row().getId());
      requireDecimal(settlementKey, "OA联动价单价", oa.getUnitPrice(), oaEvidence.row().getPartUnitPrice());
      requireDecimal(
          settlementKey,
          "财务联动价单价",
          finance.getUnitPrice(),
          financeEvidence.row().getPartUnitPrice());
      cuAffected =
          validateLinkedPair(settlementKey, oaEvidence, financeEvidence, cuVariableCodes);
    }
    if (diff.signum() != 0 && !cuAffected) {
      throw new IllegalStateException("非Cu结算行出现非零差异: " + settlementKey);
    }
    QuoteCuMaterialDiffItem row = baseDiffRow(version, settlementKey, oa, finance);
    row.setDetailLevel(DETAIL_SETTLEMENT);
    row.setContributesToAdjustment(1);
    row.setParentMaterialCode(null);
    row.setFinanceUnitPrice(money(finance.getUnitPrice()));
    row.setOaUnitPrice(money(oa.getUnitPrice()));
    row.setFinanceAmount(money(finance.getAmount()));
    row.setOaAmount(money(oa.getAmount()));
    row.setDiffAmount(diff);
    row.setCuAffected(cuAffected ? 1 : 0);
    row.setPriceFormulaRefType(finance.getResultRefType());
    row.setPriceFormulaRefId(finance.getResultRefId());
    row.setTraceJson(json(Map.of(
        "detailLevel", DETAIL_SETTLEMENT,
        "oaPrepareNo", oaBatch.getPrepareNo(),
        "financePrepareNo", financeBatch.getPrepareNo(),
        "formula", "diff_amount = oa_amount - finance_amount",
        "oaLinkedTrace", traceOrNull(oaEvidence),
        "financeLinkedTrace", traceOrNull(financeEvidence))));
    return row;
  }

  private List<QuoteCuMaterialDiffItem> buildMakeRows(
      QuoteCostRunVersion version,
      PricePrepareBatch oaBatch,
      PricePrepareBatch financeBatch,
      String settlementKey,
      PricePrepareItem oaItem,
      PricePrepareItem financeItem,
      MakeRowsPair pair,
      Map<String, LinkedEvidence> oaLinked,
      Map<String, LinkedEvidence> financeLinked,
      Set<String> cuVariableCodes) {
    List<QuoteCuMaterialDiffItem> components = new ArrayList<>();
    boolean settlementCuAffected = false;
    BigDecimal oaComponentTotal = BigDecimal.ZERO;
    BigDecimal financeComponentTotal = BigDecimal.ZERO;
    BigDecimal componentDiffTotal = BigDecimal.ZERO;
    for (String identity : pair.oaRows().keySet()) {
      MakePartPriceCalcRow oa = pair.oaRows().get(identity);
      MakePartPriceCalcRow finance = pair.financeRows().get(identity);
      boolean rawCu = validateMakeMaterialPrice(
          identity + "/原材料",
          oa.getChildMaterialNo(),
          oa.getRawPriceType(),
          oa.getRawUnitPrice(),
          finance.getRawUnitPrice(),
          oaLinked,
          financeLinked,
          cuVariableCodes);
      boolean scrapCu = validateMakeMaterialPrice(
          identity + "/废料",
          oa.getScrapCode(),
          oa.getScrapPriceType(),
          oa.getScrapUnitPrice(),
          finance.getScrapUnitPrice(),
          oaLinked,
          financeLinked,
          cuVariableCodes);
      boolean componentCuAffected = rawCu || scrapCu;
      BigDecimal oaAmount = money(oaItem.getQuantity().multiply(oa.getCostPrice()));
      BigDecimal financeAmount = money(financeItem.getQuantity().multiply(finance.getCostPrice()));
      BigDecimal diff = difference(oaAmount, financeAmount);
      if (diff.signum() != 0 && !componentCuAffected) {
        throw new IllegalStateException("非Cu制造件组件出现非零差异: " + identity);
      }
      String componentKey = PricePrepareSettlementKeyGenerator.componentKey(
          settlementKey,
          oa.getParentMaterialNo(),
          oa.getChildMaterialNo(),
          identity);
      QuoteCuMaterialDiffItem component = baseDiffRow(version, componentKey, oaItem, financeItem);
      component.setParentSettlementKey(settlementKey);
      component.setDetailLevel(DETAIL_RAW_COMPONENT);
      component.setContributesToAdjustment(0);
      component.setParentMaterialCode(oa.getParentMaterialNo());
      component.setMaterialCode(oa.getChildMaterialNo());
      component.setMaterialName(oa.getChildMaterialName());
      component.setItemType(DETAIL_RAW_COMPONENT);
      component.setFinanceUnitPrice(money(finance.getCostPrice()));
      component.setOaUnitPrice(money(oa.getCostPrice()));
      component.setFinanceAmount(financeAmount);
      component.setOaAmount(oaAmount);
      component.setDiffAmount(diff);
      component.setCuAffected(componentCuAffected ? 1 : 0);
      component.setPriceFormulaRefType("MAKE_PART_COMPONENT");
      component.setPriceFormulaRefId(finance.getId());
      component.setTraceJson(json(makeComponentTrace(
          oaBatch,
          financeBatch,
          oa,
          finance,
          rawCu,
          scrapCu,
          oaLinked,
          financeLinked)));
      components.add(component);
      settlementCuAffected |= componentCuAffected;
      oaComponentTotal = oaComponentTotal.add(oaAmount);
      financeComponentTotal = financeComponentTotal.add(financeAmount);
      componentDiffTotal = componentDiffTotal.add(diff);
    }
    oaComponentTotal = money(oaComponentTotal);
    financeComponentTotal = money(financeComponentTotal);
    componentDiffTotal = money(componentDiffTotal);
    requireDecimal(settlementKey, "OA制造件组件金额之和", money(oaItem.getAmount()), oaComponentTotal);
    requireDecimal(
        settlementKey,
        "财务制造件组件金额之和",
        money(financeItem.getAmount()),
        financeComponentTotal);
    BigDecimal settlementDiff = difference(oaItem.getAmount(), financeItem.getAmount());
    requireDecimal(settlementKey, "制造件父项差异与组件解释之和", settlementDiff, componentDiffTotal);
    if (settlementDiff.signum() != 0 && !settlementCuAffected) {
      throw new IllegalStateException("非Cu制造件结算行出现非零差异: " + settlementKey);
    }

    QuoteCuMaterialDiffItem settlement = baseDiffRow(version, settlementKey, oaItem, financeItem);
    settlement.setDetailLevel(DETAIL_SETTLEMENT);
    settlement.setContributesToAdjustment(1);
    settlement.setParentMaterialCode(oaItem.getMaterialCode());
    settlement.setFinanceUnitPrice(money(financeItem.getUnitPrice()));
    settlement.setOaUnitPrice(money(oaItem.getUnitPrice()));
    settlement.setFinanceAmount(money(financeItem.getAmount()));
    settlement.setOaAmount(money(oaItem.getAmount()));
    settlement.setDiffAmount(settlementDiff);
    settlement.setCuAffected(settlementCuAffected ? 1 : 0);
    settlement.setPriceFormulaRefType(REF_MAKE_PART);
    settlement.setPriceFormulaRefId(financeItem.getResultRefId());
    settlement.setTraceJson(json(Map.of(
        "detailLevel", DETAIL_SETTLEMENT,
        "oaPrepareNo", oaBatch.getPrepareNo(),
        "financePrepareNo", financeBatch.getPrepareNo(),
        "componentKeys", components.stream().map(QuoteCuMaterialDiffItem::getSettlementKey).toList(),
        "componentDiffTotal", componentDiffTotal,
        "contributesToAdjustment", true)));
    List<QuoteCuMaterialDiffItem> result = new ArrayList<>();
    result.add(settlement);
    result.addAll(components);
    return result;
  }

  private boolean validateMakeMaterialPrice(
      String context,
      String materialCode,
      String priceType,
      BigDecimal oaPrice,
      BigDecimal financePrice,
      Map<String, LinkedEvidence> oaLinked,
      Map<String, LinkedEvidence> financeLinked,
      Set<String> cuVariableCodes) {
    requirePresentTogether(context, oaPrice, financePrice);
    if (!StringUtils.hasText(materialCode)) {
      if (different(oaPrice, financePrice)) {
        throw new IllegalStateException("无料号的制造件价格出现差异: " + context);
      }
      return false;
    }
    if (!isLinkedPriceType(priceType)) {
      if (different(oaPrice, financePrice)) {
        throw new IllegalStateException("非Cu制造件价格出现非零差异: " + context);
      }
      return false;
    }
    String code = materialCode.trim();
    LinkedEvidence oaEvidence = requireEvidence(oaLinked, code, "OA");
    LinkedEvidence financeEvidence = requireEvidence(financeLinked, code, "财务");
    requireDecimal(context, "OA联动单价", oaPrice, oaEvidence.row().getPartUnitPrice());
    requireDecimal(context, "财务联动单价", financePrice, financeEvidence.row().getPartUnitPrice());
    boolean cuAffected =
        validateLinkedPair(context, oaEvidence, financeEvidence, cuVariableCodes);
    if (different(oaPrice, financePrice) && !cuAffected) {
      throw new IllegalStateException("非Cu联动价出现非零差异: " + context);
    }
    return cuAffected;
  }

  private Map<String, Object> makeComponentTrace(
      PricePrepareBatch oaBatch,
      PricePrepareBatch financeBatch,
      MakePartPriceCalcRow oa,
      MakePartPriceCalcRow finance,
      boolean rawCu,
      boolean scrapCu,
      Map<String, LinkedEvidence> oaLinked,
      Map<String, LinkedEvidence> financeLinked) {
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("detailLevel", DETAIL_RAW_COMPONENT);
    trace.put("oaPrepareNo", oaBatch.getPrepareNo());
    trace.put("financePrepareNo", financeBatch.getPrepareNo());
    trace.put("oaMakeRowId", oa.getId());
    trace.put("financeMakeRowId", finance.getId());
    trace.put("parentMaterialCode", oa.getParentMaterialNo());
    trace.put("childMaterialCode", oa.getChildMaterialNo());
    trace.put("scrapCode", oa.getScrapCode());
    trace.put("qtyPerParent", oa.getQtyPerParent());
    trace.put("grossWeightG", oa.getGrossWeightG());
    trace.put("netWeightG", oa.getNetWeightG());
    trace.put("oaRawUnitPrice", oa.getRawUnitPrice());
    trace.put("financeRawUnitPrice", finance.getRawUnitPrice());
    trace.put("oaScrapUnitPrice", oa.getScrapUnitPrice());
    trace.put("financeScrapUnitPrice", finance.getScrapUnitPrice());
    trace.put("rawCuAffected", rawCu);
    trace.put("scrapCuAffected", scrapCu);
    if (rawCu) {
      trace.put("oaRawLinkedTrace", requireEvidence(oaLinked, oa.getChildMaterialNo(), "OA").trace());
      trace.put(
          "financeRawLinkedTrace",
          requireEvidence(financeLinked, finance.getChildMaterialNo(), "财务").trace());
    }
    if (scrapCu && StringUtils.hasText(oa.getScrapCode())) {
      trace.put("oaScrapLinkedTrace", requireEvidence(oaLinked, oa.getScrapCode(), "OA").trace());
      trace.put(
          "financeScrapLinkedTrace",
          requireEvidence(financeLinked, finance.getScrapCode(), "财务").trace());
    }
    return trace;
  }

  private QuoteCuMaterialDiffItem baseDiffRow(
      QuoteCostRunVersion version,
      String settlementKey,
      PricePrepareItem oa,
      PricePrepareItem finance) {
    QuoteCuMaterialDiffItem row = new QuoteCuMaterialDiffItem();
    row.setCostRunVersionId(version.getId());
    row.setCostRunNo(version.getCostRunNo());
    row.setSettlementKey(settlementKey);
    row.setBomRowId(oa.getBomRowId());
    row.setTopProductCode(oa.getTopProductCode());
    row.setMaterialCode(oa.getMaterialCode());
    row.setMaterialName(oa.getMaterialName());
    row.setItemType(oa.getItemType());
    row.setQuantity(money(oa.getQuantity()));
    row.setFinancePrepareItemId(finance.getId());
    row.setOaPrepareItemId(oa.getId());
    row.setBusinessUnitType(version.getBusinessUnitType());
    return row;
  }

  private LinkedEvidence requireEvidence(
      Map<String, LinkedEvidence> evidence, String materialCode, String label) {
    String code = requireText(materialCode, label + "联动价料号");
    LinkedEvidence result = evidence.get(code);
    if (result == null) {
      throw new IllegalStateException("缺" + label + "联动价证据: " + code);
    }
    return result;
  }

  private Object traceOrNull(LinkedEvidence evidence) {
    return evidence == null ? Map.of() : evidence.trace();
  }

  private Set<String> resolveCuVariableCodes(
      Map<String, LinkedEvidence> oaEvidence,
      Map<String, LinkedEvidence> financeEvidence) {
    Set<String> result = new LinkedHashSet<>();
    result.add(CU_VARIABLE_CODE);
    Set<Long> factorIdentityIds = new LinkedHashSet<>();
    collectFactorIdentityIds(oaEvidence, factorIdentityIds);
    collectFactorIdentityIds(financeEvidence, factorIdentityIds);
    if (factorIdentityIds.isEmpty()) {
      return result;
    }
    List<FactorQuoteBaseMapping> mappings =
        factorQuoteBaseMappingMapper.selectList(
            Wrappers.lambdaQuery(FactorQuoteBaseMapping.class)
                .in(FactorQuoteBaseMapping::getFactorIdentityId, factorIdentityIds)
                .eq(FactorQuoteBaseMapping::getEnabled, 1));
    if (mappings == null) {
      return result;
    }
    for (FactorQuoteBaseMapping mapping : mappings) {
      if (mapping == null || mapping.getFactorIdentityId() == null) {
        continue;
      }
      if (CU_VARIABLE_CODE.equals(variableCode(mapping.getVariableCode()))
          || "copper_price".equals(variableCode(mapping.getQuoteFieldCode()))) {
        result.add(FACTOR_IDENTITY_PREFIX + mapping.getFactorIdentityId());
      }
    }
    return result;
  }

  private void collectFactorIdentityIds(
      Map<String, LinkedEvidence> evidence, Set<Long> target) {
    if (evidence == null || evidence.isEmpty()) {
      return;
    }
    for (LinkedEvidence linkedEvidence : evidence.values()) {
      if (linkedEvidence == null || linkedEvidence.variables() == null) {
        continue;
      }
      for (String code : linkedEvidence.variables().keySet()) {
        Long factorIdentityId = factorIdentityId(code);
        if (factorIdentityId != null) {
          target.add(factorIdentityId);
        }
      }
    }
  }

  private Long factorIdentityId(String code) {
    String normalized = variableCode(code);
    if (!normalized.startsWith(FACTOR_IDENTITY_PREFIX)) {
      return null;
    }
    try {
      return Long.valueOf(normalized.substring(FACTOR_IDENTITY_PREFIX.length()));
    } catch (NumberFormatException ignored) {
      return null;
    }
  }

  private boolean containsCu(
      Map<String, BigDecimal> variables, Set<String> cuVariableCodes) {
    return variables.keySet().stream().anyMatch(cuVariableCodes::contains);
  }

  private BigDecimal cuValue(
      String context,
      Map<String, BigDecimal> variables,
      Set<String> cuVariableCodes) {
    BigDecimal result = null;
    for (Map.Entry<String, BigDecimal> entry : variables.entrySet()) {
      if (!cuVariableCodes.contains(entry.getKey())) {
        continue;
      }
      if (result != null && result.compareTo(entry.getValue()) != 0) {
        throw new IllegalStateException("同一联动价存在多个不同Cu变量值: " + context);
      }
      result = entry.getValue();
    }
    return result;
  }

  private Map<String, BigDecimal> withoutCu(
      Map<String, BigDecimal> source, Set<String> cuVariableCodes) {
    Map<String, BigDecimal> result = new LinkedHashMap<>();
    source.forEach((key, value) -> {
      if (!cuVariableCodes.contains(key)) {
        result.put(key, value);
      }
    });
    return result;
  }

  private Map<String, String> withoutCuText(
      Map<String, String> source, Set<String> cuVariableCodes) {
    Map<String, String> result = new LinkedHashMap<>();
    source.forEach((key, value) -> {
      if (!cuVariableCodes.contains(key)) {
        result.put(key, value);
      }
    });
    return result;
  }

  private String variableCode(String code) {
    return requireText(code, "联动价变量编码").toLowerCase(Locale.ROOT);
  }

  private String textNode(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? node.get(field).asText() : null;
  }

  private boolean isLinkedPriceType(String priceType) {
    if (!StringUtils.hasText(priceType)) {
      return false;
    }
    String normalized = priceType.trim();
    if ("LINKED".equalsIgnoreCase(normalized) || REF_LINKED_PRICE.equalsIgnoreCase(normalized)) {
      return true;
    }
    return PriceTypeEnum.fromDbText(normalized).orElse(null) == PriceTypeEnum.LINKED;
  }

  private void requirePresentTogether(String context, Object left, Object right) {
    if ((left == null) != (right == null)) {
      throw new IllegalStateException("OA与财务输入空值状态不一致: " + context);
    }
  }

  private void requireSame(String key, String field, Object left, Object right) {
    if (!Objects.equals(normalizeValue(left), normalizeValue(right))) {
      throw new IllegalStateException("OA与财务输入不一致: key=" + key + ", field=" + field);
    }
  }

  private Object normalizeValue(Object value) {
    if (value instanceof String text) {
      return text.trim();
    }
    return value;
  }

  private void requireDecimal(String key, String field, BigDecimal left, BigDecimal right) {
    if (left == null ? right == null : right != null && left.compareTo(right) == 0) {
      return;
    }
    throw new IllegalStateException("OA与财务数值不一致: key=" + key + ", field=" + field);
  }

  private boolean different(BigDecimal left, BigDecimal right) {
    return left == null ? right != null : right == null || left.compareTo(right) != 0;
  }

  private BigDecimal difference(BigDecimal oaAmount, BigDecimal financeAmount) {
    return money(oaAmount.subtract(financeAmount));
  }

  private BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("Cu材料费差异trace序列化失败", ex);
    }
  }

  private int value(Integer value) {
    return value == null ? 0 : value;
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

  private record MakeRowsPair(
      Map<String, MakePartPriceCalcRow> oaRows,
      Map<String, MakePartPriceCalcRow> financeRows) {}

  private record LinkedEvidence(
      PriceLinkedCalcItem row,
      String formula,
      Map<String, BigDecimal> variables,
      Map<String, String> variableSources,
      JsonNode trace) {}
}
