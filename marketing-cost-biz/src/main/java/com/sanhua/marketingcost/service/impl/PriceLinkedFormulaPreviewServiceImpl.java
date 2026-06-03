package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.LinkedPriceVariableContext;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewRequest;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse.TraceEntry;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse.VariableDetail;
import com.sanhua.marketingcost.entity.FactorQuoteBaseMapping;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.FormulaSyntaxException;
import com.sanhua.marketingcost.formula.normalize.FormulaUnitConsistencyChecker;
import com.sanhua.marketingcost.formula.registry.ExpressionEvaluator;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistry;
import com.sanhua.marketingcost.formula.registry.VariableContext;
import com.sanhua.marketingcost.mapper.FactorQuoteBaseMappingMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceLinkedFormulaPreviewService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 预览服务实现 —— 把 Normalizer → Registry → Evaluator 三步串联，无持久化副作用。
 *
 * <p>执行阶段：
 * <ol>
 *   <li><b>校验</b>：formulaExpr 必填；缺 materialCode 加 warning 但继续算</li>
 *   <li><b>normalize</b>：任何 {@link FormulaSyntaxException} 直接写入 error 并早退</li>
 *   <li><b>resolve</b>：逐 token 走 Registry；未解析出值按 0 处理，source 标 MISSING</li>
 *   <li><b>evaluate</b>：ExpressionEvaluator 求值；运行时异常也写入 error 不上抛</li>
 * </ol>
 *
 * <p>每阶段都往 {@code response.trace} 追加一条，供前端时间轴展示。
 */
@Service
public class PriceLinkedFormulaPreviewServiceImpl implements PriceLinkedFormulaPreviewService {

  private static final Logger log = LoggerFactory.getLogger(PriceLinkedFormulaPreviewServiceImpl.class);

  private final FormulaNormalizer formulaNormalizer;
  private final FactorVariableRegistry factorVariableRegistry;
  private final PriceVariableMapper priceVariableMapper;
  private final PriceLinkedItemMapper priceLinkedItemMapper;
  private final FormulaUnitConsistencyChecker unitConsistencyChecker;
  private final ObjectMapper objectMapper;
  private final FactorQuoteBaseMappingMapper factorQuoteBaseMappingMapper;

  public PriceLinkedFormulaPreviewServiceImpl(
      FormulaNormalizer formulaNormalizer,
      FactorVariableRegistry factorVariableRegistry,
      PriceVariableMapper priceVariableMapper,
      PriceLinkedItemMapper priceLinkedItemMapper,
      FormulaUnitConsistencyChecker unitConsistencyChecker) {
    this(
        formulaNormalizer,
        factorVariableRegistry,
        priceVariableMapper,
        priceLinkedItemMapper,
        unitConsistencyChecker,
        new ObjectMapper(),
        null);
  }

  @Autowired
  public PriceLinkedFormulaPreviewServiceImpl(
      FormulaNormalizer formulaNormalizer,
      FactorVariableRegistry factorVariableRegistry,
      PriceVariableMapper priceVariableMapper,
      PriceLinkedItemMapper priceLinkedItemMapper,
      FormulaUnitConsistencyChecker unitConsistencyChecker,
      ObjectMapper objectMapper,
      FactorQuoteBaseMappingMapper factorQuoteBaseMappingMapper) {
    this.formulaNormalizer = formulaNormalizer;
    this.factorVariableRegistry = factorVariableRegistry;
    this.priceVariableMapper = priceVariableMapper;
    this.priceLinkedItemMapper = priceLinkedItemMapper;
    this.unitConsistencyChecker = unitConsistencyChecker;
    this.objectMapper = objectMapper;
    this.factorQuoteBaseMappingMapper = factorQuoteBaseMappingMapper;
  }

  @Override
  public PriceLinkedFormulaPreviewResponse preview(PriceLinkedFormulaPreviewRequest request) {
    if (request == null || !StringUtils.hasText(request.getFormulaExpr())) {
      PriceLinkedFormulaPreviewResponse response = new PriceLinkedFormulaPreviewResponse();
      response.setError("公式不能为空");
      response.getTrace().add(new TraceEntry("validate", "formulaExpr 为空"));
      return response;
    }
    // preview 场景：没有预加载的 linkedItem；按 materialCode 查，查不到给 warning
    return doCompute(
        request.getFormulaExpr(),
        request.getMaterialCode(),
        request.getPricingMonth(),
        request.getTaxIncluded(),
        null,
        null,
        null,
        null);
  }

