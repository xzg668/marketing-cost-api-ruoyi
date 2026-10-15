package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.enums.MaterialPriceSourceType;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 按月份、组织和料号维护完整顺序。默认生成与财务调整分别写入，互不覆盖。 */
@Service
public class MaterialPricePriorityService {
  private final MaterialPriceTypeMapper mapper;
  private final org.springframework.transaction.support.TransactionTemplate initialization;

  public MaterialPricePriorityService(MaterialPriceTypeMapper mapper,
      org.springframework.transaction.PlatformTransactionManager transactions) {
    this.mapper = mapper;
    initialization = new org.springframework.transaction.support.TransactionTemplate(transactions);
    initialization.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    initialization.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public record Scope(String businessUnitType, String period, String priceOrgCode, String materialCode) {
    public Scope {
      businessUnitType = required(businessUnitType, "业务单元");
      period = FactorPriceScope.month(period);
      priceOrgCode = required(priceOrgCode, "取价组织");
      materialCode = required(materialCode, "料号");
    }

    private static String required(String value, String label) {
      if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
      return value.trim();
    }
  }

  public record Entry(MaterialPriceSourceType type, int priority) {}
  public record Configuration(Scope scope, List<Entry> order, boolean manual, String revision) {}

  public Configuration read(Scope scope) {
    return configuration(scope, mapper.selectScope(scope, false));
  }

  /** 调用方按组织和月份分批；已有顺序一次读取，缺失项仍由原短事务安全初始化。 */
  public java.util.Map<String, Configuration> readBatch(Scope scope,
      java.util.Map<String, String> names, boolean initializeMissing) {
    var rows = mapper.selectScopes(scope, List.copyOf(names.keySet()));
    var grouped = rows.stream().collect(java.util.stream.Collectors.groupingBy(MaterialPriceType::getMaterialCode));
    var result = new java.util.LinkedHashMap<String, Configuration>();
    for (var entry : names.entrySet()) {
      var itemScope = new Scope(scope.businessUnitType(), scope.period(), scope.priceOrgCode(), entry.getKey());
      var existing = grouped.getOrDefault(entry.getKey(), List.of());
      result.put(entry.getKey(), initializeMissing && existing.isEmpty()
          ? initialize(itemScope, entry.getValue()) : configuration(itemScope, existing));
    }
    return result;
  }

  /** 只在实际准备价格时调用。唯一键解决并发首次生成，不更新已有财务顺序。 */
  public Configuration initialize(Scope scope, String materialName) {
    // 默认对照属于可独立复用的主数据。短事务提交，避免报价长事务互相持有首次插入锁。
    for (int attempt = 0; ; attempt++) {
      try {
        return initialization.execute(status -> {
          var existing = mapper.selectScope(scope, false);
          if (existing.isEmpty()) {
            mapper.insertDefaults(scope, materialName, MaterialPriceSourceType.DEFAULT_ORDER);
            // 并发初始化可能先由另一请求提交；返回本短事务内实际已提交的完整配置。
            return read(scope);
          }
          return configuration(scope, existing);
        });
      } catch (org.springframework.dao.ConcurrencyFailureException contention) {
        if (attempt >= 2) throw contention;
      }
    }
  }

  @Transactional(rollbackFor = Exception.class)
  public Configuration change(Scope scope, List<MaterialPriceSourceType> order,
      String expectedRevision, Long userId) {
    if (userId == null) throw new IllegalArgumentException("缺少操作人");
    if (order != null && (order.size() != MaterialPriceSourceType.DEFAULT_ORDER.size()
        || !new HashSet<>(order).equals(new HashSet<>(MaterialPriceSourceType.DEFAULT_ORDER)))) {
      throw new IllegalArgumentException("请完整排列五种取价类型，不能重复或遗漏");
    }
    var rows = mapper.selectScope(scope, true);
    if (rows.isEmpty()) throw new IllegalArgumentException("该月份、组织、料号尚未生成取价对照");
    var current = configuration(scope, rows);
    if (!current.revision().equals(expectedRevision)) {
      throw new OptimisticLockingFailureException("取价顺序已被修改，请刷新后重试");
    }
    for (var row : rows) {
      var type = MaterialPriceSourceType.fromStored(row.getPriceType());
      mapper.updateManual(row.getId(), order == null ? null : order.indexOf(type) + 1, userId);
    }
    return read(scope);
  }

  public Configuration configuration(Scope scope, List<MaterialPriceType> rows) {
    if (rows.isEmpty()) {
      var order = MaterialPriceSourceType.DEFAULT_ORDER.stream()
          .map(type -> new Entry(type, type.getDefaultPriority())).toList();
      return new Configuration(scope, order, false, revision(scope + ":DEFAULT"));
    }
    if (rows.size() != 5) throw new IllegalStateException("取价顺序不完整：" + scope.materialCode());
    boolean manual = rows.getFirst().getManualPriority() != null;
    var types = new HashSet<MaterialPriceSourceType>();
    var ranks = new HashSet<Integer>();
    for (var row : rows) {
      var type = MaterialPriceSourceType.fromStored(row.getPriceType());
      Integer rank = manual ? row.getManualPriority() : row.getPriority();
      if ((row.getManualPriority() != null) != manual || rank == null || rank < 1 || rank > 5
          || !types.add(type) || !ranks.add(rank) || row.getPriority() == null
          || row.getPriority() != type.getDefaultPriority()) {
        throw new IllegalStateException("取价顺序存在重复或无效设置：" + scope.materialCode());
      }
    }
    var order = rows.stream().map(row -> new Entry(MaterialPriceSourceType.fromStored(row.getPriceType()),
        manual ? row.getManualPriority() : row.getPriority())).sorted(Comparator.comparingInt(Entry::priority)).toList();
    // 首次自动生成不能使正在准备的报价失效；只有财务调整的内容和操作版本参与配置修订。
    String audit = rows.stream().sorted(Comparator.comparing(MaterialPriceType::getPriceType))
        .map(row -> row.getPriceType() + ":" + row.getManualPriority() + ":"
            + row.getPriorityUpdatedBy() + ":" + row.getPriorityUpdatedAt()).collect(java.util.stream.Collectors.joining(";"));
    boolean edited = rows.stream().anyMatch(row -> row.getPriorityUpdatedAt() != null);
    return new Configuration(scope, order, manual, revision(scope + ":" + (edited ? audit : "DEFAULT")));
  }

  private static String revision(String value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
