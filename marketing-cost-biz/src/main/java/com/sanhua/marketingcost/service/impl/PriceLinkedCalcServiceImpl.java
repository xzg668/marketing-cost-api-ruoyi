package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.LinkedPriceVariableContext;
import com.sanhua.marketingcost.dto.PriceLinkedCalcRow;
import com.sanhua.marketingcost.dto.PriceLinkedCalcTraceResponse;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse.VariableDetail;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import com.sanhua.marketingcost.enums.LinkedPriceFactorSource;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.service.PriceLinkedCalcService;
import com.sanhua.marketingcost.service.PriceLinkedFormulaPreviewService;
import com.sanhua.marketingcost.service.pricing.SupplierPriceReviewContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 联动结果查询和统一公式计算；报价入口负责范围选择、复用校验和持久化。 */
@Service
public class PriceLinkedCalcServiceImpl implements PriceLinkedCalcService {
  private static final Logger log = LoggerFactory.getLogger(PriceLinkedCalcServiceImpl.class);
  private final PriceLinkedCalcItemMapper priceLinkedCalcItemMapper;
  private final PriceLinkedItemMapper priceLinkedItemMapper;
  private final OaFormMapper oaFormMapper;
  private final ObjectMapper objectMapper;
  private final PriceLinkedFormulaPreviewService previewService;

  public PriceLinkedCalcServiceImpl(
      PriceLinkedCalcItemMapper priceLinkedCalcItemMapper,
      PriceLinkedItemMapper priceLinkedItemMapper,
      OaFormMapper oaFormMapper,
      ObjectMapper objectMapper,
      PriceLinkedFormulaPreviewService previewService) {
    this.priceLinkedCalcItemMapper = priceLinkedCalcItemMapper;
    this.priceLinkedItemMapper = priceLinkedItemMapper;
    this.oaFormMapper = oaFormMapper;
    this.objectMapper = objectMapper;
    this.previewService = previewService;
  }