  @Override
  public PriceLinkedFormulaPreviewResponse previewForRefresh(
      PriceLinkedItem linkedItem, Map<String, BigDecimal> variableOverrides) {
    return previewForRefresh(linkedItem, variableOverrides, null);
  }

  @Override
  public PriceLinkedFormulaPreviewResponse previewForRefresh(
      PriceLinkedItem linkedItem,
      Map<String, BigDecimal> variableOverrides,
      OaForm oaForm) {
    PriceLinkedFormulaPreviewResponse response = new PriceLinkedFormulaPreviewResponse();
    if (linkedItem == null || !StringUtils.hasText(linkedItem.getFormulaExpr())) {
      response.setError("公式不能为空");
      response.getTrace().add(new TraceEntry("validate", "linkedItem 或 formulaExpr 为空"));
      return response;
    }
    // refresh 场景：linkedItem 已在手，不要再按 materialCode 查库（省一次 round trip）
    return doCompute(
        linkedItem.getFormulaExpr(),
        linkedItem.getMaterialCode(),
        linkedItem.getPricingMonth(),
        linkedItem.getTaxIncluded(),
        linkedItem,
        oaForm,
        variableOverrides,
        null);
  }

  @Override
  public PriceLinkedFormulaPreviewResponse previewForRefresh(
      PriceLinkedItem linkedItem,
      LinkedPriceVariableContext variableContext,
      OaForm oaForm) {
    PriceLinkedFormulaPreviewResponse response = new PriceLinkedFormulaPreviewResponse();
    if (linkedItem == null || !StringUtils.hasText(linkedItem.getFormulaExpr())) {
      response.setError("公式不能为空");
      response.getTrace().add(new TraceEntry("validate", "linkedItem 或 formulaExpr 为空"));
      return response;
    }
    return doCompute(
        linkedItem.getFormulaExpr(),
        linkedItem.getMaterialCode(),
        calculationPricingMonth(linkedItem, variableContext),
        linkedItem.getTaxIncluded(),
        linkedItem,
        oaForm,
        variableContext == null ? null : variableContext.getVariableValues(),
        variableContext == null ? null : variableContext.getVariableSources(),
        variableContext);
  }

  private String calculationPricingMonth(
      PriceLinkedItem linkedItem, LinkedPriceVariableContext variableContext) {
    if (variableContext != null && StringUtils.hasText(variableContext.getPricingMonth())) {
      return variableContext.getPricingMonth().trim();
    }
    return linkedItem == null ? null : linkedItem.getPricingMonth();
  }

  /**
   * 核心计算流水线 —— preview 和 previewForRefresh 共用。差异只在入参：
   * <ul>
   *   <li>{@code preloadedItem} 非 null 时跳过 fetchLinkedItem 的 DB 查询</li>
   *   <li>{@code overrides} 在阶段 3 前写进 ctx，FactorVariableRegistry 查
   *       overrides 优先命中（OA 锁价场景）</li>
   * </ul>
   */
  private PriceLinkedFormulaPreviewResponse doCompute(
      String rawExpr,
      String materialCode,
      String pricingMonth,
      Integer taxIncluded,
      PriceLinkedItem preloadedItem,
      OaForm oaForm,
      Map<String, BigDecimal> overrides,
      Map<String, String> overrideSources) {
    return doCompute(
        rawExpr,
        materialCode,
        pricingMonth,
        taxIncluded,
        preloadedItem,
        oaForm,
        overrides,
        overrideSources,
        null);
  }

