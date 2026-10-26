package com.sanhua.marketingcost.formula.registry;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.formula.normalize.*;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.SourceEvidence;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Calculation-local captured inputs. Source acquisition belongs to the monthly source adapter. */
public final class PreparedFormulaInputs {
  private static final ObjectMapper COPIER = new ObjectMapper().findAndRegisterModules();
  private static final ObjectMapper PARAMETERS = new ObjectMapper()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
      .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
  private static final TypeReference<Map<String, Object>> PARAMETER_TYPE = new TypeReference<>() {};

  /** Static input closure, using the same normalizer and variable grammar as evaluation. */
  public record Requirements(String normalizedExpression, Set<String> variables,
      Set<Long> factorIdentityIds, boolean financialRequired, boolean scrapRequired,
      List<Map<String,Object>> financialBindings) {
    public Requirements {
      variables = Set.copyOf(variables);
      factorIdentityIds = Set.copyOf(factorIdentityIds);
      financialBindings = financialBindings.stream().map(PreparedFormulaInputs::freezeParameters).toList();
    }
  }

  /** Shared per batch: definitions, bindings and normalization all use this same captured catalog. */
  public static final class Catalog {
    private final List<PriceVariable> variables;
    private final List<RowLocalPlaceholder> placeholders;
    private final List<PriceVariableBinding> bindings;
    private final Map<String, PriceVariable> definitions;
    private final Map<Long, Map<String, PriceVariableBinding>> bindingsByRule;
    private final RowLocalPlaceholderRegistry placeholderRegistry;
    private final FormulaNormalizer normalizer;
    private final Map<String, Map<String, Object>> parameters = new ConcurrentHashMap<>();

    public Catalog(List<PriceVariable> variables, List<RowLocalPlaceholder> placeholders,
        List<PriceVariableBinding> bindings) {
      this(variables,placeholders,bindings,false);
    }

    /** Inputs must be the actual status-scoped SQL results; preserve their raw collation-matched text. */
    public static Catalog fromScopedSql(List<PriceVariable> variables,List<RowLocalPlaceholder> placeholders,
        List<PriceVariableBinding> bindings) {
      return new Catalog(variables,placeholders,bindings,true);
    }

    private Catalog(List<PriceVariable> variables,List<RowLocalPlaceholder> placeholders,
        List<PriceVariableBinding> bindings,boolean scopedSql) {
      this.variables = copies(variables, PriceVariable.class);
      this.placeholders = copies(placeholders, RowLocalPlaceholder.class);
      this.bindings = copies(bindings, PriceVariableBinding.class);
      var definitions = new LinkedHashMap<String, PriceVariable>();
      for (var row : this.variables) {
        if (!scopedSql && !"active".equals(row.getStatus()))
          throw new IllegalArgumentException("预取公式变量必须是有效定义");
        if (row.getVariableCode() != null && !row.getVariableCode().isBlank())
          definitions.put(row.getVariableCode().trim(), row);
      }
      this.definitions = Collections.unmodifiableMap(definitions);
      for (var row : this.placeholders) {
        if ((!scopedSql && !"active".equals(row.getStatus())) || !Integer.valueOf(0).equals(row.getDeleted()))
          throw new IllegalArgumentException("预取公式占位符必须是有效且未删除的配置");
      }
      var byRule = new LinkedHashMap<Long, Map<String, PriceVariableBinding>>();
      for (var row : this.bindings) {
        if (row.getLinkedItemId() == null || row.getTokenName() == null
            || row.getExpiryDate() != null || !Integer.valueOf(0).equals(row.getDeleted()))
          throw new IllegalArgumentException("预取行级绑定必须是当前有效绑定");
        var previous = byRule.computeIfAbsent(row.getLinkedItemId(), ignored -> new LinkedHashMap<>())
            .putIfAbsent(row.getTokenName(), row);
        if (previous != null) throw new IllegalArgumentException("同一公式行存在重复当前变量绑定");
      }
      var frozen = new LinkedHashMap<Long, Map<String, PriceVariableBinding>>();
      byRule.forEach((id, rows) -> frozen.put(id, Collections.unmodifiableMap(rows)));
      bindingsByRule = Collections.unmodifiableMap(frozen);
      placeholderRegistry = RowLocalPlaceholderRegistry.captured(this.placeholders, COPIER);
      normalizer = new FormulaNormalizer(VariableAliasIndex.captured(this.variables), placeholderRegistry);
    }

