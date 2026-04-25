package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.PriceVariableRequest;
import com.sanhua.marketingcost.dto.VariableCatalogResponse;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.formula.normalize.FormulaNormalizer;
import com.sanhua.marketingcost.formula.normalize.FormulaSyntaxException;
import com.sanhua.marketingcost.formula.normalize.FormulaValidator;
import com.sanhua.marketingcost.formula.registry.FactorVariableRegistryImpl;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceVariableService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 价格变量服务实现 —— {@link #list(String)} 给后台管理页平铺，
 * {@link #catalog()} 给前端公式编辑器做三分组树。
 *
 * <p>Plan B T9a：新增 CRUD（{@link #create}/{@link #update}/{@link #softDelete}），
 * 每次写入后触发 {@link FactorVariableRegistryImpl#invalidate()} 让缓存重新加载，
 * 避免"改了变量但要等到重启才生效"的旧 bug。
 */
@Service
public class PriceVariableServiceImpl implements PriceVariableService {

  private static final Logger log = LoggerFactory.getLogger(PriceVariableServiceImpl.class);

  /** 仅展示启用态 —— V24 seed 里 status 取值是小写 {@code 'active'}。 */
  private static final String STATUS_ACTIVE = "active";
  private static final String STATUS_INACTIVE = "inactive";

  /** factor_type 合法值 —— 严格与 V24 seed / catalog 三分组保持一致 */
  private static final Set<String> VALID_FACTOR_TYPES =
      Set.of("FINANCE_FACTOR", "PART_CONTEXT", "FORMULA_REF", "CONST");

  /** resolver_kind 合法值 —— 严格与 V31 回填脚本 / Registry 分派保持一致 */
  private static final Set<String> VALID_RESOLVER_KINDS =
      Set.of("FINANCE", "ENTITY", "DERIVED", "FORMULA", "CONST");

  /** DERIVED.strategy 合法值 —— 与 DerivedContextResolver 实现保持一致 */
  private static final Set<String> VALID_DERIVED_STRATEGIES =
      Set.of("MAIN_MATERIAL_FINANCE", "SCRAP_REF", "FORMULA_REF", "FINANCE_FACTOR");

  private final PriceVariableMapper priceVariableMapper;
  private final FinanceBasePriceMapper financeBasePriceMapper;
  private final FactorVariableRegistryImpl factorVariableRegistry;
  private final ObjectMapper objectMapper;
  /** 公式 normalize 管线 —— 把 FORMULA.expr / DERIVED.formulaRef 的中文/全角/单位剥干净，统一到 {@code [code]} 形式 */
  private final FormulaNormalizer formulaNormalizer;
  /** 公式结构 & code 白名单校验 —— 拦相邻 value 缺算符 / 未知 code 等脏点，避免求值期悄悄算成 0 */
  private final FormulaValidator formulaValidator;

  public PriceVariableServiceImpl(
      PriceVariableMapper priceVariableMapper,
      FinanceBasePriceMapper financeBasePriceMapper,
      FactorVariableRegistryImpl factorVariableRegistry,
      ObjectMapper objectMapper,
      FormulaNormalizer formulaNormalizer,
      FormulaValidator formulaValidator) {
    this.priceVariableMapper = priceVariableMapper;
    this.financeBasePriceMapper = financeBasePriceMapper;
    this.factorVariableRegistry = factorVariableRegistry;
    this.objectMapper = objectMapper;
    this.formulaNormalizer = formulaNormalizer;
    this.formulaValidator = formulaValidator;
  }

  @Override
  public List<PriceVariable> list(String status) {
    var query = Wrappers.lambdaQuery(PriceVariable.class);
    if (StringUtils.hasText(status)) {
      query.eq(PriceVariable::getStatus, status.trim());
    }
    query.orderByAsc(PriceVariable::getId);
    return priceVariableMapper.selectList(query);
  }

  @Override
  public VariableCatalogResponse catalog() {
    VariableCatalogResponse resp = new VariableCatalogResponse();

    // 阶段 1：拉启用态变量并按 factor_type 分桶；CONST 不展示。
    List<PriceVariable> enabled = priceVariableMapper.selectList(
        Wrappers.lambdaQuery(PriceVariable.class)
            .eq(PriceVariable::getStatus, STATUS_ACTIVE)
            .orderByAsc(PriceVariable::getId));

    List<PriceVariable> financeVars = new ArrayList<>();
    List<PriceVariable> partVars = new ArrayList<>();
    List<PriceVariable> formulaVars = new ArrayList<>();
    for (PriceVariable v : enabled) {
      String type = v.getFactorType();
      if ("FINANCE_FACTOR".equals(type)) {
        financeVars.add(v);
      } else if ("PART_CONTEXT".equals(type)) {
        partVars.add(v);
      } else if ("FORMULA_REF".equals(type)) {
        formulaVars.add(v);
      }
      // CONST 无需进入前端下拉
    }

    // 阶段 2：批量预取财务因素最新价格 —— 一次 IN 查询，按 short_name 取最早出现者（已按月份倒序）。
    Map<String, FinanceBasePrice> latestByShortName =
        loadLatestFinancePrices(financeVars);

    // 阶段 3：组装三组
    for (PriceVariable v : financeVars) {
      resp.getFinanceFactors().add(toFinanceFactor(v, latestByShortName.get(v.getVariableName())));
    }
    for (PriceVariable v : partVars) {
      resp.getPartContexts().add(toPartContext(v));
    }
    for (PriceVariable v : formulaVars) {
      resp.getFormulaRefs().add(toFormulaRef(v));
    }
    return resp;
  }

  @Override
  public PriceVariable getById(Long id) {
    PriceVariable v = priceVariableMapper.selectById(id);
    if (v == null) {
      throw new IllegalArgumentException("变量不存在：id=" + id);
    }
    return v;
  }

  @Override
  public Long create(PriceVariableRequest request) {
    validate(request, true);
    // 唯一性检查 —— 同 code 已存在直接拒
    Long existing = priceVariableMapper.selectCount(
        Wrappers.lambdaQuery(PriceVariable.class)
            .eq(PriceVariable::getVariableCode, request.getVariableCode()));
    if (existing != null && existing > 0) {
      throw new IllegalArgumentException("变量编码已存在：" + request.getVariableCode());
    }

    PriceVariable entity = new PriceVariable();
    copyRequestToEntity(request, entity);
    // 新增默认启用；前端若显式传 inactive 也尊重
    if (!StringUtils.hasText(entity.getStatus())) {
      entity.setStatus(STATUS_ACTIVE);
    }
    priceVariableMapper.insert(entity);
    factorVariableRegistry.invalidate();
    log.info("新增价格变量：id={} code={} kind={}", entity.getId(),
        entity.getVariableCode(), entity.getResolverKind());
    return entity.getId();
  }

  @Override
  public void update(Long id, PriceVariableRequest request) {
    PriceVariable existing = getById(id);
    validate(request, false);
    // variableCode 是外部引用标识，公式里的 [code] 依赖它；改名会把历史 formula 全部打断
    if (!existing.getVariableCode().equals(request.getVariableCode())) {
      throw new IllegalArgumentException("variableCode 不允许修改（影响历史公式引用）");
    }
    copyRequestToEntity(request, existing);
    priceVariableMapper.updateById(existing);
    factorVariableRegistry.invalidate();
    log.info("更新价格变量：id={} code={} kind={}", existing.getId(),
        existing.getVariableCode(), existing.getResolverKind());
  }

  @Override
  public void softDelete(Long id) {
    PriceVariable existing = getById(id);
    existing.setStatus(STATUS_INACTIVE);
    priceVariableMapper.updateById(existing);
    factorVariableRegistry.invalidate();
    log.info("软删价格变量：id={} code={}", id, existing.getVariableCode());
  }

  // ============================ 私有工具 ============================

  /**
   * 统一把 request 拷到 entity —— 新增 / 更新共用。
   *
   * <p>resolverParams 由 Map 再序列化成字符串存到 {@code resolver_params} TEXT 列，
   * 保持与 MyBatis Plus 现状一致（不引入 TypeHandler 改造）。
   */
  private void copyRequestToEntity(PriceVariableRequest req, PriceVariable entity) {
    entity.setVariableCode(req.getVariableCode().trim());
    entity.setVariableName(req.getVariableName().trim());
    entity.setAliasesJson(req.getAliasesJson());
    entity.setFactorType(req.getFactorType());
    entity.setResolverKind(req.getResolverKind());
    // V0 遗留字段 source_type 是 NOT NULL 无默认值 —— 新管线不读它（用 resolver_kind 替代），
    // 但 insert 仍需要填值避免 SQL 约束报错。用 resolverKind 同步填充，保持一致语义。
    if (entity.getSourceType() == null || entity.getSourceType().isBlank()) {
      entity.setSourceType(req.getResolverKind());
    }
    entity.setTaxMode(req.getTaxMode());
    entity.setBusinessUnitType(req.getBusinessUnitType());
    entity.setStatus(req.getStatus());
    entity.setScope(req.getScope());
    entity.setDefaultValue(req.getDefaultValue());
    try {
      entity.setResolverParams(objectMapper.writeValueAsString(req.getResolverParams()));
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("resolverParams 序列化失败：" + e.getOriginalMessage());
    }
  }

  /**
   * 校验 —— 确保 factorType / resolverKind 合法 + resolverParams 按 kind 的 schema 对齐。
   *
   * <p>{@code isCreate=true} 时 variableCode 必填格式校验（实际 Pattern 注解已做，此处兜底）。
   */
  private void validate(PriceVariableRequest req, boolean isCreate) {
    if (req == null) {
      throw new IllegalArgumentException("请求体不能为空");
    }
    if (!VALID_FACTOR_TYPES.contains(req.getFactorType())) {
      throw new IllegalArgumentException(
          "factorType 非法：" + req.getFactorType() + "，合法值：" + VALID_FACTOR_TYPES);
    }
    if (!VALID_RESOLVER_KINDS.contains(req.getResolverKind())) {
      throw new IllegalArgumentException(
          "resolverKind 非法：" + req.getResolverKind() + "，合法值：" + VALID_RESOLVER_KINDS);
    }
    Map<String, Object> params = req.getResolverParams();
    if (params == null) {
      throw new IllegalArgumentException("resolverParams 必填");
    }
    validateParamsByKind(req.getResolverKind(), params);
  }

  /** 按 kind 做 resolverParams 必填字段检查 —— 与 FactorVariableRegistryImpl 实际读的 key 对齐 */
  private void validateParamsByKind(String kind, Map<String, Object> params) {
    switch (kind) {
      case "FINANCE" -> {
        boolean hasFactorCode = hasText(params.get("factorCode"));
        boolean hasShortName = hasText(params.get("shortName"));
        if (!hasFactorCode && !hasShortName) {
          throw new IllegalArgumentException(
              "FINANCE 参数：factorCode 和 shortName 至少填一个");
        }
        if (!hasText(params.get("priceSource"))) {
          throw new IllegalArgumentException("FINANCE 参数：priceSource 必填");
        }
      }
      case "ENTITY" -> {
        if (!hasText(params.get("entity"))) {
          throw new IllegalArgumentException("ENTITY 参数：entity 必填（如 linkedItem）");
        }
        if (!hasText(params.get("field"))) {
          throw new IllegalArgumentException("ENTITY 参数：field 必填（如 blankWeight）");
        }
      }
      case "DERIVED" -> {
        Object strategy = params.get("strategy");
        if (!hasText(strategy) || !VALID_DERIVED_STRATEGIES.contains(strategy.toString())) {
          throw new IllegalArgumentException(
              "DERIVED 参数：strategy 必填且须 ∈ " + VALID_DERIVED_STRATEGIES);
        }
        if ("FORMULA_REF".equals(strategy)) {
          if (!hasText(params.get("formulaRef"))) {
            throw new IllegalArgumentException(
                "DERIVED strategy=FORMULA_REF 时 formulaRef 必填");
          }
          // 走一次规范化 + 结构校验，把 params.formulaRef 写成 [code] 正则形
          params.put("formulaRef", normalizeAndValidate(
              params.get("formulaRef").toString(), "DERIVED.formulaRef"));
        }
        if ("FINANCE_FACTOR".equals(strategy) && !hasText(params.get("factorCode"))) {
          throw new IllegalArgumentException(
              "DERIVED strategy=FINANCE_FACTOR 时 factorCode 必填");
        }
      }
      case "FORMULA" -> {
        if (!hasText(params.get("expr"))) {
          throw new IllegalArgumentException("FORMULA 参数：expr 必填");
        }
        // 同 DERIVED.formulaRef：入库前把 expr 规范化为 [code] 形式并做结构校验
        params.put("expr", normalizeAndValidate(
            params.get("expr").toString(), "FORMULA.expr"));
      }
      case "CONST" -> {
        if (params.get("value") == null) {
          throw new IllegalArgumentException("CONST 参数：value 必填");
        }
      }
      default -> throw new IllegalArgumentException("未知 resolverKind：" + kind);
    }
  }

  private static boolean hasText(Object o) {
    return o != null && StringUtils.hasText(o.toString());
  }

  /**
   * 把用户提交的原始公式（可能含中文别名、全角字符、单位注释）规范化成 {@code [code]} 形式，
   * 再用 {@link FormulaValidator} 拦一轮结构/白名单错误。
   *
   * <p>为什么入库前必须做这一步：
   * <ul>
   *   <li>不规范化 → DB 里会出现中英文混排字符串（历史 bug：{@code 下料重量*Cu}），
   *       求值期遇到未知 token 只能静默算 0，调试极难；</li>
   *   <li>不校验 → 用户可写 {@code [Cu][Zn]}（缺算符）或 {@code [typo_code]}（白名单外），
   *       求值期同样静默出错。</li>
   * </ul>
   * 抛 {@link IllegalArgumentException}（由 {@link FormulaSyntaxException} 转译），
   * 让 GlobalExceptionHandler 统一返回 400 并带上原因。
   *
   * @param raw 用户提交的原始公式
   * @param location 字段定位串（用于错误消息，如 {@code "FORMULA.expr"}）
   * @return 规范化后的表达式（全部 {@code [code]} 形式）
   */
  private String normalizeAndValidate(String raw, String location) {
    try {
      String normalized = formulaNormalizer.normalize(raw);
      formulaValidator.validate(normalized);
      return normalized;
    } catch (FormulaSyntaxException e) {
      throw new IllegalArgumentException(
          location + " 公式非法：" + e.getMessage(), e);
    }
  }

  /**
   * 批量拉 FINANCE_FACTOR 变量对应的最新基准价。
   *
   * <p>按 {@code lp_finance_base_price.short_name IN (...)} 一次查完，再按
   * {@code price_month desc, id desc} 排序，遇到新 shortName 先写入即是该因素最新一期，
   * 相同 shortName 后续记录忽略。避免 N+1 查询。
   */
  private Map<String, FinanceBasePrice> loadLatestFinancePrices(List<PriceVariable> financeVars) {
    if (financeVars.isEmpty()) {
      return Map.of();
    }
    List<String> shortNames = financeVars.stream()
        .map(PriceVariable::getVariableName)
        .filter(StringUtils::hasText)
        .distinct()
        .collect(Collectors.toList());
    if (shortNames.isEmpty()) {
      return Map.of();
    }
    List<FinanceBasePrice> rows = financeBasePriceMapper.selectList(
        Wrappers.lambdaQuery(FinanceBasePrice.class)
            .in(FinanceBasePrice::getShortName, shortNames)
            .orderByDesc(FinanceBasePrice::getPriceMonth)
            .orderByDesc(FinanceBasePrice::getId));
    Map<String, FinanceBasePrice> result = new HashMap<>();
    for (FinanceBasePrice row : rows) {
      result.putIfAbsent(row.getShortName(), row);
    }
    return result;
  }

  private VariableCatalogResponse.FinanceFactor toFinanceFactor(
      PriceVariable v, FinanceBasePrice latest) {
    VariableCatalogResponse.FinanceFactor item = new VariableCatalogResponse.FinanceFactor();
    item.setCode(v.getVariableCode());
    item.setName(v.getVariableName());
    if (latest != null) {
      item.setCurrentPrice(latest.getPrice());
      item.setUnit(latest.getUnit());
      item.setSource(latest.getPriceSource());
      item.setPricingMonth(latest.getPriceMonth());
    }
    return item;
  }

  private VariableCatalogResponse.PartContext toPartContext(PriceVariable v) {
    VariableCatalogResponse.PartContext item = new VariableCatalogResponse.PartContext();
    item.setCode(v.getVariableCode());
    item.setName(v.getVariableName());
    item.setBinding(v.getContextBindingJson());
    return item;
  }

  private VariableCatalogResponse.FormulaRef toFormulaRef(PriceVariable v) {
    VariableCatalogResponse.FormulaRef item = new VariableCatalogResponse.FormulaRef();
    item.setCode(v.getVariableCode());
    item.setName(v.getVariableName());
    item.setFormulaExpr(v.getFormulaExpr());
    return item;
  }
}
