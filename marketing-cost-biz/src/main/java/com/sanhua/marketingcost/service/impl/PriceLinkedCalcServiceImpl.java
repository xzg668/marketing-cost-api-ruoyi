package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceLinkedCalcRow;
import com.sanhua.marketingcost.entity.BomManageItem;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.PriceLinkedCalcItem;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.mapper.BomManageItemMapper;
import com.sanhua.marketingcost.mapper.DynamicValueMapper;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedCalcItemMapper;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceLinkedCalcService;

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

@Service
public class PriceLinkedCalcServiceImpl implements PriceLinkedCalcService {
  private static final String LINKED_PRICE_TYPE = "联动价";
  private static final Pattern VARIABLE_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9_]+$");
  private static final String FINANCE_PRICE_LATEST = "__latest__";
  private static final BigDecimal WEIGHT_DIVISOR = new BigDecimal("1000");

  private final BomManageItemMapper bomManageItemMapper;
  private final PriceLinkedCalcItemMapper priceLinkedCalcItemMapper;
  private final PriceLinkedItemMapper priceLinkedItemMapper;
  private final PriceVariableMapper priceVariableMapper;
  private final OaFormMapper oaFormMapper;
  private final FinanceBasePriceMapper financeBasePriceMapper;
  private final DynamicValueMapper dynamicValueMapper;

  public PriceLinkedCalcServiceImpl(
      BomManageItemMapper bomManageItemMapper,
      PriceLinkedCalcItemMapper priceLinkedCalcItemMapper,
      PriceLinkedItemMapper priceLinkedItemMapper,
      PriceVariableMapper priceVariableMapper,
      OaFormMapper oaFormMapper,
      FinanceBasePriceMapper financeBasePriceMapper,
      DynamicValueMapper dynamicValueMapper) {
    this.bomManageItemMapper = bomManageItemMapper;
    this.priceLinkedCalcItemMapper = priceLinkedCalcItemMapper;
    this.priceLinkedItemMapper = priceLinkedItemMapper;
    this.priceVariableMapper = priceVariableMapper;
    this.oaFormMapper = oaFormMapper;
    this.financeBasePriceMapper = financeBasePriceMapper;
    this.dynamicValueMapper = dynamicValueMapper;
  }

  @Override
  public Page<PriceLinkedCalcRow> page(
      String oaNo, String itemCode, String shapeAttr, int page, int pageSize) {
    var query = Wrappers.lambdaQuery(BomManageItem.class);
    if (StringUtils.hasText(oaNo)) {
      query.like(BomManageItem::getOaNo, oaNo.trim());
    }
    if (StringUtils.hasText(itemCode)) {
      query.like(BomManageItem::getItemCode, itemCode.trim());
    }
    if (StringUtils.hasText(shapeAttr)) {
      query.eq(BomManageItem::getShapeAttr, shapeAttr.trim());
    }
    query.apply(
        "exists (select 1 from lp_material_price_type p "
            + "where p.material_code = lp_bom_manage_item.item_code "
            + "and p.material_shape = lp_bom_manage_item.shape_attr "
            + "and p.price_type = {0})",
        LINKED_PRICE_TYPE);
    query.orderByAsc(BomManageItem::getOaNo).orderByAsc(BomManageItem::getId);
    Page<BomManageItem> pager = new Page<>(page, pageSize);
    Page<BomManageItem> bomPage = bomManageItemMapper.selectPage(pager, query);
    List<BomManageItem> records = bomPage.getRecords();
    Map<String, PriceLinkedCalcItem> calcMap = fetchCalcItems(records, oaNo);
    ensureCalcItems(records, calcMap);
    List<PriceLinkedCalcRow> rows = new ArrayList<>();
    for (BomManageItem item : records) {
      PriceLinkedCalcRow row = new PriceLinkedCalcRow();
      row.setOaNo(item.getOaNo());
      row.setItemCode(item.getItemCode());
      row.setShapeAttr(item.getShapeAttr());
      row.setBomQty(item.getBomQty());
      String key = buildKey(item.getOaNo(), item.getItemCode(), item.getShapeAttr());
      PriceLinkedCalcItem calcItem = calcMap.get(key);
      if (calcItem != null) {
        row.setPartUnitPrice(calcItem.getPartUnitPrice());
        row.setPartAmount(calcItem.getPartAmount());
        if (row.getBomQty() == null) {
          row.setBomQty(calcItem.getBomQty());
        }
      }
      rows.add(row);
    }
    Page<PriceLinkedCalcRow> result = new Page<>(page, pageSize);
    result.setTotal(bomPage.getTotal());
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
    List<BomManageItem> items = bomManageItemMapper.selectList(
        Wrappers.lambdaQuery(BomManageItem.class)
            .eq(BomManageItem::getOaNo, oaNoValue)
            .apply(
                "exists (select 1 from lp_material_price_type p "
                    + "where p.material_code = lp_bom_manage_item.item_code "
                    + "and p.material_shape = lp_bom_manage_item.shape_attr "
                    + "and p.price_type = {0})",
                LINKED_PRICE_TYPE));
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
    for (BomManageItem item : items) {
      String itemCode = item.getItemCode();
      String normalizedItemCode = itemCode == null ? null : itemCode.trim();
      String key = buildKey(item.getOaNo(), itemCode, item.getShapeAttr());
      if (!handled.add(key)) {
        continue;
      }
      PriceLinkedCalcItem calcItem = refreshedCalcMap.get(key);
      if (calcItem == null) {
        continue;
      }
      if (item.getBomQty() != null
          && (calcItem.getBomQty() == null
          || item.getBomQty().compareTo(calcItem.getBomQty()) != 0)) {
        calcItem.setBomQty(item.getBomQty());
      }
      PriceLinkedItem linkedItem = normalizedItemCode == null
          ? null
          : linkedItemMap.get(normalizedItemCode);
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
      if (updated) {
        priceLinkedCalcItemMapper.updateById(calcItem);
        changed += 1;
      }
    }
    return changed;
  }

  private int ensureCalcItems(List<BomManageItem> items, Map<String, PriceLinkedCalcItem> calcMap) {
    if (items == null || items.isEmpty()) {
      return 0;
    }
    int changed = 0;
    Set<String> handled = new HashSet<>();
    for (BomManageItem item : items) {
      String key = buildKey(item.getOaNo(), item.getItemCode(), item.getShapeAttr());
      if (!handled.add(key)) {
        continue;
      }
      PriceLinkedCalcItem existing = calcMap.get(key);
      if (existing == null) {
        PriceLinkedCalcItem calc = new PriceLinkedCalcItem();
        calc.setOaNo(item.getOaNo());
        calc.setItemCode(item.getItemCode());
        calc.setShapeAttr(item.getShapeAttr());
        calc.setBomQty(item.getBomQty());
        priceLinkedCalcItemMapper.insert(calc);
        changed += 1;
      } else if (item.getBomQty() != null
          && (existing.getBomQty() == null
          || item.getBomQty().compareTo(existing.getBomQty()) != 0)) {
        existing.setBomQty(item.getBomQty());
        priceLinkedCalcItemMapper.updateById(existing);
        changed += 1;
      }
    }
    return changed;
  }

  private Map<String, PriceLinkedCalcItem> fetchCalcItems(
      List<BomManageItem> items, String oaNo) {
    if (items == null || items.isEmpty()) {
      return Map.of();
    }
    Set<String> itemCodes = new HashSet<>();
    Set<String> shapeAttrs = new HashSet<>();
    for (BomManageItem item : items) {
      if (StringUtils.hasText(item.getItemCode())) {
        itemCodes.add(item.getItemCode().trim());
      }
      if (StringUtils.hasText(item.getShapeAttr())) {
        shapeAttrs.add(item.getShapeAttr().trim());
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
    if (!shapeAttrs.isEmpty()) {
      query.in(PriceLinkedCalcItem::getShapeAttr, shapeAttrs);
    }
    List<PriceLinkedCalcItem> calcItems = priceLinkedCalcItemMapper.selectList(query);
    Map<String, PriceLinkedCalcItem> calcMap = new HashMap<>();

    for (PriceLinkedCalcItem calc : calcItems) {
      String key = buildKey(calc.getOaNo(), calc.getItemCode(), calc.getShapeAttr());
      calcMap.put(key, calc);
    }
    return calcMap;
  }

  private String buildKey(String oaNo, String itemCode, String shapeAttr) {
    return String.format("%s|%s|%s",
        oaNo == null ? "" : oaNo.trim(),
        itemCode == null ? "" : itemCode.trim(),
        shapeAttr == null ? "" : shapeAttr.trim());
  }

  private Map<String, PriceLinkedItem> fetchLinkedItems(List<BomManageItem> items) {
    if (items == null || items.isEmpty()) {
      return Map.of();
    }
    Set<String> itemCodes = new HashSet<>();
    for (BomManageItem item : items) {
      if (StringUtils.hasText(item.getItemCode())) {
        itemCodes.add(item.getItemCode().trim());
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

  private BigDecimal calculatePartUnitPrice(
      PriceLinkedItem linkedItem,
      PriceLinkedCalcItem calcItem,
      OaForm oaForm,
      Map<String, PriceVariable> variableMap,
      Map<String, Map<String, BigDecimal>> financePriceMap) {
    if (linkedItem == null || !StringUtils.hasText(linkedItem.getFormulaExpr())) {
      return null;
    }
    String expr = linkedItem.getFormulaExpr().trim();
    Map<String, BigDecimal> values = resolveVariables(
        expr, linkedItem, calcItem, oaForm, variableMap, financePriceMap);
    BigDecimal result = evaluateExpression(expr, values);
    return result == null ? null : result.setScale(6, RoundingMode.HALF_UP);
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
    Matcher matcher = VARIABLE_PATTERN.matcher(expr);
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
      BigDecimal value = readDecimal(oaForm, variable.getSourceField());
      if (value == null) {
        return null;
      }
      return value.divide(new BigDecimal("1000"), 6, RoundingMode.HALF_UP);
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