    public List<PriceVariable> variables() { return copies(variables, PriceVariable.class); }
    public List<RowLocalPlaceholder> placeholders() { return copies(placeholders, RowLocalPlaceholder.class); }
    public List<PriceVariableBinding> bindings() { return copies(bindings, PriceVariableBinding.class); }

    public Requirements requirements(PriceLinkedItem rule) {
      Objects.requireNonNull(rule, "联动规则必填");
      return new InputClosure(rule).collect();
    }

    private Map<String, Object> parameters(String code) {
      return parameters.computeIfAbsent(code, key -> {
        var variable = definitions.get(key);
        if (variable == null) throw new IllegalArgumentException("公式变量未定义：" + key);
        String json = variable.getResolverParams();
        if (json == null || json.isBlank()) return Map.of();
        try {
          return freezeParameters(PARAMETERS.readValue(json, PARAMETER_TYPE));
        } catch (Exception failure) {
          throw new IllegalArgumentException("公式变量参数无效：" + key, failure);
        }
      });
    }

    private final class InputClosure {
      private final PriceLinkedItem rule;
      private final Set<String> variables = new LinkedHashSet<>();
      private final Set<String> visiting = new LinkedHashSet<>();
      private final Set<Long> identities = new LinkedHashSet<>();
      private final List<Map<String,Object>> financialBindings = new ArrayList<>();
      private boolean financial;
      private boolean scrap;

      private InputClosure(PriceLinkedItem rule) { this.rule = rule; }

      private Requirements collect() {
        // The existing technical evaluator reads financial context even for a constant expression.
        financial = "TECH_SUPPLEMENTAL".equals(rule.getSourceKind());
        String expression = normalizer.normalize(rule.getFormulaExpr());
        ExpressionEvaluator.validate(expression);
        visitExpression(expression);
        if (Integer.valueOf(0).equals(rule.getTaxIncluded())) visit("vat_rate");
        return new Requirements(expression, variables, identities, financial, scrap, financialBindings);
      }

      private void visitExpression(String expression) {
        if (expression == null || expression.isBlank())
          throw new IllegalArgumentException("公式依赖表达式为空");
        ExpressionEvaluator.extractVariables(expression).forEach(this::visit);
      }

      private void visit(String code) {
        if (visiting.contains(code)) throw new IllegalArgumentException("公式变量循环依赖：" + visiting + " -> " + code);
        if (variables.contains(code)) return;
        if (visiting.size() >= 256) throw new IllegalArgumentException("公式变量依赖超过256层");
        visiting.add(code);
        try {
          if (placeholderRegistry.isKnown(code)) visitRowBinding(code);
          else visitDefinition(code);
          variables.add(code);
        } finally {
          visiting.remove(code);
        }
      }

      private void visitRowBinding(String code) {
        var current = bindingsByRule.getOrDefault(rule.getId(), Map.of());
        var binding = placeholderRegistry.tokenNames().getOrDefault(code, List.of()).stream()
            .map(current::get).filter(Objects::nonNull).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("公式行级变量未绑定：" + code));
        if (binding.getFactorIdentityId() != null) {
          financial = true;
          identities.add(binding.getFactorIdentityId());
          financialBindings.add(Map.of("factorIdentityId",binding.getFactorIdentityId()));
        } else {
          String dependency = binding.getFactorCode();
          // The registry requires this exact registered code before following a row binding.
          if (!definitions.containsKey(dependency))
            throw new IllegalArgumentException("公式行级绑定变量未定义：" + dependency);
          visit(dependency);
        }
      }