  @Override
  public Page<PriceLinkedCalcRow> resultPage(
      String oaNo,
      String customer,
      String businessUnitType,
      String itemCode,
      String calcScene,
      String pricingMonth,
      String calcStatus,
      String factorSource,
      int page,
      int pageSize) {
    // LPE-10：结果查询页只追溯已落库的 calc_item，不能像旧 calc 列表一样合成/补写记录。
    var query =
        Wrappers.lambdaQuery(PriceLinkedCalcItem.class)
            .eq(PriceLinkedCalcItem::getSourceKind, "PUBLIC");
    if (StringUtils.hasText(oaNo)) {
      query.eq(PriceLinkedCalcItem::getOaNo, oaNo.trim());
    }
    if (StringUtils.hasText(businessUnitType)) {
      query.eq(PriceLinkedCalcItem::getBusinessUnitType, businessUnitType.trim());
    }
    if (StringUtils.hasText(itemCode)) {
      query.like(PriceLinkedCalcItem::getItemCode, itemCode.trim());
    }
    if (StringUtils.hasText(calcScene)) {
      query.eq(PriceLinkedCalcItem::getCalcScene, calcScene.trim());
    }
    if (StringUtils.hasText(pricingMonth)) {
      query.eq(PriceLinkedCalcItem::getPricingMonth, pricingMonth.trim());
    }
    if (StringUtils.hasText(calcStatus)) {
      query.eq(PriceLinkedCalcItem::getCalcStatus, calcStatus.trim());
    }
    if (StringUtils.hasText(factorSource)) {
      query.eq(PriceLinkedCalcItem::getFactorSource, factorSource.trim());
    }

    Set<String> customerOaNos = findOaNosByCustomer(customer);
    if (customerOaNos != null) {
      if (customerOaNos.isEmpty()) {
        Page<PriceLinkedCalcRow> empty = new Page<>(page, pageSize);
        empty.setTotal(0);
        empty.setRecords(List.of());
        return empty;
      }
      query.in(PriceLinkedCalcItem::getOaNo, customerOaNos);
    }
    query.orderByDesc(PriceLinkedCalcItem::getUpdatedAt).orderByDesc(PriceLinkedCalcItem::getId);

    Page<PriceLinkedCalcItem> calcPage =
        priceLinkedCalcItemMapper.selectPage(new Page<>(page, pageSize), query);
    List<PriceLinkedCalcItem> calcItems = calcPage.getRecords();
    Map<String, OaForm> oaMap = fetchOaMap(calcItems);
    Map<Long, PriceLinkedItem> linkedItemMap = fetchLinkedItemMapForResults(calcItems);

    List<PriceLinkedCalcRow> rows = new ArrayList<>(calcItems.size());
    for (PriceLinkedCalcItem calcItem : calcItems) {
      PriceLinkedCalcRow row = new PriceLinkedCalcRow();
      row.setCalcId(calcItem.getId());
      row.setOaNo(calcItem.getOaNo());
      row.setBusinessUnitType(calcItem.getBusinessUnitType());
      row.setItemCode(calcItem.getItemCode());
      row.setShapeAttr(calcItem.getShapeAttr());
      row.setBomQty(calcItem.getBomQty());
      row.setPartUnitPrice(calcItem.getPartUnitPrice());
      row.setPartAmount(calcItem.getPartAmount());
      row.setCalcScene(calcItem.getCalcScene());
      row.setPricingMonth(calcItem.getPricingMonth());
      row.setFactorSource(calcItem.getFactorSource());
      row.setCalcStatus(calcItem.getCalcStatus());
      row.setCalcMessage(calcItem.getCalcMessage());
      row.setHasTrace(StringUtils.hasText(calcItem.getTraceJson()));
      row.setVariableSourceSummary(countVariableSources(calcItem.getTraceJson()));
      row.setUpdatedAt(calcItem.getUpdatedAt());

      OaForm oaForm = oaMap.get(trimToEmpty(calcItem.getOaNo()));
      if (oaForm != null) {
        row.setCustomer(oaForm.getCustomer());
      }
      row.setSupplierName(calcItem.getSupplierName());
      // 旧结果可能未保存规则ID；只展示当时的结果和追溯，不按当前料号猜配规则。
      Long sourceRuleId = calcItem.getSourcePriceRecordId();
      PriceLinkedItem linkedItem = sourceRuleId == null ? null : linkedItemMap.get(sourceRuleId);
      if (linkedItem != null) {
        row.setMaterialName(linkedItem.getMaterialName());
        row.setSupplierName(linkedItem.getSupplierName());
        row.setFormulaExpr(linkedItem.getFormulaExpr());
        row.setFormulaExprCn(linkedItem.getFormulaExprCn());
      }
      if (StringUtils.hasText(calcItem.getTraceJson())) {
        try {
          String recordedExpression =
              objectMapper.readTree(calcItem.getTraceJson()).path("rawExpr").asText(null);
          if (StringUtils.hasText(recordedExpression)) row.setFormulaExpr(recordedExpression);
        } catch (JsonProcessingException invalidHistory) {
          log.debug("联动结果底稿无法读取: calcId={}", calcItem.getId());
        }
      }
      rows.add(row);
    }

    Page<PriceLinkedCalcRow> result = new Page<>(page, pageSize);
    result.setTotal(calcPage.getTotal());
    result.setRecords(rows);
    return result;
  }