  private PriceLinkedFormulaPreviewResponse doCompute(
      String rawExpr,
      String materialCode,
      String pricingMonth,
      Integer taxIncluded,
      PriceLinkedItem preloadedItem,
      OaForm oaForm,
      Map<String, BigDecimal> overrides,
      Map<String, String> overrideSources,
      LinkedPriceVariableContext variableContext) {
    PriceLinkedFormulaPreviewResponse response = new PriceLinkedFormulaPreviewResponse();

    // 阶段 1：规范化
    //
    // 捕异常的范围是 RuntimeException —— 除了 FormulaSyntaxException 这种业务错外，
    // Normalizer 可能抛各种运行时异常（如缺依赖、内部断言等）。preview 服务的契约是
    // "任何错误都落成 response.error，不让异常冒泡到上层"，否则 refresh 场景下这类
    // 异常会绕过 catch 直接炸服务。
    String normalized;
    try {
      normalized = formulaNormalizer.normalize(rawExpr);
      response.setNormalizedExpr(normalized);
      response.getTrace().add(new TraceEntry("normalize", normalized));
    } catch (FormulaSyntaxException e) {
      response.setError("公式语法错误: " + e.getMessage());
      response.getTrace().add(new TraceEntry("error", e.getMessage()));
      return response;
    } catch (RuntimeException e) {
      response.setError(e.getClass().getSimpleName() + ": " + e.getMessage());
      response.getTrace().add(new TraceEntry("error", e.getMessage()));
      return response;
    }

    // 单位一致性静态检查 —— 把 /1000、*1000、裸大常数之类"元/吨旧口径"嫌疑写法收集为 warnings
    response.getWarnings().addAll(unitConsistencyChecker.check(normalized));

    // 阶段 2：上下文准备（尽力而为 —— 拉不到部品不阻断计算）
    PriceLinkedItem linkedItem = preloadedItem;
    if (linkedItem == null) {
      if (!StringUtils.hasText(materialCode)) {
        response.getWarnings().add("未提供 materialCode 无法取部品上下文");
      } else {
        linkedItem = fetchLinkedItem(materialCode, pricingMonth);
        if (linkedItem == null) {
          response.getWarnings().add(
              "未在 lp_price_linked_item 找到 materialCode=" + materialCode + " 的记录");
        }
      }
    }
    VariableContext ctx = new VariableContext()
        .materialCode(materialCode)
        .pricingMonth(pricingMonth)
        .oaForm(oaForm)
        .linkedItem(linkedItem);
    if (variableContext != null
        && variableContext.getCalcScene() == LinkedPriceCalcScene.MONTHLY_ADJUST) {
      // 月度调价场景必须只读影响因素月度价或指定调价批次价，不能读取 OA 表头锁价。
      ctx.priceContextType(VariableContext.PriceContextType.MONTHLY_REPRICE)
          .adjustBatchId(variableContext.getAdjustBatchId());
    }
    // OA 锁价等 overrides 在变量解析之前灌进 ctx，让 registry 在查 finance 基价前先看 overrides
    if (overrides != null) {
      overrides.forEach((code, value) -> {
        if (code != null && value != null) {
          String source = overrideSources == null ? null : overrideSources.get(code);
          ctx.override(code, value, source);
        }
      });
    }

    // 阶段 3：变量逐个解析
    LinkedHashSet<String> tokens = ExpressionEvaluator.extractVariables(normalized);
    Map<String, BigDecimal> values = new LinkedHashMap<>();
    Map<String, PriceVariable> variableMeta = loadVariableMeta(tokens);
    for (String token : tokens) {
      Optional<BigDecimal> resolved;
      try {
        resolved = factorVariableRegistry.resolve(token, ctx);
      } catch (RuntimeException e) {
        response.setError("变量解析失败: " + token + " -> " + e.getMessage());
        response.getTrace().add(new TraceEntry("error", token + ": " + e.getMessage()));
        return response;
      }
      BigDecimal value = resolved.orElse(BigDecimal.ZERO);
      values.put(token, value);
      // 让后续 DERIVED/FORMULA_REF 能看到上游值
      ctx.override(token, value);

      PriceVariable meta = variableMeta.get(token);
      VariableDetail detail = new VariableDetail();
      detail.setCode(token);
      detail.setName(meta == null ? token : safeName(meta));
      detail.setValue(value);
      detail.setSource(resolveVariableSource(token, meta, resolved, ctx));
      response.getVariables().add(detail);
    }
    response.getTrace().add(new TraceEntry("resolve", values.toString()));

    // 阶段 4：求值
    BigDecimal result;
    try {
      result = ExpressionEvaluator.evaluate(normalized, values);
      response.setResult(result);
      response.getTrace().add(new TraceEntry("evaluate", String.valueOf(result)));
    } catch (RuntimeException e) {
      log.warn("公式预览求值失败: expr={} err={}", normalized, e.getMessage());
      response.setError("求值失败: " + e.getMessage());
      response.getTrace().add(new TraceEntry("error", e.getMessage()));
      return response;
    }
    // 有变量取不到值时 evaluator 可能返回 null（非抛异常）；此时直接早退，避免阶段 5 的 divide 炸 NPE
    if (result == null) {
      response.setError("公式求值返回空值（可能某变量未解析到值），跳过税口径转换");
      response.getTrace().add(new TraceEntry("error", "evaluate returned null"));
      return response;
    }

    // 阶段 5：不含税结算口径转换
    // 背景：Excel 联动价-部品6 的"单价"列实质是"含税公式结果 / (1+vat_rate)"得到的不含税结算价。
    // 当 lp_price_linked_item.tax_included=0 时，我们必须再做一次除法，和 Excel 对齐。
    // taxIncluded=1 或 null → 保持原值（公式本身就是含税结算口径）。
    if (taxIncluded != null && taxIncluded == 0) {
      Optional<BigDecimal> vatRateOpt;
      try {
        vatRateOpt = factorVariableRegistry.resolve("vat_rate", ctx);
      } catch (RuntimeException e) {
        response.setError("税率解析失败: vat_rate -> " + e.getMessage());
        response.getTrace().add(new TraceEntry("error", "vat_rate: " + e.getMessage()));
        return response;
      }
      if (vatRateOpt.isEmpty()) {
        response.setError("税率未配置: vat_rate");
        response.getTrace().add(new TraceEntry("error", "vat_rate MISSING"));
        return response;
      }
      BigDecimal vatRate = vatRateOpt.get();
      BigDecimal divisor = BigDecimal.ONE.add(vatRate);
      if (divisor.compareTo(BigDecimal.ZERO) == 0) {
        response.setError("税率非法: 1+vat_rate=0");
        response.getTrace().add(new TraceEntry("error", "vat_rate=-1"));
        return response;
      }
      // 保留充足精度；最终舍入由上游展示层决定
      BigDecimal adjusted = result.divide(divisor, new MathContext(20, RoundingMode.HALF_UP));
      response.setResult(adjusted);
      response.getTrace().add(new TraceEntry(
          "tax_adjust",
          String.format("不含税结算：%s / (1+%s) = %s", result, vatRate, adjusted)));
    }

    return response;
  }

