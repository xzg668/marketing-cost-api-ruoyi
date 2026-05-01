package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.config.LinkedParserProperties;
import com.sanhua.marketingcost.dto.PriceLinkedCalcRow;
import com.sanhua.marketingcost.dto.PriceLinkedCalcTraceResponse;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewRequest;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse.VariableDetail;
import com.sanhua.marketingcost.service.PriceLinkedFormulaPreviewService;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistry;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.DynamicValueMapper;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceLinkedCalcService;
import java.util.LinkedHashMap;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.transaction.annotation.Transactional;

/**
 * 联动价计算服务。
 *
 * <p>T5.5：BOM 数据源从老表 {@code lp_bom_manage_item} 切换到新表 {@code lp_bom_costing_row}。
 * 字段映射：{@code getItemCode → getMaterialCode}、{@code getBomQty → getQtyPerTop}；
 * 其余字段（{@code getOaNo / getShapeAttr}）在新表上同名保留。
 * 内嵌 SQL 子查询里的表名 / 字段名同步替换。
 */
@Service
public class PriceLinkedCalcServiceImpl implements PriceLinkedCalcService {
  private static final Logger log = LoggerFactory.getLogger(PriceLinkedCalcServiceImpl.class);
  private static final String LINKED_PRICE_TYPE = "联动价";
  /** 中文/英文变量需用 [...] 包裹（Task #7） */
  private static final Pattern BRACKET_VARIABLE_PATTERN =
      Pattern.compile("\\[([\\u4e00-\\u9fa5A-Za-z_][\\u4e00-\\u9fa5A-Za-z0-9_]*)\\]");
  /** 兼容旧版裸 ASCII 标识符（无 [) */
  private static final Pattern VARIABLE_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9_]+$");
  private static final String FINANCE_PRICE_LATEST = "__latest__";
  private static final BigDecimal WEIGHT_DIVISOR = new BigDecimal("1000");

  private final BomCostingRowMapper bomCostingRowMapper;
  private final PriceLinkedCalcItemMapper priceLinkedCalcItemMapper;
  private final PriceLinkedItemMapper priceLinkedItemMapper;
  private final PriceVariableMapper priceVariableMapper;
  private final OaFormMapper oaFormMapper;
  private final FinanceBasePriceMapper financeBasePriceMapper;
  private final DynamicValueMapper dynamicValueMapper;

  /** T11 引入 —— 联动价 parser 灰度配置（legacy/dual/new） */
  private final LinkedParserProperties parserProperties;
  /** T11 引入 —— 新公式规范化器（括号/单位/变量替换四阶段） */
  private final FormulaNormalizer formulaNormalizer;
  /** T11 引入 —— factor_type 分派变量注册表 */
  private final FactorVariableRegistry factorVariableRegistry;
  /** T11 引入 —— 计算 trace 序列化 */
  private final ObjectMapper objectMapper;

  /** 按 linked_item.id 跑 trace 时复用 preview 实现，避免解析逻辑两份漂移 */
  private final PriceLinkedFormulaPreviewService previewService;

  public PriceLinkedCalcServiceImpl(
      BomCostingRowMapper bomCostingRowMapper,
      PriceLinkedCalcItemMapper priceLinkedCalcItemMapper,
      PriceLinkedItemMapper priceLinkedItemMapper,
      PriceVariableMapper priceVariableMapper,
      OaFormMapper oaFormMapper,
      FinanceBasePriceMapper financeBasePriceMapper,
      DynamicValueMapper dynamicValueMapper,
      LinkedParserProperties parserProperties,
      FormulaNormalizer formulaNormalizer,
      FactorVariableRegistry factorVariableRegistry,
      ObjectMapper objectMapper,
      PriceLinkedFormulaPreviewService previewService) {
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.priceLinkedCalcItemMapper = priceLinkedCalcItemMapper;
    this.priceLinkedItemMapper = priceLinkedItemMapper;
    this.priceVariableMapper = priceVariableMapper;
    this.oaFormMapper = oaFormMapper;
    this.financeBasePriceMapper = financeBasePriceMapper;
    this.dynamicValueMapper = dynamicValueMapper;
    this.parserProperties = parserProperties;
    this.formulaNormalizer = formulaNormalizer;
    this.factorVariableRegistry = factorVariableRegistry;
    this.objectMapper = objectMapper;
    this.previewService = previewService;
  }

  @Override
  public Page<PriceLinkedCalcRow> page(
      String oaNo, String itemCode, String shapeAttr, int page, int pageSize) {
    // V48 业务逻辑重写：
    //   联动价计算 = 联动价主表 lp_price_linked_item 全展示（按料号去重，本表已是料号唯一）。
    //   OA 单号只是上下文：从 BOM (lp_bom_costing_row) 聚合该料号的总用量（SUM(qty_per_top)）。
    //   - 联动价主表 22 条 → 永远 22 行结果（按页切片）
    //   - 该 OA 的 BOM 没用到的料号 → 部品用量 = NULL（前端展示空）
    //   - 该 OA 的 BOM 多次用到的料号 → 部品用量 = 累计求和
    //   shapeAttr 入参保留兼容前端契约，但联动价主表无该字段，仅在合成 BomCostingRow 时透传
    //   shape 信息（用于 calc_item 主键 + trace）。
    var liQuery = Wrappers.lambdaQuery(PriceLinkedItem.class)
        .eq(PriceLinkedItem::getDeleted, 0);
    if (StringUtils.hasText(itemCode)) {
      liQuery.like(PriceLinkedItem::getMaterialCode, itemCode.trim());
    }
    liQuery.orderByAsc(PriceLinkedItem::getId);

    Page<PriceLinkedItem> liPager = new Page<>(page, pageSize);
    Page<PriceLinkedItem> liPage = priceLinkedItemMapper.selectPage(liPager, liQuery);
    List<PriceLinkedItem> linkedItems = liPage.getRecords();

    // 聚合该 OA 的 BOM 用量（按 material_code → SUM(qty_per_top)）
    Map<String, BigDecimal> bomQtyMap = new HashMap<>();
    Map<String, String> bomShapeMap = new HashMap<>();
    if (StringUtils.hasText(oaNo) && !linkedItems.isEmpty()) {
      List<String> codes = new ArrayList<>();
      for (PriceLinkedItem li : linkedItems) {
        if (StringUtils.hasText(li.getMaterialCode())) {
          codes.add(li.getMaterialCode().trim());
        }
      }
      if (!codes.isEmpty()) {
        var bomQuery = Wrappers.lambdaQuery(BomCostingRow.class)
            .eq(BomCostingRow::getOaNo, oaNo.trim())
            .in(BomCostingRow::getMaterialCode, codes);
        if (StringUtils.hasText(shapeAttr)) {
          bomQuery.eq(BomCostingRow::getShapeAttr, shapeAttr.trim());
        }
        for (BomCostingRow b : bomCostingRowMapper.selectList(bomQuery)) {
          BigDecimal qty = b.getQtyPerTop() == null ? BigDecimal.ZERO : b.getQtyPerTop();
          bomQtyMap.merge(b.getMaterialCode(), qty, BigDecimal::add);
          bomShapeMap.putIfAbsent(b.getMaterialCode(), b.getShapeAttr());
        }
      }
    }

    // 合成 BomCostingRow 实例 —— 复用 fetchCalcItems / ensureCalcItems / fetchLinkedItems 等辅助方法
    // 同步 oaNo（空 oaNo 用 ""）+ material_code + shape（BOM 取首条；BOM 没的料号默认"部品联动"）+ 聚合用量
    String oaNoForKey = StringUtils.hasText(oaNo) ? oaNo.trim() : "";
    List<BomCostingRow> records = new ArrayList<>();
    for (PriceLinkedItem li : linkedItems) {
      BomCostingRow synth = new BomCostingRow();
      synth.setOaNo(oaNoForKey);
      synth.setMaterialCode(li.getMaterialCode());
      synth.setMaterialName(li.getMaterialName());
      synth.setShapeAttr(bomShapeMap.getOrDefault(li.getMaterialCode(), "部品联动"));
      synth.setQtyPerTop(bomQtyMap.get(li.getMaterialCode()));
      records.add(synth);
    }

    Map<String, PriceLinkedCalcItem> calcMap = fetchCalcItems(records, oaNo);
    ensureCalcItems(records, calcMap);
    Map<String, PriceLinkedItem> linkedItemMap = fetchLinkedItems(records);

    List<PriceLinkedCalcRow> rows = new ArrayList<>();
    for (BomCostingRow item : records) {
      PriceLinkedCalcRow row = new PriceLinkedCalcRow();
      row.setOaNo(item.getOaNo());
      row.setItemCode(item.getMaterialCode());
      row.setShapeAttr(item.getShapeAttr());
      row.setBomQty(item.getQtyPerTop());
      String key = buildKey(item.getOaNo(), item.getMaterialCode(), item.getShapeAttr());
      PriceLinkedCalcItem calcItem = calcMap.get(key);
      if (calcItem != null) {
        row.setCalcId(calcItem.getId());
        row.setPartUnitPrice(calcItem.getPartUnitPrice());
        row.setPartAmount(calcItem.getPartAmount());
        if (row.getBomQty() == null) {
          row.setBomQty(calcItem.getBomQty());
        }
      }
      String normalizedItemCode =
          item.getMaterialCode() == null ? null : item.getMaterialCode().trim();
      PriceLinkedItem linkedItem = normalizedItemCode == null
          ? null : linkedItemMap.get(normalizedItemCode);
      if (linkedItem != null) {
        row.setFormulaExpr(linkedItem.getFormulaExpr());
        row.setFormulaExprCn(linkedItem.getFormulaExprCn());
      }
      rows.add(row);
    }
    Page<PriceLinkedCalcRow> result = new Page<>(page, pageSize);
    result.setTotal(liPage.getTotal());
    result.setRecords(rows);
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public int refresh(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      return 0;
    }
    String oaNoValue = oaNo.trim();
    // T5.5：读 BOM 源切换到新表；内嵌子查询表名 / 字段名同步
    List<BomCostingRow> items = bomCostingRowMapper.selectList(
        Wrappers.lambdaQuery(BomCostingRow.class)
            .eq(BomCostingRow::getOaNo, oaNoValue)
            .apply(
                // V48 业务逻辑（与 page() 同步）：联动价 = 联动价主表里有定义的料号。
                "exists (select 1 from lp_price_linked_item li "
                    + "where li.material_code = lp_bom_costing_row.material_code "
                    + "and li.deleted = 0)"));
    if (items.isEmpty()) {
      return 0;
    }
    Map<String, PriceLinkedCalcItem> calcMap = fetchCalcItems(items, oaNoValue);
    int changed = ensureCalcItems(items, calcMap);
    Map<String, PriceLinkedCalcItem> refreshedCalcMap = fetchCalcItems(items, oaNoValue);
    Map<String, PriceLinkedItem> linkedItemMap = fetchLinkedItems(items);
    Map<String, PriceVariable> variableMap = fetchVariableMap();
    OaForm oaForm = oaFormMapper.selectOne(
        Wrappers.lambdaQuery(OaForm.class).eq(OaForm::getOaNo, oaNoValue).last("LIMIT 1"));
    Map<String, Map<String, BigDecimal>> financePriceMap =
        buildFinancePriceMap(variableMap);
    Set<String> handled = new HashSet<>();
    for (BomCostingRow item : items) {
      String itemCode = item.getMaterialCode();
      String normalizedItemCode = itemCode == null ? null : itemCode.trim();
      String key = buildKey(item.getOaNo(), itemCode, item.getShapeAttr());
      if (!handled.add(key)) {
        continue;
      }
      PriceLinkedCalcItem calcItem = refreshedCalcMap.get(key);
      if (calcItem == null) {
        continue;
      }
      // 用新表 qty_per_top 与 calcItem.bomQty 比较 —— calcItem 的 bomQty 字段名不变
      BigDecimal qty = item.getQtyPerTop();
      if (qty != null
          && (calcItem.getBomQty() == null
          || qty.compareTo(calcItem.getBomQty()) != 0)) {
        calcItem.setBomQty(qty);
      }
      PriceLinkedItem linkedItem = normalizedItemCode == null
          ? null
          : linkedItemMap.get(normalizedItemCode);
      // 记录 trace 写入前的原值 —— 用于判断是否需要触发 updateById
      // bug 修复：历史上仅当 unit_price/amount 变化才 update，导致新生成的 trace_json
      // 无法落库，前端行级 trace 永远拿到的是陈旧 / 空值
      String prevTraceJson = calcItem.getTraceJson();
      BigDecimal partUnitPrice = calculatePartUnitPrice(
          linkedItem, calcItem, oaForm, variableMap, financePriceMap);
      BigDecimal partAmount = calculatePartAmount(partUnitPrice, calcItem.getBomQty());
      boolean updated = false;
      if (!Objects.equals(calcItem.getPartUnitPrice(), partUnitPrice)) {
        calcItem.setPartUnitPrice(partUnitPrice);
        updated = true;
      }
      if (!Objects.equals(calcItem.getPartAmount(), partAmount)) {
        calcItem.setPartAmount(partAmount);
        updated = true;
      }
      // trace_json 由 calculatePartUnitPrice 内部 writeTraceJson 写入 calcItem（内存），
      // 这里显式对比，使 trace 变化也能触发持久化
      if (!Objects.equals(prevTraceJson, calcItem.getTraceJson())) {
        updated = true;
      }
      if (updated) {
        priceLinkedCalcItemMapper.updateById(calcItem);
        changed += 1;
      }
    }
    return changed;
  }

  /**
   * 按 {@code lp_price_linked_item.id} 当场跑 preview 产出 trace。
   *
   * <p>历史实现把 {@code id} 当作 calc_item.id 去 {@code selectById}，
   * 但 calc_item 与 linked_item 的主键域完全独立，前端点的又是 linked_item.id，
   * 造成必 miss 的 404。改走 {@link PriceLinkedFormulaPreviewService#preview}：
   * <ul>
   *   <li>从 linkedItem 抽 {@code formulaExpr / materialCode / pricingMonth} 拼 request</li>
   *   <li>preview 内部自带 normalize + resolve + evaluate 全链路</li>
   *   <li>把 response 拍平成前端 {@code parseTraceJson + buildTraceTimeline} 期待的 schema：
   *       {@code {rawExpr, normalizedExpr, variables, result, error}}</li>
   * </ul>
   *
   * <p>id 不存在 → 返回 null（Controller 转 404）；
   * 序列化异常降级为 null traceJson 并打 warn，不上抛。
   */
  @Override
  public PriceLinkedCalcTraceResponse getTrace(Long id) {
    if (id == null) {
      return null;
    }
    PriceLinkedItem linkedItem = priceLinkedItemMapper.selectById(id);
    if (linkedItem == null) {
      return null;
    }

    PriceLinkedFormulaPreviewRequest request = new PriceLinkedFormulaPreviewRequest();
    request.setFormulaExpr(linkedItem.getFormulaExpr());
    request.setMaterialCode(linkedItem.getMaterialCode());
    request.setPricingMonth(linkedItem.getPricingMonth());
    // 不含税结算口径：Excel"联动价-部品6"单价列 = 含税公式结果 / (1+vat_rate)；
    // 把 linkedItem.tax_included 透传给 preview，让其在阶段 5 做 1/(1+vat_rate) 转换
    request.setTaxIncluded(linkedItem.getTaxIncluded());
    PriceLinkedFormulaPreviewResponse preview = previewService.preview(request);

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
    flat.put("result", preview.getResult());
    if (StringUtils.hasText(preview.getError())) {
      flat.put("error", preview.getError());
    }

    String traceJson = null;
    try {
      traceJson = objectMapper.writeValueAsString(flat);
    } catch (JsonProcessingException e) {
      log.warn("getTrace 序列化 trace 失败: linkedId={} err={}", id, e.getMessage());
    }
    return new PriceLinkedCalcTraceResponse(id, traceJson);
  }

  /**
   * 按 calc_item.id 读 {@code trace_json}。
   *
   * <p>和 {@link #getTrace(Long)} 的 preview 口径不同 —— 这里返回的就是
   * 该行在最近一次 {@code refresh(oaNo)} 里实际跑出的 trace（含 OA 金属锁价代入）。
   * 前端行级"查看 trace"走这个入口。
   */
  @Override
  public PriceLinkedCalcTraceResponse getCalcTrace(Long calcId) {
    if (calcId == null) {
      return null;
    }
    PriceLinkedCalcItem calcItem = priceLinkedCalcItemMapper.selectById(calcId);
    if (calcItem == null) {
      return null;
    }
    // trace_json 可能为 null（legacy-only 模式或从未 refresh），
    // 这里原样透传；由前端决定展示空状态提示
    return new PriceLinkedCalcTraceResponse(calcItem.getId(), calcItem.getTraceJson());
  }

  private int ensureCalcItems(List<BomCostingRow> items, Map<String, PriceLinkedCalcItem> calcMap) {
    if (items == null || items.isEmpty()) {
      return 0;
    }
    int changed = 0;
    Set<String> handled = new HashSet<>();
    for (BomCostingRow item : items) {
      String key = buildKey(item.getOaNo(), item.getMaterialCode(), item.getShapeAttr());
      if (!handled.add(key)) {
        continue;
      }
      PriceLinkedCalcItem existing = calcMap.get(key);
      BigDecimal qty = item.getQtyPerTop();
      if (existing == null) {
        PriceLinkedCalcItem calc = new PriceLinkedCalcItem();
        calc.setOaNo(item.getOaNo());
        calc.setItemCode(item.getMaterialCode());
        calc.setShapeAttr(item.getShapeAttr());
        calc.setBomQty(qty);
        priceLinkedCalcItemMapper.insert(calc);
        changed += 1;
      } else if (qty != null
          && (existing.getBomQty() == null
          || qty.compareTo(existing.getBomQty()) != 0)) {
        existing.setBomQty(qty);
        priceLinkedCalcItemMapper.updateById(existing);
        changed += 1;
      }
    }
    return changed;
  }

  private Map<String, PriceLinkedCalcItem> fetchCalcItems(
      List<BomCostingRow> items, String oaNo) {
    if (items == null || items.isEmpty()) {
      return Map.of();
    }
    // V48：buildKey 不再含 shape，对应 fetch 也不再按 shape 过滤
    Set<String> itemCodes = new HashSet<>();
    for (BomCostingRow item : items) {
      if (StringUtils.hasText(item.getMaterialCode())) {
        itemCodes.add(item.getMaterialCode().trim());
      }
    }
    if (itemCodes.isEmpty()) {
      return Map.of();
    }
    var query = Wrappers.lambdaQuery(PriceLinkedCalcItem.class);
    if (StringUtils.hasText(oaNo)) {
      query.eq(PriceLinkedCalcItem::getOaNo, oaNo.trim());
    }
    query.in(PriceLinkedCalcItem::getItemCode, itemCodes);
    // V48：去掉 shape_attr 过滤 —— 业务唯一性是 (oa, item)，shape 仅是元数据
    List<PriceLinkedCalcItem> calcItems = priceLinkedCalcItemMapper.selectList(query);
    Map<String, PriceLinkedCalcItem> calcMap = new HashMap<>();

    for (PriceLinkedCalcItem calc : calcItems) {
      String key = buildKey(calc.getOaNo(), calc.getItemCode(), calc.getShapeAttr());
      calcMap.put(key, calc);
    }
    return calcMap;
  }

  private String buildKey(String oaNo, String itemCode, String shapeAttr) {
    // V48 修正：业务唯一性 = (oa_no, item_code)，**不再包含 shape_attr**。
    // 历史问题：BOM 模型变更（V21 旧 BOM shape='部品联动' → 新 BOM shape='采购件'）
    // 导致同一料号在 calc_item 表里出现两份，每次刷新如果 shape 不一致就再生一条。
    // shape_attr 仍作为元数据存在 calc_item 里给前端展示，但**不参与唯一性判断**。
    // 入参 shapeAttr 保留只为调用方契约不变。
    return String.format("%s|%s",
        oaNo == null ? "" : oaNo.trim(),
        itemCode == null ? "" : itemCode.trim());
  }

  private Map<String, PriceLinkedItem> fetchLinkedItems(List<BomCostingRow> items) {
    if (items == null || items.isEmpty()) {
      return Map.of();
    }
    Set<String> itemCodes = new HashSet<>();
    for (BomCostingRow item : items) {
      if (StringUtils.hasText(item.getMaterialCode())) {
        itemCodes.add(item.getMaterialCode().trim());
      }
    }
    if (itemCodes.isEmpty()) {
      return Map.of();
    }
    List<PriceLinkedItem> linkedItems = priceLinkedItemMapper.selectList(
        Wrappers.lambdaQuery(PriceLinkedItem.class)
            .in(PriceLinkedItem::getMaterialCode, itemCodes));
    Map<String, PriceLinkedItem> map = new HashMap<>();
    for (PriceLinkedItem item : linkedItems) {
      if (!StringUtils.hasText(item.getMaterialCode())) {
        continue;
      }
      String code = item.getMaterialCode().trim();
      PriceLinkedItem existing = map.get(code);
      if (existing == null || isLater(item, existing)) {
        map.put(code, item);
      }
    }
    return map;
  }

  private boolean isLater(PriceLinkedItem candidate, PriceLinkedItem existing) {
    if (existing == null) {
      return true;
    }
    if (candidate.getUpdatedAt() != null && existing.getUpdatedAt() != null) {
      return candidate.getUpdatedAt().isAfter(existing.getUpdatedAt());
    }
    if (candidate.getUpdatedAt() != null) {
      return true;
    }
    if (existing.getUpdatedAt() != null) {
      return false;
    }
    if (candidate.getId() != null && existing.getId() != null) {
      return candidate.getId() > existing.getId();
    }
    return false;
  }

  private Map<String, PriceVariable> fetchVariableMap() {
    List<PriceVariable> variables = priceVariableMapper.selectList(
        Wrappers.lambdaQuery(PriceVariable.class)
            .eq(PriceVariable::getStatus, "active"));
    Map<String, PriceVariable> map = new HashMap<>();
    for (PriceVariable variable : variables) {
      if (!StringUtils.hasText(variable.getVariableCode())) {
        continue;
      }
      map.put(variable.getVariableCode().trim(), variable);
    }
    return map;
  }

  private Map<String, Map<String, BigDecimal>> buildFinancePriceMap(
      Map<String, PriceVariable> variableMap) {
    Set<String> shortNames = new HashSet<>();
    for (PriceVariable variable : variableMap.values()) {
      if ("lp_finance_base_price".equalsIgnoreCase(variable.getSourceTable())
          && StringUtils.hasText(variable.getVariableName())) {
        shortNames.add(variable.getVariableName().trim());
      }
    }
    if (shortNames.isEmpty()) {
      return Map.of();
    }
    List<FinanceBasePrice> rows = financeBasePriceMapper.selectList(
        Wrappers.lambdaQuery(FinanceBasePrice.class)
            .in(FinanceBasePrice::getShortName, shortNames)
            .orderByDesc(FinanceBasePrice::getPriceMonth)
            .orderByAsc(FinanceBasePrice::getId));
    Map<String, Map<String, BigDecimal>> map = new HashMap<>();
    for (FinanceBasePrice row : rows) {
      if (!StringUtils.hasText(row.getShortName())) {
        continue;
      }
      String shortName = row.getShortName().trim();
      Map<String, BigDecimal> byMonth =
          map.computeIfAbsent(shortName, key -> new HashMap<>());
      if (StringUtils.hasText(row.getPriceMonth())) {
        byMonth.putIfAbsent(row.getPriceMonth(), row.getPrice());
      }
      byMonth.putIfAbsent(FINANCE_PRICE_LATEST, row.getPrice());
    }
    return map;
  }

  /**
   * T11 —— 取价分派入口：按 {@link LinkedParserProperties#getMode()} 决定跑 legacy / new / dual。
   *
   * <ul>
   *   <li>{@code legacy} —— 仅跑 {@link #legacyCalculate}，与改造前零差异</li>
   *   <li>{@code new}    —— 仅跑 {@link #newCalculate}（失败时返 null，trace 写错误原因）</li>
   *   <li>{@code dual}   —— 两份都跑；diff &gt; 阈值打 WARN；返回 legacy 值（生产默认，回滚保险）</li>
   * </ul>
   *
   * <p>trace 写入策略：只要 new 路径被执行（new/dual 模式），就把结构化 trace 写到
   * {@link PriceLinkedCalcItem#getTraceJson}；legacy-only 模式不写 trace 保持与老代码等同。
   */
  private BigDecimal calculatePartUnitPrice(
      PriceLinkedItem linkedItem,
      PriceLinkedCalcItem calcItem,
      OaForm oaForm,
      Map<String, PriceVariable> variableMap,
      Map<String, Map<String, BigDecimal>> financePriceMap) {
    if (linkedItem == null || !StringUtils.hasText(linkedItem.getFormulaExpr())) {
      return null;
    }
    boolean runLegacy = parserProperties.runsLegacyParser();
    boolean runNew = parserProperties.runsNewParser();
    BigDecimal legacyResult = null;
    BigDecimal newResult = null;
    Map<String, Object> trace = null;

    if (runLegacy) {
      legacyResult = legacyCalculate(
          linkedItem, calcItem, oaForm, variableMap, financePriceMap);
    }
    if (runNew) {
      NewCalcOutcome outcome = newCalculate(linkedItem, calcItem, oaForm);
      newResult = outcome.value;
      trace = outcome.trace;
    }

    // dual 模式下对比差异，超阈值打 WARN
    if (runLegacy && runNew) {
      BigDecimal diff = diffAbs(legacyResult, newResult);
      BigDecimal threshold = parserProperties.getDualWarnThreshold();
      if (diff != null && threshold != null && diff.compareTo(threshold) > 0) {
        log.warn("联动价双跑差异超阈值: oaNo={}, materialCode={}, legacy={}, new={}, diff={}, threshold={}",
            calcItem == null ? null : calcItem.getOaNo(),
            linkedItem.getMaterialCode(),
            legacyResult, newResult, diff, threshold);
      }
      if (trace != null) {
        trace.put("legacyResult", legacyResult);
        trace.put("newResult", newResult);
        trace.put("diff", diff);
      }
    }

    // 把 trace 写回 calcItem（仅当有 trace 时）；实际持久化在调用方 updateById
    if (trace != null && calcItem != null) {
      writeTraceJson(calcItem, trace);
    }

    // 返回值：new 管线优先 —— new 管线含 tax_included=0 的不含税转换，和联动价结果页 preview
    // 口径完全对齐；legacy 管线不读 tax_included，对 tax_included=0 的 OA 单会偏高 ≈ vat_rate 倍。
    // legacy 结果仅在 new 返回 null（new 管线解析异常）时作兜底，保证不至于返回空。
    BigDecimal chosen = (runNew && newResult != null) ? newResult : legacyResult;
    return chosen == null ? null : chosen.setScale(6, RoundingMode.HALF_UP);
  }

  /** 老管线 —— 保留原 evaluateExpression 路径，[中文] 包裹变量的兼容实现 */
  private BigDecimal legacyCalculate(
      PriceLinkedItem linkedItem,
      PriceLinkedCalcItem calcItem,
      OaForm oaForm,
      Map<String, PriceVariable> variableMap,
      Map<String, Map<String, BigDecimal>> financePriceMap) {
    String expr = linkedItem.getFormulaExpr().trim();
    Map<String, BigDecimal> values = resolveVariables(
        expr, linkedItem, calcItem, oaForm, variableMap, financePriceMap);
    return evaluateExpression(expr, values);
  }

  /**
   * 新管线 —— 完全委托给 {@link PriceLinkedFormulaPreviewService#previewForRefresh}。
   *
   * <p>2026-04 重构：消除 OA refresh 和联动价结果页 preview 两套重复实现。
   * 两个场景本质是同一个公式引擎 + 不同的变量值来源：
   * <ul>
   *   <li>联动价结果页（月度基准价）—— 变量值全部走 FactorVariableRegistry（finance 基价回落）</li>
   *   <li>OA 刷新(日常报价)       —— 同样走 registry，但 OA 锁价作为 overrides 优先命中</li>
   * </ul>
   * 历史上两处分别维护，改一处忘一处就偏差（2026-04 的 1.13 倍 tax bug 正是这样来的）。
   *
   * <p>本方法现在只做 3 件事：
   * <ol>
   *   <li>把 OA 金属锁价收成 Map 作为 overrides 传给 preview</li>
   *   <li>调 {@code previewForRefresh(linkedItem, overrides)} 走统一流水线</li>
   *   <li>把 preview 的结构化响应翻译成老的 trace Map（适配 calc_item.trace_json 存储 schema，
   *       前端 UI 依赖这个 schema 展示）</li>
   * </ol>
   *
   * <p>tax 转换 / vat_rate 硬错 / normalize / resolve 全部由 preview 负责，不再重复实现。
   */
  private NewCalcOutcome newCalculate(
      PriceLinkedItem linkedItem,
      PriceLinkedCalcItem calcItem,
      OaForm oaForm) {
    Map<String, Object> trace = new LinkedHashMap<>();
    trace.put("mode", parserProperties.getMode());
    if (linkedItem == null || !StringUtils.hasText(linkedItem.getFormulaExpr())) {
      trace.put("error", "linkedItem 或 formulaExpr 为空");
      return new NewCalcOutcome(null, trace);
    }
    trace.put("rawExpr", linkedItem.getFormulaExpr());

    // OA 锁价 → overrides；非空字段按吨转千克（兼容 OA_form.copper_price 的元/吨存储口径）
    Map<String, BigDecimal> overrides = buildOaLockOverrides(oaForm);

    PriceLinkedFormulaPreviewResponse resp =
        previewService.previewForRefresh(linkedItem, overrides);

    // 防御：preview 生产实现永远返 non-null，但 Mockito 默认 stub 会返 null，
    // 这里兜底防止单测场景下 NPE；同时也是针对未来 preview 改动的健壮性
    if (resp == null) {
      trace.put("error", "previewService 返回 null（可能 mock 未配置或实现被破坏）");
      return new NewCalcOutcome(null, trace);
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
    }
    // preview 有 error → 走失败分支：result=null，trace.error 记下原因（含 vat_rate 硬错等）
    if (StringUtils.hasText(resp.getError())) {
      log.warn("新管线取价失败: materialCode={}, error={}",
          linkedItem.getMaterialCode(), resp.getError());
      trace.put("error", resp.getError());
      return new NewCalcOutcome(null, trace);
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
    return new NewCalcOutcome(resp.getResult(), trace);
  }

  /**
   * 把 OA 单的金属锁价收成 {@code code → 元/kg} 的 Map（NULL 字段不 put，
   * 对应的变量走 FinanceBaseResolver 回落）。OA 字段存"元/吨"，这里 /1000 转"元/kg"。
   */
  private Map<String, BigDecimal> buildOaLockOverrides(OaForm oaForm) {
    Map<String, BigDecimal> overrides = new LinkedHashMap<>();
    if (oaForm == null) {
      return overrides;
    }
    putKgOverride(overrides, "Cu", oaForm.getCopperPrice());
    putKgOverride(overrides, "Zn", oaForm.getZincPrice());
    putKgOverride(overrides, "Al", oaForm.getAluminumPrice());
    // 注：oa_form.steel_price 暂无对应 variable_code 绑定，后续若需要 Fe/SUS 锁价再扩展
    return overrides;
  }

  private void putKgOverride(Map<String, BigDecimal> overrides, String code, BigDecimal tonPrice) {
    if (tonPrice == null) {
      return;
    }
    overrides.put(code, tonPrice.divide(WEIGHT_DIVISOR, 6, RoundingMode.HALF_UP));
  }

  /** 绝对差；任一为空返 null（表示不可比较，不触发 WARN） */
  private static BigDecimal diffAbs(BigDecimal a, BigDecimal b) {
    if (a == null || b == null) {
      return null;
    }
    return a.subtract(b).abs();
  }

  /** 将 trace map 序列化为 JSON 写回 calcItem.trace_json —— 失败仅打 debug 不影响主流程 */
  private void writeTraceJson(PriceLinkedCalcItem calcItem, Map<String, Object> trace) {
    try {
      calcItem.setTraceJson(objectMapper.writeValueAsString(trace));
    } catch (JsonProcessingException e) {
      log.debug("trace JSON 序列化失败: {}", e.getMessage());
    }
  }

  /** 新管线输出载荷：结果值 + 结构化 trace（同时供 dual 比对和持久化） */
  private record NewCalcOutcome(BigDecimal value, Map<String, Object> trace) {}

  /**
   * V28 收尾：把 OA 单里填的金属锁价展开到 {@link VariableContext#override} —— 优先级高于 resolver。
   *
   * <p>当前 OA 单支持的金属字段：Cu/Zn/Al（见 oa_form 表结构）。
   * 字段 NULL 视为"未锁价"，不 put 进 overrides，让新引擎继续走 FinanceBaseResolver 的基价回落。
   *
   * <p>单位换算：OA 表字段单位为"元/吨"，与 lp_finance_base_price 的"元/千克"统一，需 /1000。
   */
  private void applyOaLockPrice(VariableContext ctx, OaForm oaForm) {
    if (ctx == null || oaForm == null) {
      return;
    }
    putOaOverride(ctx, "Cu", oaForm.getCopperPrice());
    putOaOverride(ctx, "Zn", oaForm.getZincPrice());
    putOaOverride(ctx, "Al", oaForm.getAluminumPrice());
    // 注：oa_form.steel_price 暂无对应 variable_code 绑定，后续若需要 Fe/SUS 锁价再扩展
  }

  /** 把单个 OA 金属字段按 /1000 换算后 put 进 overrides；NULL 不 put（由调用方保证空值含义=未锁价）。 */
  private void putOaOverride(VariableContext ctx, String code, BigDecimal tonPrice) {
    if (tonPrice == null) {
      return;
    }
    ctx.override(
        code, tonPrice.divide(WEIGHT_DIVISOR, 6, RoundingMode.HALF_UP));
  }

  private Map<String, BigDecimal> resolveVariables(
      String expr,
      PriceLinkedItem linkedItem,
      PriceLinkedCalcItem calcItem,
      OaForm oaForm,
      Map<String, PriceVariable> variableMap,
      Map<String, Map<String, BigDecimal>> financePriceMap) {
    Set<String> tokens = extractVariableTokens(expr);
    Map<String, BigDecimal> values = new HashMap<>();
    for (String token : tokens) {
      PriceVariable variable = variableMap.get(token);
      BigDecimal value = resolveVariableValue(
          variable, linkedItem, calcItem, oaForm, financePriceMap);
      values.put(token, value == null ? BigDecimal.ZERO : value);
    }
    return values;
  }

  private Set<String> extractVariableTokens(String expr) {
    Set<String> tokens = new HashSet<>();
    if (!StringUtils.hasText(expr)) {
      return tokens;
    }
    // 1) 优先抽 [中文/英文] 显式声明，并用空白填充以避免与裸 ASCII 重复
    StringBuilder remaining = new StringBuilder(expr);
    Matcher bracket = BRACKET_VARIABLE_PATTERN.matcher(expr);
    while (bracket.find()) {
      tokens.add(bracket.group(1));
      for (int i = bracket.start(); i < bracket.end(); i++) {
        remaining.setCharAt(i, ' ');
      }
    }
    // 2) 旧表达式不含 [，走 ASCII 模式兼容
    Matcher matcher = VARIABLE_PATTERN.matcher(remaining.toString());
    while (matcher.find()) {
      tokens.add(matcher.group());
    }
    return tokens;
  }

  private BigDecimal resolveVariableValue(
      PriceVariable variable,
      PriceLinkedItem linkedItem,
      PriceLinkedCalcItem calcItem,
      OaForm oaForm,
      Map<String, Map<String, BigDecimal>> financePriceMap) {
    if (variable == null || !StringUtils.hasText(variable.getSourceTable())) {
      return null;
    }
    String sourceTable = variable.getSourceTable().trim();
    if ("oa_form".equalsIgnoreCase(sourceTable)) {
      // OA 锁价优先：填了就用 OA（元/吨 → 元/千克）
      BigDecimal value = readDecimal(oaForm, variable.getSourceField());
      if (value != null) {
        return value.divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP);
      }
      // OA NULL：回落到基价表按 variable_code 当月"长江现货平均价"查，
      // 与 V28 新引擎语义保持一致（见 V28 迁移注释）
      return resolveFinanceFallback(variable, linkedItem);
    }
    if ("lp_finance_base_price".equalsIgnoreCase(sourceTable)) {
      return resolveFinanceValue(
          variable.getVariableName(),
          linkedItem == null ? null : linkedItem.getPricingMonth(),
          financePriceMap);
    }
    if ("lp_price_linked_item".equalsIgnoreCase(sourceTable)) {
      BigDecimal value = readDecimal(linkedItem, variable.getSourceField());
      return adjustWeightIfNeeded(variable.getSourceField(), value);
    }
    if ("lp_price_linked_calc_item".equalsIgnoreCase(sourceTable)) {
      BigDecimal value = readDecimal(calcItem, variable.getSourceField());
      return adjustWeightIfNeeded(variable.getSourceField(), value);
    }
    String itemCode = calcItem == null ? null : calcItem.getItemCode();
    return resolveDynamicValue(
        sourceTable,
        variable.getSourceField(),
        itemCode == null ? null : itemCode.trim());
  }

  private BigDecimal resolveFinanceValue(
      String shortName,
      String pricingMonth,
      Map<String, Map<String, BigDecimal>> financePriceMap) {
    if (!StringUtils.hasText(shortName)) {
      return null;
    }
    Map<String, BigDecimal> byMonth =
        financePriceMap.get(shortName.trim());
    if (byMonth == null || byMonth.isEmpty()) {
      return null;
    }
    if (StringUtils.hasText(pricingMonth) && byMonth.containsKey(pricingMonth)) {
      return byMonth.get(pricingMonth);
    }
    if (byMonth.containsKey(FINANCE_PRICE_LATEST)) {
      return byMonth.get(FINANCE_PRICE_LATEST);
    }
    return byMonth.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
  }

  /**
   * OA 锁价 NULL 时的基价表回落 —— 按 variable_code 对应 lp_finance_base_price.factor_code
   * 查当月"长江现货平均价"（V28 定义的权威源）。
   *
   * <p>匹配条件：factor_code + price_month + price_source='长江现货平均价'；
   * 若 linkedItem 带 businessUnitType，再加 BU 维度精确匹配。
   *
   * <p>返回：查到 → 该月价；查不到 → null（公式里该变量会进 MISSING，由调用方暴露给业务）
   */
  private BigDecimal resolveFinanceFallback(
      PriceVariable variable, PriceLinkedItem linkedItem) {
    if (variable == null || linkedItem == null) {
      return null;
    }
    String factorCode = variable.getVariableCode();
    String month = linkedItem.getPricingMonth();
    if (!StringUtils.hasText(factorCode) || !StringUtils.hasText(month)) {
      return null;
    }
    var query = Wrappers.lambdaQuery(FinanceBasePrice.class)
        .eq(FinanceBasePrice::getFactorCode, factorCode.trim())
        .eq(FinanceBasePrice::getPriceMonth, month.trim())
        .eq(FinanceBasePrice::getPriceSource, "长江现货平均价");
    if (StringUtils.hasText(linkedItem.getBusinessUnitType())) {
      query.eq(FinanceBasePrice::getBusinessUnitType, linkedItem.getBusinessUnitType().trim());
    }
    query.last("LIMIT 1");
    FinanceBasePrice row = financeBasePriceMapper.selectOne(query);
    return row == null ? null : row.getPrice();
  }

  private BigDecimal resolveDynamicValue(String table, String field, String itemCode) {
    if (!StringUtils.hasText(table) || !StringUtils.hasText(field)) {
      return null;
    }
    if (!SAFE_IDENTIFIER.matcher(table).matches()
        || !SAFE_IDENTIFIER.matcher(field).matches()) {
      return null;
    }
    if (!StringUtils.hasText(itemCode)) {
      return null;
    }
    Object value = dynamicValueMapper.selectByMaterialCode(table, field, itemCode);
    if (value == null) {
      value = dynamicValueMapper.selectByItemCode(table, field, itemCode);
    }
    return toBigDecimal(value);
  }

  private BigDecimal adjustWeightIfNeeded(String sourceField, BigDecimal value) {
    if (value == null || !StringUtils.hasText(sourceField)) {
      return value;
    }
    String normalized = sourceField.trim().toLowerCase().replace("_", "");
    if ("blankweight".equals(normalized) || "netweight".equals(normalized)) {
      return value.divide(WEIGHT_DIVISOR, 6, RoundingMode.HALF_UP);
    }
    return value;
  }

  private BigDecimal calculatePartAmount(BigDecimal unitPrice, BigDecimal bomQty) {
    if (unitPrice == null || bomQty == null) {
      return null;
    }
    return unitPrice.multiply(bomQty).setScale(6, RoundingMode.HALF_UP);
  }

  private BigDecimal readDecimal(Object target, String sourceField) {
    if (target == null || !StringUtils.hasText(sourceField)) {
      return null;
    }
    BeanWrapperImpl wrapper = new BeanWrapperImpl(target);
    String trimmed = sourceField.trim();
    Object value = null;
    if (wrapper.isReadableProperty(trimmed)) {
      value = wrapper.getPropertyValue(trimmed);
    } else {
      String camel = toCamelCase(trimmed);
      if (wrapper.isReadableProperty(camel)) {
        value = wrapper.getPropertyValue(camel);
      }
    }
    return toBigDecimal(value);
  }

  private BigDecimal toBigDecimal(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    if (value instanceof Number) {
      return new BigDecimal(((Number) value).toString());
    }
    if (value instanceof String) {
      String text = ((String) value).trim();
      if (text.isEmpty()) {
        return null;
      }
      try {
        return new BigDecimal(text);
      } catch (NumberFormatException ex) {
        return null;
      }
    }
    return null;
  }

  private String toCamelCase(String value) {
    StringBuilder builder = new StringBuilder();
    boolean nextUpper = false;
    for (char ch : value.toCharArray()) {
      if (ch == '_') {
        nextUpper = true;
        continue;
      }
      builder.append(nextUpper ? Character.toUpperCase(ch) : ch);
      nextUpper = false;
    }
    return builder.toString();
  }

  private BigDecimal evaluateExpression(String expr, Map<String, BigDecimal> variables) {
    if (!StringUtils.hasText(expr)) {
      return null;
    }
    List<Token> tokens = tokenize(expr);
    List<Token> output = new ArrayList<>();
    Deque<Token> operators = new ArrayDeque<>();
    Token previous = null;
    for (Token token : tokens) {
      switch (token.type) {
        case NUMBER, VARIABLE -> {
          output.add(token);
          previous = token;
        }
        case OPERATOR -> {
          Token op = token;
          if ("-".equals(op.text) && (previous == null
              || previous.type == TokenType.OPERATOR
              || previous.type == TokenType.LEFT_PAREN)) {
            op = Token.unary();
          }
          while (!operators.isEmpty()
              && operators.peek().type == TokenType.OPERATOR
              && precedence(operators.peek()) >= precedence(op)) {
            output.add(operators.pop());
          }
          operators.push(op);
          previous = op;
        }
        case LEFT_PAREN -> {
          operators.push(token);
          previous = token;
        }
        case RIGHT_PAREN -> {
          while (!operators.isEmpty()
              && operators.peek().type != TokenType.LEFT_PAREN) {
            output.add(operators.pop());
          }
          if (!operators.isEmpty() && operators.peek().type == TokenType.LEFT_PAREN) {
            operators.pop();
          }
          previous = token;
        }
        default -> {
        }
      }
    }
    while (!operators.isEmpty()) {
      output.add(operators.pop());
    }
    Deque<BigDecimal> stack = new ArrayDeque<>();
    for (Token token : output) {
      switch (token.type) {
        case NUMBER -> stack.push(token.number);
        case VARIABLE -> stack.push(variables.getOrDefault(token.text, BigDecimal.ZERO));
        case OPERATOR -> {
          if ("NEG".equals(token.text)) {
            BigDecimal value = stack.isEmpty() ? null : stack.pop();
            if (value == null) {
              return null;
            }
            stack.push(value.negate());
          } else {
            BigDecimal right = stack.isEmpty() ? null : stack.pop();
            BigDecimal left = stack.isEmpty() ? null : stack.pop();
            if (left == null || right == null) {
              return null;
            }
            BigDecimal applied = applyOperator(token.text, left, right);
            if (applied == null) {
              return null;
            }
            stack.push(applied);
          }
        }
        default -> {
        }
      }
    }
    if (stack.size() != 1) {
      return null;
    }
    return stack.pop();
  }

  private List<Token> tokenize(String expr) {
    List<Token> tokens = new ArrayList<>();
    int index = 0;
    while (index < expr.length()) {
      char ch = expr.charAt(index);
      if (Character.isWhitespace(ch)) {
        index++;
        continue;
      }
      if (Character.isDigit(ch) || ch == '.') {
        int start = index;
        while (index < expr.length()) {
          char next = expr.charAt(index);
          if (Character.isDigit(next) || next == '.') {
            index++;
          } else {
            break;
          }
        }
        String text = expr.substring(start, index);
        try {
          tokens.add(Token.number(new BigDecimal(text)));
        } catch (NumberFormatException ex) {
          return List.of();
        }
        continue;
      }
      if (Character.isLetter(ch) || ch == '_') {
        int start = index;
        while (index < expr.length()) {
          char next = expr.charAt(index);
          if (Character.isLetterOrDigit(next) || next == '_') {
            index++;
          } else {
            break;
          }
        }
        String text = expr.substring(start, index);
        tokens.add(Token.variable(text));
        continue;
      }
      if (ch == '(') {
        tokens.add(Token.leftParen());
        index++;
        continue;
      }
      if (ch == ')') {
        tokens.add(Token.rightParen());
        index++;
        continue;
      }
      if ("+-*/".indexOf(ch) >= 0) {
        tokens.add(Token.operator(String.valueOf(ch)));
        index++;
        continue;
      }
      index++;
    }
    return tokens;
  }

  private int precedence(Token token) {
    if ("NEG".equals(token.text)) {
      return 3;
    }
    if ("*".equals(token.text) || "/".equals(token.text)) {
      return 2;
    }
    return 1;
  }

  private BigDecimal applyOperator(String op, BigDecimal left, BigDecimal right) {
    return switch (op) {
      case "+" -> left.add(right);
      case "-" -> left.subtract(right);
      case "*" -> left.multiply(right);
      case "/" -> right.compareTo(BigDecimal.ZERO) == 0
          ? null
          : left.divide(right, 10, RoundingMode.HALF_UP);
      default -> null;
    };
  }

  private enum TokenType {
    NUMBER,
    VARIABLE,
    OPERATOR,
    LEFT_PAREN,
    RIGHT_PAREN
  }

  private static final class Token {
    private final TokenType type;
    private final String text;
    private final BigDecimal number;

    private Token(TokenType type, String text, BigDecimal number) {
      this.type = type;
      this.text = text;
      this.number = number;
    }

    static Token number(BigDecimal number) {
      return new Token(TokenType.NUMBER, null, number);
    }

    static Token variable(String name) {
      return new Token(TokenType.VARIABLE, name, null);
    }

    static Token operator(String op) {
      return new Token(TokenType.OPERATOR, op, null);
    }

    static Token unary() {
      return new Token(TokenType.OPERATOR, "NEG", null);
    }

    static Token leftParen() {
      return new Token(TokenType.LEFT_PAREN, "(", null);
    }

    static Token rightParen() {
      return new Token(TokenType.RIGHT_PAREN, ")", null);
    }
  }
}