  @Override
  public PriceLinkedCalcTraceResponse getTrace(Long id) {
    if (id == null) {
      return null;
    }
    PriceLinkedItem linkedItem = priceLinkedItemMapper.selectById(id);
    if (linkedItem == null) {
      return null;
    }
    if (OriginalLinkedRuleSnapshot.VERSION.equals(linkedItem.getExpressionVersion())) {
      throw new IllegalArgumentException("原稿联动价请读取财务有效价详情，不支持通过旧依据接口重新计算");
    }

    // 必须使用按 id 取到的当前行作为上下文。同料号存在多个供应商时，如果重新按
    // materialCode + pricingMonth 查询，会误取最新供应商行并串用其加工费。
    PriceLinkedFormulaPreviewResponse preview =
        previewService.previewForRefresh(linkedItem, Map.of());

    // 前端 priceLinkedResultUtils.buildTraceTimeline 按平铺字段读；把 preview 结果摊平成 map
    Map<String, Object> flat = new LinkedHashMap<>();
    flat.put("rawExpr", linkedItem.getFormulaExpr());
    flat.put("normalizedExpr", preview.getNormalizedExpr());
    Map<String, BigDecimal> variables = new LinkedHashMap<>();
    if (preview.getVariables() != null) {
      for (VariableDetail detail : preview.getVariables()) {
        if (detail != null && detail.getCode() != null) {
          variables.put(detail.getCode(), detail.getValue());
        }
      }
    }
    flat.put("variables", variables);
    putVariableDetails(flat, preview.getVariables());
    flat.put("result", preview.getResult());
    if (preview.getInputSnapshotJson() != null) {
      flat.put("inputSnapshot", preview.getInputSnapshotJson());
      flat.put("calcFingerprint", preview.getCalcFingerprint());
      flat.put("calculatorVersion", preview.getCalculatorVersion());
    }
    if (StringUtils.hasText(preview.getError())) {
      flat.put("error", preview.getError());
    }

    String traceJson = null;
    try {
      traceJson = objectMapper.writeValueAsString(flat);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("无法序列化联动计算依据，规则=" + id, e);
    }
    return new PriceLinkedCalcTraceResponse(id, traceJson);
  }

  @Override
  public PriceLinkedCalcTraceResponse getCalcTrace(Long calcId) {
    if (calcId == null) return null;
    PriceLinkedCalcItem item = priceLinkedCalcItemMapper.selectById(calcId);
    return item == null
        ? null
        : new PriceLinkedCalcTraceResponse(item.getId(), item.getTraceJson());
  }

  private Set<String> findOaNosByCustomer(String customer) {
    if (!StringUtils.hasText(customer)) {
      return null;
    }
    List<OaForm> forms =
        oaFormMapper.selectList(
            Wrappers.lambdaQuery(OaForm.class).like(OaForm::getCustomer, customer.trim()));
    if (forms == null || forms.isEmpty()) {
      return Set.of();
    }
    Set<String> oaNos = new LinkedHashSet<>();
    for (OaForm form : forms) {
      if (form != null && StringUtils.hasText(form.getOaNo())) {
        oaNos.add(form.getOaNo().trim());
      }
    }
    return oaNos;
  }

  private Map<String, OaForm> fetchOaMap(List<PriceLinkedCalcItem> calcItems) {
    if (calcItems == null || calcItems.isEmpty()) {
      return Map.of();
    }
    Set<String> oaNos = new LinkedHashSet<>();
    for (PriceLinkedCalcItem item : calcItems) {
      if (item != null && StringUtils.hasText(item.getOaNo())) {
        oaNos.add(item.getOaNo().trim());
      }
    }
    if (oaNos.isEmpty()) {
      return Map.of();
    }
    List<OaForm> forms =
        oaFormMapper.selectList(Wrappers.lambdaQuery(OaForm.class).in(OaForm::getOaNo, oaNos));
    Map<String, OaForm> map = new HashMap<>();
    for (OaForm form : forms) {
      if (form != null && StringUtils.hasText(form.getOaNo())) {
        map.put(form.getOaNo().trim(), form);
      }
    }
    return map;
  }

  private Map<Long, PriceLinkedItem> fetchLinkedItemMapForResults(List<PriceLinkedCalcItem> items) {
    Set<Long> ids = new LinkedHashSet<>();
    for (PriceLinkedCalcItem item : items) {
      if (item.getSourcePriceRecordId() != null) ids.add(item.getSourcePriceRecordId());
    }
    if (ids.isEmpty()) return Map.of();
    Map<Long, PriceLinkedItem> rules = new HashMap<>();
    for (PriceLinkedItem item :
        priceLinkedItemMapper.selectList(
            Wrappers.lambdaQuery(PriceLinkedItem.class)
                .in(PriceLinkedItem::getId, ids)
                .eq(PriceLinkedItem::getSourceKind, "PUBLIC"))) {
      rules.put(item.getId(), item);
    }
    return rules;
  }