      private void visitDefinition(String code) {
        var variable = definitions.get(code);
        if (variable == null) throw new IllegalArgumentException("公式变量未定义：" + code);
        var params = parameters(code);
        String kind = variable.getResolverKind();
        if (kind == null) throw new IllegalArgumentException("公式变量未配置解析类型：" + code);
        switch (kind.trim().toUpperCase(Locale.ROOT)) {
          case "FINANCE" -> {
            financial = true;
            Long id = FinanceFactorBinding.identityId(params);
            if (id != null) identities.add(id);
            financialBindings.add(params);
          }
          case "FORMULA" -> visitExpression(text(params.get("expr")));
          case "DERIVED" -> visitDerived(variable, params);
          case "CONST", "ENTITY" -> { }
          default -> throw new IllegalArgumentException("公式变量解析类型无效：" + code);
        }
      }

      private void visitDerived(PriceVariable variable, Map<String, Object> params) {
        String strategy = text(params.get("strategy"));
        switch (strategy == null ? "" : strategy.toUpperCase(Locale.ROOT)) {
          case "MAIN_MATERIAL_FINANCE", "FINANCE_FACTOR" -> financial = true;
          case "FORMULA_REF" -> visitExpression(text(params.get("formulaRef")));
          case "SCRAP_REF" -> {
            scrap = true;
            if ("INCL".equals(variable.getTaxMode())) visit("vat_rate");
            else if (!"EXCL".equals(variable.getTaxMode()))
              throw new IllegalArgumentException("废料变量税价口径无效：" + variable.getVariableCode());
          }
          default -> throw new IllegalArgumentException("公式派生策略无效：" + variable.getVariableCode());
        }
      }
    }
  }

  /** privatePrices must be the scoped, ordered SQL results from MonthlyPrivateFactorInputMapper. */
  public record Financial(PriceFactorContext factors, Map<Long, FactorIdentity> identities,
      Set<String> managedFactors, List<FactorMonthlyPrice> privatePrices) {
    public Financial(PriceFactorContext factors, Map<Long, FactorIdentity> identities,
        Set<String> managedFactors) {
      this(factors, identities, managedFactors, List.of());
    }
    public Financial {
      identities = copyMap(identities, FactorIdentity.class);
      managedFactors = Set.copyOf(managedFactors);
      privatePrices = copies(privatePrices, FactorMonthlyPrice.class);
      if (factors != null && factors.customerCopperPricePerTon() != null)
        throw new IllegalArgumentException("公共公式P0不能包含客户Cu");
    }

    @Override public Map<Long, FactorIdentity> identities() {
      return copyMap(identities, FactorIdentity.class);
    }

    @Override public List<FactorMonthlyPrice> privatePrices() {
      return copies(privatePrices, FactorMonthlyPrice.class);
    }
  }

  private final Catalog catalog;
  private final Financial financial;
  private final Scrap scrap;
  private final PriceFactorContext customerFactors;

  /** The existing formula scrap contract is an independent CNY/KG excluded-tax price. */
  public record ScrapPricePerKgExclTax(BigDecimal cnyAmount, SourceEvidence evidence) {
    public ScrapPricePerKgExclTax {
      Objects.requireNonNull(cnyAmount, "废料依赖价格必填");
      Objects.requireNonNull(evidence, "废料依赖依据必填");
      if (cnyAmount.signum() < 0) throw new IllegalArgumentException("废料依赖价格不能为负数");
    }
  }

  /** Mapping rows and child facts are acquired before evaluation, never by the derived resolver. */
  public static final class Scrap {
    private final Map<String, List<MaterialScrapRef>> references;
    private final Map<String, ScrapPricePerKgExclTax> prices;

    public Scrap(Map<String, List<MaterialScrapRef>> references,
        Map<String, ScrapPricePerKgExclTax> prices) {
      var frozen = new LinkedHashMap<String, List<MaterialScrapRef>>();
      references.forEach((code, rows) -> frozen.put(code, copies(rows, MaterialScrapRef.class)));
      this.references = Collections.unmodifiableMap(frozen);
      this.prices = Map.copyOf(prices);
    }

    public static Scrap empty() { return new Scrap(Map.of(), Map.of()); }

    public Map<String, List<MaterialScrapRef>> references() {
      var detached = new LinkedHashMap<String, List<MaterialScrapRef>>();
      references.forEach((code, rows) -> detached.put(code, copies(rows, MaterialScrapRef.class)));
      return Collections.unmodifiableMap(detached);
    }

    public Map<String, ScrapPricePerKgExclTax> prices() { return prices; }

    List<MaterialScrapRef> referencesFor(String code) {
      if (!references.containsKey(code))
        throw new IllegalArgumentException("公式废料依赖映射尚未预取：" + code);
      return copies(references.get(code), MaterialScrapRef.class);
    }

    ScrapPricePerKgExclTax priceFor(String code) {
      var selected = prices.get(code);
      if (selected == null) throw new IllegalArgumentException("公式废料依赖缺少已捕获月价：" + code);
      return selected;
    }
  }

  public PreparedFormulaInputs(Catalog catalog, Financial financial) {
    this(catalog, financial, Scrap.empty());
  }

  public PreparedFormulaInputs(Catalog catalog, Financial financial, Scrap scrap) {
    this(catalog, financial, scrap, null);
  }

  private PreparedFormulaInputs(Catalog catalog, Financial financial, Scrap scrap,
      PriceFactorContext customerFactors) {
    this.catalog = Objects.requireNonNull(catalog);
    this.financial = Objects.requireNonNull(financial);
    this.scrap = Objects.requireNonNull(scrap);
    this.customerFactors = customerFactors;
  }

  /** Quote-local projection; the captured public Financial inputs remain unchanged. */
  public PreparedFormulaInputs withCustomerCopper(BigDecimal copperPerTon, Scrap customerScrap) {
    var factors = financial.factors();
    return new PreparedFormulaInputs(catalog, financial, customerScrap,
        factors == null ? null : factors.withCustomerCopperPerTon(copperPerTon));
  }

  public Catalog catalog() { return catalog; }
  public Financial financial() { return financial; }
  public Scrap scrap() { return scrap; }

  public Map<String, PriceVariable> variables() {
    return copyMap(catalog.definitions, PriceVariable.class);
  }

  /** Only requested display metadata is copied; the complete catalog is shared across calculations. */
  public Map<String, PriceVariable> variableMetadata(Set<String> names) {
    var selected = new LinkedHashMap<String, PriceVariable>();
    names.forEach(name -> {
      var row = catalog.definitions.get(name);
      if (row != null) selected.put(name, copy(row, PriceVariable.class));
    });
    return Collections.unmodifiableMap(selected);
  }

  public List<PriceVariable> selectedVariables(Set<String> names) {
    return names.stream().sorted().map(catalog.definitions::get).filter(Objects::nonNull)
        .map(row -> copy(row, PriceVariable.class)).toList();
  }

  public Map<String, PriceVariableBinding> bindings(Long id) {
    return copyMap(calculationBindings(id), PriceVariableBinding.class);
  }

  // Registry collaborators only read these internal entities; external accessors return detached copies.
  Map<String, PriceVariable> calculationVariables() { return catalog.definitions; }
  Map<String, Object> calculationParameters(PriceVariable variable) {
    return catalog.parameters(variable.getVariableCode().trim());
  }
  Map<String, PriceVariableBinding> calculationBindings(Long id) {
    return catalog.bindingsByRule.getOrDefault(id, Map.of());
  }
  FactorIdentity capturedIdentity(Long id) {
    var identity = financial.identities.get(id);
    return identity == null ? null : copy(identity, FactorIdentity.class);
  }
  boolean isManaged(String key) { return financial.managedFactors.contains(key); }
  RowLocalPlaceholderRegistry placeholders() { return catalog.placeholderRegistry; }

  /** Preserve the SQL-selected head; only the old ID fallback uses exact Java state checks. */
  Optional<FactorMonthlyPrice> privateFactor(Long identityId, Long snapshotId, String month) {
    if (identityId == null || month == null || month.isBlank()) return Optional.empty();
    var matching = financial.privatePrices.stream()
        .filter(row -> identityId.equals(row.getFactorIdentityId()))
        .toList();
    var head = matching.stream().findFirst().orElse(null);
    if (head != null && head.getPrice() != null)
      return Optional.of(copy(head, FactorMonthlyPrice.class));
    // Do not remove null-valued heads and silently select an older amount.
    return snapshotId == null ? Optional.empty() : matching.stream()
        .filter(row -> snapshotId.equals(row.getId()) && month.equals(row.getPriceMonth())
            && "LEGACY".equals(row.getSchemeCode()) && "ACTIVE".equals(row.getStatus())
            && row.getPrice() != null)
        .findFirst().map(row -> copy(row, FactorMonthlyPrice.class));
  }

  public String normalize(String expression) { return catalog.normalizer.normalize(expression); }

  public void validate(PriceLinkedItem rule, String month) {
    Objects.requireNonNull(rule, "联动规则必填");
    String caller = com.sanhua.marketingcost.security.BusinessUnitContext.getCurrentBusinessUnitType();
    if (caller != null && !caller.isBlank() && !caller.equals(rule.getBusinessUnitType()))
      throw new IllegalArgumentException("规则与当前业务单元不一致");
    var factors = financial.factors();
    if (factors != null && (!rule.getMaterialCode().equals(factors.materialCode())
        || !rule.getBusinessUnitType().equals(factors.financial().scope().businessUnitType())
        || !rule.getOrgCode().equals(factors.financial().scope().priceOrgCode())
        || !month.equals(factors.months().requestedMonth())))
      throw new IllegalArgumentException("公式规则与预取财务上下文不一致");
  }

  public PriceFactorContext requireFinancial(PriceLinkedItem rule, String month) {
    validate(rule, month);
    return Objects.requireNonNull(customerFactors == null ? financial.factors() : customerFactors,
        "公式未预取财务因素");
  }

  private static <T> T copy(T value, Class<T> type) {
    return COPIER.convertValue(value, type);
  }

  private static String text(Object value) { return value == null ? null : value.toString(); }

  private static Map<String, Object> freezeParameters(Map<String, Object> values) {
    var result = new LinkedHashMap<String, Object>();
    values.forEach((key, value) -> result.put(key, freezeParameter(value)));
    return Collections.unmodifiableMap(result);
  }

  private static Object freezeParameter(Object value) {
    if (value instanceof Map<?, ?> map) {
      var result = new LinkedHashMap<String, Object>();
      map.forEach((key, nested) -> result.put(key.toString(), freezeParameter(nested)));
      return Collections.unmodifiableMap(result);
    }
    if (value instanceof List<?> list) return list.stream().map(PreparedFormulaInputs::freezeParameter).toList();
    return value;
  }

  private static <T> List<T> copies(Collection<T> values, Class<T> type) {
    return values.stream().map(value -> copy(value, type)).toList();
  }

  private static <K, T> Map<K, T> copyMap(Map<K, T> values, Class<T> type) {
    var copy = new LinkedHashMap<K, T>();
    values.forEach((key, value) -> copy.put(key, copy(value, type)));
    return Collections.unmodifiableMap(copy);
  }
}