  private String resolveVariableSource(
      String token, PriceVariable meta, Optional<BigDecimal> resolved, VariableContext ctx) {
    if (resolved.isEmpty()) {
      return "MISSING";
    }
    if (ctx != null && StringUtils.hasText(ctx.getOverrideSource(token))) {
      return ctx.getOverrideSource(token);
    }
    if (ctx != null && StringUtils.hasText(ctx.getResolvedSource(token))) {
      return ctx.getResolvedSource(token);
    }
    if (meta == null) {
      return "UNKNOWN";
    }
    if (usesQuoteBaseOverride(meta, ctx)) {
      return "OA_LOCKED";
    }
    return meta.getFactorType();
  }

  /**
   * trace 展示用来源判定：实际计算在 FactorVariableRegistryImpl 里已经优先取 OA 表头锁价，
   * 这里只补充可追溯标识，避免页面把报价单基价覆盖误显示成普通月度影响因素价。
   */
  private boolean usesQuoteBaseOverride(PriceVariable meta, VariableContext ctx) {
    if (meta == null
        || ctx == null
        || ctx.getOaForm() == null
        || ctx.isMonthlyReprice()
        || factorQuoteBaseMappingMapper == null
        || !"FINANCE_FACTOR".equalsIgnoreCase(String.valueOf(meta.getFactorType()))) {
      return false;
    }
    Long factorIdentityId = resolverParamLong(meta, "factorIdentityId");
    if (factorIdentityId == null) {
      return false;
    }
    List<FactorQuoteBaseMapping> mappings = factorQuoteBaseMappingMapper.selectList(
        Wrappers.lambdaQuery(FactorQuoteBaseMapping.class)
            .eq(FactorQuoteBaseMapping::getFactorIdentityId, factorIdentityId)
            .eq(FactorQuoteBaseMapping::getEnabled, 1)
            .eq(FactorQuoteBaseMapping::getDeleted, 0)
            .orderByAsc(FactorQuoteBaseMapping::getId));
    for (FactorQuoteBaseMapping mapping : mappings) {
      if (readOaQuoteBasePrice(ctx.getOaForm(), mapping.getQuoteFieldCode()) != null) {
        return true;
      }
    }
    return false;
  }