  private String trimToEmpty(String value) {
    return StringUtils.hasText(value) ? value.trim() : "";
  }

  @SuppressWarnings("unchecked")
  private Map<String, Integer> countVariableSources(String traceJson) {
    if (!StringUtils.hasText(traceJson)) {
      return Map.of();
    }
    try {
      Map<String, Object> trace = objectMapper.readValue(traceJson, Map.class);
      Object details = trace.get("variableDetails");
      if (!(details instanceof List<?> rows)) {
        return Map.of();
      }
      Map<String, Integer> summary = new LinkedHashMap<>();
      for (Object rowObj : rows) {
        if (!(rowObj instanceof Map<?, ?> row)) {
          continue;
        }
        Object sourceObj = row.get("source");
        if (sourceObj == null || !StringUtils.hasText(sourceObj.toString())) {
          continue;
        }
        String source = sourceObj.toString();
        summary.put(source, summary.getOrDefault(source, 0) + 1);
      }
      return summary;
    } catch (JsonProcessingException e) {
      log.debug("trace JSON 变量来源统计失败: {}", e.getMessage());
      return Map.of();
    }
  }

  private void putVariableDetails(Map<String, Object> trace, List<VariableDetail> details) {
    if (trace == null || details == null || details.isEmpty()) {
      return;
    }
    List<Map<String, Object>> rows = new ArrayList<>();
    for (VariableDetail detail : details) {
      if (detail == null || !StringUtils.hasText(detail.getCode())) {
        continue;
      }
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("code", detail.getCode());
      row.put("name", detail.getName());
      row.put("value", detail.getValue());
      row.put("source", detail.getSource());
      rows.add(row);
    }
    if (!rows.isEmpty()) {
      // V3-11：保留 variables 扁平 map 兼容老前端，同时新增明细数组承载变量名和来源。
      trace.put("variableDetails", rows);
    }
  }

  PriceLinkedCalcItem calculateQuoteItemForEnsure(
      PriceLinkedCalcItem item,
      PriceLinkedItem rule,
      OaForm oa,
      SupplierPriceReviewContext supplierReviews) {
    return calculateQuoteItemForEnsure(
        item, rule, oa, Map.of(), LinkedPriceFactorSource.OA_LOCKED.getCode(), supplierReviews);
  }

  PriceLinkedCalcItem calculateQuoteItemForEnsure(
      PriceLinkedCalcItem item,
      PriceLinkedItem rule,
      OaForm oa,
      Map<String, BigDecimal> overrides,
      String factorSource,
      SupplierPriceReviewContext supplierReviews) {
    if (item == null) throw new IllegalArgumentException("calcItem不能为空");
    item.setCalcScene(LinkedPriceCalcScene.QUOTE.getCode());
    item.setFactorSource(
        StringUtils.hasText(factorSource)
            ? factorSource.trim()
            : LinkedPriceFactorSource.OA_LOCKED.getCode());
    return applyCalculation(item, rule, oa, overrides, null, supplierReviews);
  }

  PriceLinkedCalcItem calculateMonthlyAdjustItemForEnsure(
      PriceLinkedCalcItem item,
      PriceLinkedItem rule,
      SupplierPriceReviewContext supplierReviews) {
    if (item == null) throw new IllegalArgumentException("calcItem不能为空");
    item.setCalcScene(LinkedPriceCalcScene.MONTHLY_ADJUST.getCode());
    item.setFactorSource(LinkedPriceFactorSource.MONTHLY_FACTOR.getCode());
    return applyCalculation(item, rule, null, Map.of(), null, supplierReviews);
  }

  PriceLinkedCalcItem applyEffectiveQuotePrice(
      PriceLinkedCalcItem item, PriceLinkedItem rule, PriceLinkedFormulaPreviewResponse response) {
    return applyCalculation(item, rule, null, Map.of(), java.util.Objects.requireNonNull(response), null);
  }

  private PriceLinkedCalcItem applyCalculation(
      PriceLinkedCalcItem item,
      PriceLinkedItem rule,
      OaForm oa,
      Map<String, BigDecimal> overrides,
      PriceLinkedFormulaPreviewResponse prepared,
      SupplierPriceReviewContext supplierReviews) {
    // 重算失败时旧价格及原稿依据必须同时清空，不能保留上次成功的输入。
    item.setPartUnitPrice(null);
    item.setPartAmount(null);
    item.setInputSnapshotJson(null);
    item.setCalculatorVersion(null);
    item.setCalcFingerprint(null);
    item.setTraceJson(null);
    item.setCalcStatus("FAILED");
    item.setCalcMessage("联动计算未完成");
    FormulaOutcome outcome =
        calculateFormula(rule, item, oa, overrides, item.getFactorSource(), prepared, supplierReviews);
    writeTraceJson(item, outcome.trace());
    String error = (String) outcome.trace().get("error");
    if (StringUtils.hasText(error) || outcome.value() == null) {
      item.setCalcStatus("FAILED");
      item.setCalcMessage(StringUtils.hasText(error) ? error : "联动价计算结果为空");
    } else {
      item.setPartUnitPrice(outcome.value().setScale(6, RoundingMode.HALF_UP));
      item.setPartAmount(calculatePartAmount(item.getPartUnitPrice(), item.getBomQty()));
      item.setCalcStatus("OK");
      item.setCalcMessage(null);
    }
    return item;
  }

  private FormulaOutcome calculateFormula(
      PriceLinkedItem linkedItem,
      PriceLinkedCalcItem calcItem,
      OaForm oaForm,
      Map<String, BigDecimal> variableOverrides,
      String factorSource,
      PriceLinkedFormulaPreviewResponse prepared,
      SupplierPriceReviewContext supplierReviews) {
    Map<String, Object> trace = new LinkedHashMap<>();
    if (linkedItem == null || !StringUtils.hasText(linkedItem.getFormulaExpr())) {
      trace.put("error", "linkedItem 或 formulaExpr 为空");
      return new FormulaOutcome(null, trace);
    }
    trace.put("rawExpr", linkedItem.getFormulaExpr());
    trace.put("formulaPriceMonth", linkedItem.getPricingMonth());
    trace.put("calculationPriceMonth", calculationPricingMonth(calcItem, linkedItem));

    LinkedPriceVariableContext variableContext =
        buildVariableContext(calcItem, oaForm, trace, variableOverrides, factorSource)
            .supplierReviews(supplierReviews)
            .pricingDate(
                calcItem.getPriceAsOfTime() == null
                    ? null
                    : calcItem.getPriceAsOfTime().toLocalDate());
    OaForm contextOaForm =
        variableContext.getCalcScene() == LinkedPriceCalcScene.MONTHLY_ADJUST ? null : oaForm;

    PriceLinkedFormulaPreviewResponse resp =
        prepared != null
            ? prepared
            : previewService.previewForRefresh(linkedItem, variableContext, contextOaForm);

    if (resp == null) {
      trace.put("error", "联动价预览未返回结果");
      return new FormulaOutcome(null, trace);
    }

    if (StringUtils.hasText(resp.getInputSnapshotJson()) && calcItem != null) {
      calcItem.setInputSnapshotJson(resp.getInputSnapshotJson());
      calcItem.setCalcFingerprint(resp.getCalcFingerprint());
      calcItem.setCalculatorVersion(resp.getCalculatorVersion());
      trace.put("inputSnapshot", resp.getInputSnapshotJson());
      trace.put("calcFingerprint", resp.getCalcFingerprint());
      trace.put("calculatorVersion", resp.getCalculatorVersion());
    }
    // preview 响应 → 老 trace map（前端 UI 靠 rawExpr / normalizedExpr / variables / result / error 读）
    if (StringUtils.hasText(resp.getNormalizedExpr())) {
      trace.put("normalizedExpr", resp.getNormalizedExpr());
    }
    if (resp.getVariables() != null && !resp.getVariables().isEmpty()) {
      Map<String, BigDecimal> values = new LinkedHashMap<>();
      for (var detail : resp.getVariables()) {
        if (detail != null && detail.getCode() != null) {
          values.put(detail.getCode(), detail.getValue());
        }
      }
      trace.put("variables", values);
      putVariableDetails(trace, resp.getVariables());
    }
    // preview 有 error → 走失败分支：result=null，trace.error 记下原因（含 vat_rate 硬错等）
    if (StringUtils.hasText(resp.getError())) {
      log.warn(
          "联动公式取价失败: materialCode={}, error={}", linkedItem.getMaterialCode(), resp.getError());
      trace.put("error", resp.getError());
      return new FormulaOutcome(null, trace);
    }
    trace.put("result", resp.getResult());
    // V48 新增：联动价主表 manual_price（Excel 金标）+ 主表对应月份，方便前端弹窗对比
    // 跟 result 对照：同月份就该一致，不一致 = OA 用了别的月份基价（金属价波动了）
    if (linkedItem.getManualPrice() != null) {
      trace.put("manualPrice", linkedItem.getManualPrice());
    }
    if (StringUtils.hasText(linkedItem.getPricingMonth())) {
      trace.put("manualPriceMonth", linkedItem.getPricingMonth());
    }
    return new FormulaOutcome(resp.getResult(), trace);
  }