  private Long resolverParamLong(PriceVariable meta, String fieldName) {
    if (meta == null || !StringUtils.hasText(meta.getResolverParams())) {
      return null;
    }
    try {
      JsonNode node = objectMapper.readTree(meta.getResolverParams());
      JsonNode value = node.get(fieldName);
      if (value == null || value.isNull()) {
        return null;
      }
      if (value.isNumber()) {
        return value.longValue();
      }
      String text = value.asText();
      return StringUtils.hasText(text) ? Long.valueOf(text.trim()) : null;
    } catch (Exception e) {
      log.debug("resolver_params 读取 {} 失败: {}", fieldName, e.getMessage());
      return null;
    }
  }

  private BigDecimal readOaQuoteBasePrice(OaForm oaForm, String quoteFieldCode) {
    if (oaForm == null || !StringUtils.hasText(quoteFieldCode)) {
      return null;
    }
    try {
      Object value = new BeanWrapperImpl(oaForm).getPropertyValue(snakeToCamel(quoteFieldCode.trim()));
      if (value == null) {
        return null;
      }
      if (value instanceof BigDecimal bd) {
        return bd;
      }
      if (value instanceof Number n) {
        return new BigDecimal(n.toString());
      }
      return new BigDecimal(value.toString());
    } catch (RuntimeException e) {
      log.debug("读取 OA 报价单基价字段 {} 失败: {}", quoteFieldCode, e.getMessage());
      return null;
    }
  }

  private String snakeToCamel(String value) {
    StringBuilder builder = new StringBuilder();
    boolean upperNext = false;
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch == '_') {
        upperNext = true;
        continue;
      }
      if (upperNext) {
        builder.append(Character.toUpperCase(ch));
        upperNext = false;
      } else {
        builder.append(ch);
      }
    }
    return builder.toString();
  }

  /** 取最接近 {@code pricingMonth} 的一条 linkedItem；无精确匹配则按 materialCode 取最新一条。 */
  private PriceLinkedItem fetchLinkedItem(String materialCode, String pricingMonth) {
    if (StringUtils.hasText(pricingMonth)) {
      PriceLinkedItem exact = priceLinkedItemMapper.selectOne(
          Wrappers.lambdaQuery(PriceLinkedItem.class)
              .eq(PriceLinkedItem::getMaterialCode, materialCode)
              .eq(PriceLinkedItem::getPricingMonth, pricingMonth)
              .orderByDesc(PriceLinkedItem::getId)
              .last("LIMIT 1"));
      if (exact != null) {
        return exact;
      }
    }
    List<PriceLinkedItem> rows = priceLinkedItemMapper.selectList(
        Wrappers.lambdaQuery(PriceLinkedItem.class)
            .eq(PriceLinkedItem::getMaterialCode, materialCode)
            .orderByDesc(PriceLinkedItem::getPricingMonth)
            .orderByDesc(PriceLinkedItem::getId)
            .last("LIMIT 1"));
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 批量读 variable 元数据（name + factorType），token 不在表里返回空条目，显示为 UNKNOWN。 */
  private Map<String, PriceVariable> loadVariableMeta(Collection<String> tokens) {
    Map<String, PriceVariable> out = new LinkedHashMap<>();
    if (tokens.isEmpty()) {
      return out;
    }
    List<PriceVariable> rows = priceVariableMapper.selectList(
        Wrappers.lambdaQuery(PriceVariable.class)
            .in(PriceVariable::getVariableCode, tokens));
    for (PriceVariable row : rows) {
      out.put(row.getVariableCode(), row);
    }
    return out;
  }

  private static String safeName(PriceVariable v) {
    return StringUtils.hasText(v.getVariableName()) ? v.getVariableName() : v.getVariableCode();
  }
}