  private LinkedPriceVariableContext buildVariableContext(
      PriceLinkedCalcItem calcItem,
      OaForm oaForm,
      Map<String, Object> trace,
      Map<String, BigDecimal> variableOverrides,
      String factorSource) {
    String calcScene = calcItem == null ? null : calcItem.getCalcScene();
    if (LinkedPriceCalcScene.MONTHLY_ADJUST.getCode().equalsIgnoreCase(calcScene)) {
      trace.put("calcScene", LinkedPriceCalcScene.MONTHLY_ADJUST.getCode());
      trace.put("factorSource", LinkedPriceFactorSource.MONTHLY_FACTOR.getCode());
      return LinkedPriceVariableContext.monthlyAdjust()
          .pricingMonth(calcItem == null ? null : calcItem.getPricingMonth());
    }
    // 财务与客户场景共用因素发布；客户 Cu 和板换 SUS 按各自业务规则取值。
    trace.put("calcScene", LinkedPriceCalcScene.QUOTE.getCode());
    String resolvedFactorSource =
        StringUtils.hasText(factorSource)
            ? factorSource.trim()
            : LinkedPriceFactorSource.OA_LOCKED.getCode();
    trace.put("factorSource", resolvedFactorSource);
    LinkedPriceVariableContext context =
        LinkedPriceVariableContext.quote(oaForm)
            .factorSource(resolvedFactorSource)
            .pricingMonth(calcItem == null ? null : calcItem.getPricingMonth());
    if (variableOverrides != null) {
      variableOverrides.forEach(
          (code, value) -> {
            if (code != null && value != null) {
              context.overrideKg(code, value, resolvedFactorSource);
            }
          });
    }
    return context;
  }

  private String calculationPricingMonth(PriceLinkedCalcItem calcItem, PriceLinkedItem linkedItem) {
    if (calcItem != null && StringUtils.hasText(calcItem.getPricingMonth())) {
      return calcItem.getPricingMonth().trim();
    }
    return linkedItem == null ? null : linkedItem.getPricingMonth();
  }

  private void writeTraceJson(PriceLinkedCalcItem item, Map<String, Object> trace) {
    try {
      item.setTraceJson(objectMapper.writeValueAsString(trace));
    } catch (JsonProcessingException error) {
      throw new IllegalStateException("无法保存联动计算依据，料号=" + item.getItemCode(), error);
    }
  }

  private record FormulaOutcome(BigDecimal value, Map<String, Object> trace) {}

  private BigDecimal calculatePartAmount(BigDecimal unitPrice, BigDecimal bomQty) {
    if (unitPrice == null || bomQty == null) {
      return null;
    }
    return unitPrice.multiply(bomQty).setScale(6, RoundingMode.HALF_UP);
  }
}
