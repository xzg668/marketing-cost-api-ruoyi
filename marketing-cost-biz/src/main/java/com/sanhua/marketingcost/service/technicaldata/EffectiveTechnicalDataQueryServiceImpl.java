package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.EffectiveTechnicalDataInput;
import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import com.sanhua.marketingcost.entity.QuoteTechTask;
import com.sanhua.marketingcost.mapper.QuoteTechAuxItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechDataVersionMapper;
import com.sanhua.marketingcost.mapper.QuoteTechModuleMapper;
import com.sanhua.marketingcost.mapper.QuoteTechPackageItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechProductMapper;
import com.sanhua.marketingcost.mapper.QuoteTechSalaryItemMapper;
import com.sanhua.marketingcost.mapper.QuoteTechTaskMapper;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataException;
import com.sanhua.marketingcost.service.EffectiveTechnicalDataQueryService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Strict read boundary between reviewed technical data and costing. */
@Service
public class EffectiveTechnicalDataQueryServiceImpl
    implements EffectiveTechnicalDataQueryService {

  private static final List<String> LEGACY_MODULE_ORDER =
      List.of("PROFILE", "PACKAGE", "AUXILIARY", "SALARY");

  private final QuoteTechProductMapper productMapper;
  private final QuoteTechTaskMapper taskMapper;
  private final QuoteTechModuleMapper moduleMapper;
  private final QuoteTechDataVersionMapper versionMapper;
  private final QuoteTechPackageItemMapper packageItemMapper;
  private final QuoteTechAuxItemMapper auxItemMapper;
  private final QuoteTechSalaryItemMapper salaryItemMapper;
  private final TechnicalDataVersionContentCodec contentCodec;
  private final TechnicalDataAuxiliaryClassificationService auxiliaryClassification;
  private final TechnicalDataCostingSources costingSources;
  private final TechnicalPriceCostingSources priceSources;
  private final com.sanhua.marketingcost.integration.oa.OaMessageCodec oaCodec;

  public EffectiveTechnicalDataQueryServiceImpl(
      QuoteTechProductMapper productMapper,
      QuoteTechTaskMapper taskMapper,
      QuoteTechModuleMapper moduleMapper,
      QuoteTechDataVersionMapper versionMapper,
      QuoteTechPackageItemMapper packageItemMapper,
      QuoteTechAuxItemMapper auxItemMapper,
      QuoteTechSalaryItemMapper salaryItemMapper,
      TechnicalDataVersionContentCodec contentCodec,
      com.sanhua.marketingcost.integration.oa.OaMessageCodec oaCodec,
      TechnicalDataAuxiliaryClassificationService auxiliaryClassification,
      TechnicalDataCostingSources costingSources, TechnicalPriceCostingSources priceSources) {
    this.productMapper = productMapper;
    this.taskMapper = taskMapper;
    this.moduleMapper = moduleMapper;
    this.versionMapper = versionMapper;
    this.packageItemMapper = packageItemMapper;
    this.auxItemMapper = auxItemMapper;
    this.salaryItemMapper = salaryItemMapper;
    this.contentCodec = contentCodec;
    this.oaCodec = oaCodec;
    this.auxiliaryClassification = auxiliaryClassification;
    this.costingSources = costingSources;
    this.priceSources = priceSources;
  }

  @Override
  // 缺审批属于可展示的业务阻断，调用方检查后仍须保存本次 BOM/价格准备结果。
  @Transactional(noRollbackFor = EffectiveTechnicalDataException.class)
  public EffectiveTechnicalDataInput resolve(Long oaFormItemId, String accountingMonth) {
    requireScope(oaFormItemId, accountingMonth);
    List<QuoteTechProduct> candidates = productMapper.selectActiveCandidatesByItemAndMonth(
        oaFormItemId, accountingMonth);
    if (candidates == null || candidates.isEmpty()) {
      var selection = costingSources.select(oaFormItemId, accountingMonth);
      selection.requireReady();
      return compose(selection, null);
    }
    if (candidates.size() != 1) {
      throw error(
          "TECH_DATA_DUPLICATE_ACTIVE_PRODUCT",
          oaFormItemId,
          accountingMonth,
          List.of(),
          "产品行存在多条活动技术资料，无法确定成本取数版本");
    }
    QuoteTechProduct product = candidates.getFirst();
    // 九模块按本次实际来源核验。公共资料已补齐时，旧任务的草稿或退回状态不再阻断。
    if (Integer.valueOf(2).equals(product.getContentSchemaVersion())) {
      return compose(costingSources.select(oaFormItemId, accountingMonth), null);
    }
    List<QuoteTechModule> modules = requireModules(product, accountingMonth);
    List<String> requiredModules = modules.stream()
        .filter(module -> Integer.valueOf(1).equals(module.getRequiredFlag()))
        .map(QuoteTechModule::getModuleType)
        .toList();
    if (product.getEffectiveVersionId() == null) {
      if (requiredModules.isEmpty()) {
        var selection = costingSources.select(oaFormItemId, accountingMonth);
        return compose(selection, null);
      }
      throw error(
          "TECH_DATA_EFFECTIVE_VERSION_MISSING",
          oaFormItemId,
          accountingMonth,
          requiredModules,
          "产品行需要技术补录，但尚无审核生效版本；缺少模块：" + requiredModules);
    }
    requireApprovedProduct(product, accountingMonth, requiredModules);
    QuoteTechTask task = taskMapper.selectById(product.getTaskId());
    if (task == null
        || !"APPROVED".equals(task.getTaskStatus())
        || !"PASSED".equals(task.getReviewStatus())) {
      throw error(
          "TECH_DATA_TASK_NOT_APPROVED",
          oaFormItemId,
          accountingMonth,
          requiredModules,
          "技术资料任务尚未全部审核通过，不能用于成本核算");
    }
    // 本产品已批准资料用于 I06 前的整单检查；成本发布由统一流水线校验 I06 成功状态。
    QuoteTechDataVersion version = versionMapper.selectById(product.getEffectiveVersionId());
    if (version == null) {
      throw error(
          "TECH_DATA_EFFECTIVE_POINTER_DANGLING",
          oaFormItemId,
          accountingMonth,
          requiredModules,
          "技术资料生效版本指针悬空：" + product.getEffectiveVersionId());
    }
    if (!Objects.equals(version.getProductId(), product.getId())
        || !QuoteTechDataVersion.STATUS_APPROVED.equals(version.getVersionStatus())) {
      throw error(
          "TECH_DATA_EFFECTIVE_VERSION_INVALID",
          oaFormItemId,
          accountingMonth,
          requiredModules,
          "技术资料生效指针未指向当前产品的 APPROVED 版本");
    }

    List<QuoteTechPackageItem> packageItems = packageItemMapper.selectByVersionId(version.getId());
    List<QuoteTechAuxItem> auxiliaryItems = auxItemMapper.selectByVersionId(version.getId());
    List<QuoteTechSalaryItem> salaryItems = salaryItemMapper.selectByVersionId(version.getId());
    validateContent(product, version, modules, packageItems, auxiliaryItems, salaryItems);

    if (contentCodec.schemaVersion(version) == 2) {
      return compose(costingSources.select(oaFormItemId, accountingMonth), version);
    }

    List<EffectiveTechnicalDataInput.AuxiliaryLine> classifiedAuxiliary =
        auxiliaryItems.stream().map(this::auxiliaryLine).toList();
    String effectiveFingerprint = version.getContentFingerprint();

    return new EffectiveTechnicalDataInput(
        product.getId(),
        version.getId(),
        version.getVersionNo(),
        product.getAccountingMonth(),
        EffectiveTechnicalDataInput.SOURCE_EFFECTIVE_VERSION,
        effectiveFingerprint,
        LocalDateTime.now(),
        required(modules, "PACKAGE"),
        required(modules, "AUXILIARY"),
        required(modules, "SALARY"),
        amount(version.getPackageTotalAmount()),
        amount(version.getAuxiliaryTotalAmount()),
        amount(version.getSalaryTotalAmount()),
        packageItems.stream().map(this::packageLine).toList(),
        classifiedAuxiliary,
        salaryItems.stream().map(this::salaryLine).toList(),
        TechnicalDataProductFeeRules.costingInput(contentCodec.productFees(version)),
        required(modules, "NET_LOSS") ? TechnicalDataNetLossRules.costingInput(contentCodec.netLoss(version)) : null);
  }

  private EffectiveTechnicalDataInput compose(TechnicalDataCostingSources.Selection selection,
      QuoteTechDataVersion currentVersion) {
    selection.requireReady();
    var sources = selection.sources();
    var selectedPrices = priceSources.select(selection.context());
    var priceInputs = selectedPrices.stream().map(TechnicalPriceCostingSources.Selected::price).toList();
    var allSources = java.util.stream.Stream.concat(sources.values().stream(), selectedPrices.stream()
        .map(TechnicalPriceCostingSources.Selected::approval)).distinct().toList();
    if (allSources.isEmpty() && currentVersion == null) return null;
    var anchor = currentVersion == null
        ? allSources.stream().sorted(java.util.Comparator.comparing(TechnicalDataCostingSources.Source::moduleType))
            .findFirst().orElseThrow().version() : currentVersion;
    List<EffectiveTechnicalDataInput.ModuleSource> moduleSources = allSources.stream()
        .map(source -> new EffectiveTechnicalDataInput.ModuleSource(source.moduleType(), source.product().getId(),
            source.version().getId(), source.moduleVersionId(), source.version().getContentFingerprint()))
        .distinct().sorted(java.util.Comparator.comparing(EffectiveTechnicalDataInput.ModuleSource::moduleType)
            .thenComparing(EffectiveTechnicalDataInput.ModuleSource::productId)).toList();
    var materials = materialItems(sources);
    var auxiliary = sources.get("AUXILIARY");
    List<EffectiveTechnicalDataInput.AuxiliaryLine> auxiliaryLines = auxiliary == null ? List.of()
        : auxiliaryClassification.costingLines(auxiliary.version(), auxItemMapper.selectByVersionId(auxiliary.version().getId()),
            selection.context().businessUnitType());
    var salary = sources.get("SALARY");
    List<EffectiveTechnicalDataInput.SalaryLine> salaryLines = salary == null ? List.of()
        : salaryItemMapper.selectByVersionId(salary.version().getId()).stream().map(this::salaryLine).toList();
    var profile = sources.get("PROFILE");
    var fees = profile == null ? null : TechnicalDataProductFeeRules.costingInput(contentCodec.productFees(profile.version()));
    var loss = sources.get("NET_LOSS");
    var lossRate = loss == null ? null : TechnicalDataNetLossRules.costingInput(contentCodec.netLoss(loss.version()));
    // 各模块的批准版本与后处理映射进入输入指纹；取数时间不影响同输入复用。
    String fingerprint = oaCodec.canonicalHash(java.util.Arrays.asList(anchor.getContentFingerprint(), moduleSources,
        materials, auxiliaryLines, salaryLines, fees, lossRate, priceInputs));
    return new EffectiveTechnicalDataInput(anchor.getProductId(), anchor.getId(), anchor.getVersionNo(),
        selection.context().accountingMonth(), EffectiveTechnicalDataInput.SOURCE_EFFECTIVE_VERSION,
        fingerprint, LocalDateTime.now(), false, auxiliary != null, salary != null,
        null, auxiliaryLines.stream().map(EffectiveTechnicalDataInput.AuxiliaryLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add),
        salaryLines.stream().map(EffectiveTechnicalDataInput.SalaryLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add),
        List.of(), auxiliaryLines, salaryLines, fees, lossRate,
        profile == null ? null : profile.version().getProductProperty(), moduleSources, materials, priceInputs);
  }

  @Override
  @Transactional
  public List<EffectiveTechnicalDataInput.MaterialLine> preparationMaterials(Long itemId, String month) {
    requireScope(itemId, month);
    return materialItems(costingSources.preparationSources(itemId, month));
  }

  private List<EffectiveTechnicalDataInput.MaterialLine> materialItems(Map<String, TechnicalDataCostingSources.Source> sources) {
    List<EffectiveTechnicalDataInput.MaterialLine> materials = new ArrayList<>();
    var packaging = sources.get("PACKAGE");
    if (packaging != null) {
      var value = contentCodec.packaging(packaging.version());
      var lines = packageItemMapper.selectByVersionId(packaging.version().getId());
      var issues = TechnicalDataPackageRules.validate(value, lines);
      if (!issues.isEmpty()) throw new IllegalArgumentException(String.join("；", issues));
      for (var line : lines) materials.add(new EffectiveTechnicalDataInput.MaterialLine("PACKAGE",
          "PACKAGE:" + packaging.version().getId() + ":" + line.getId(), line.getComponentMaterialNo(), line.getComponentName(),
          TechnicalDataPackageRules.perProduct(value, line), line.getOriginalUnit(), packaging.version().getId()));
    }
    var solder = sources.get("SOLDER");
    if (solder != null) {
      var value = contentCodec.solder(solder.version());
      var issues = TechnicalDataSolderRules.validate(value);
      if (!issues.isEmpty()) throw new IllegalArgumentException(String.join("；", issues));
      for (var line : value.items()) materials.add(new EffectiveTechnicalDataInput.MaterialLine("SOLDER",
          "SOLDER:" + solder.version().getId() + ":" + line.itemKey(), line.materialNo(), line.name(),
          line.quantityPerProduct(), line.unit(), solder.version().getId()));
    }
    return List.copyOf(materials);
  }

  private List<QuoteTechModule> requireModules(
      QuoteTechProduct product, String accountingMonth) {
    List<String> moduleOrder = Integer.valueOf(2).equals(product.getContentSchemaVersion())
        ? TechnicalDataModuleType.orderedCodes() : LEGACY_MODULE_ORDER;
    List<QuoteTechModule> modules = moduleMapper.selectByProductId(product.getId());
    Map<String, QuoteTechModule> byType = new LinkedHashMap<>();
    for (QuoteTechModule module : modules == null ? List.<QuoteTechModule>of() : modules) {
      if (module == null || !moduleOrder.contains(module.getModuleType())
          || byType.put(module.getModuleType(), module) != null) {
        throw error(
            "TECH_DATA_MODULE_SET_INVALID",
            product.getOaFormItemId(),
            accountingMonth,
            List.of(),
            "技术资料模块集合损坏或存在重复模块");
      }
    }
    List<String> missing = moduleOrder.stream().filter(type -> !byType.containsKey(type)).toList();
    if (!missing.isEmpty()) {
      throw error(
          "TECH_DATA_MODULE_MISSING",
          product.getOaFormItemId(),
          accountingMonth,
          missing,
          "技术资料缺少模块：" + missing);
    }
    return moduleOrder.stream().map(byType::get).toList();
  }

  private void requireApprovedProduct(
      QuoteTechProduct product, String accountingMonth, List<String> requiredModules) {
    if (!"APPROVED".equals(product.getProductStatus())) {
      throw error(
          "TECH_DATA_PRODUCT_NOT_APPROVED",
          product.getOaFormItemId(),
          accountingMonth,
          requiredModules,
          "技术资料产品状态不是 APPROVED，不能用于成本核算");
    }
  }

  private void validateContent(
      QuoteTechProduct product,
      QuoteTechDataVersion version,
      List<QuoteTechModule> modules,
      List<QuoteTechPackageItem> packageItems,
      List<QuoteTechAuxItem> auxiliaryItems,
      List<QuoteTechSalaryItem> salaryItems) {
    List<String> invalidModules = new ArrayList<>();
    for (QuoteTechModule module : modules) {
      if (Integer.valueOf(1).equals(module.getRequiredFlag())
          && !"APPROVED".equals(module.getModuleStatus())) {
        invalidModules.add(module.getModuleType());
      }
    }
    if (!invalidModules.isEmpty()) {
      throw error(
          "TECH_DATA_MODULE_NOT_APPROVED",
          product.getOaFormItemId(),
          product.getAccountingMonth(),
          invalidModules,
          "技术资料模块未审核通过：" + invalidModules);
    }
    requireLines(product, modules, packageItems, auxiliaryItems, salaryItems);
    requireTotal(product, "PACKAGE", version.getPackageTotalAmount(),
        packageItems.stream().map(QuoteTechPackageItem::getAmount).toList());
    requireTotal(product, "AUXILIARY", version.getAuxiliaryTotalAmount(),
        auxiliaryItems.stream().map(QuoteTechAuxItem::getAmount).toList());
    requireTotal(product, "SALARY", version.getSalaryTotalAmount(),
        salaryItems.stream().map(QuoteTechSalaryItem::getAmount).toList());
    String actual;
    try {
      actual = contentCodec.fingerprint(
          version,
          contentCodec.readReferenceSnapshot(version.getReferenceSnapshotJson()),
          packageItems,
          auxiliaryItems,
          salaryItems);
    } catch (RuntimeException exception) {
      throw error(
          "TECH_DATA_CONTENT_SNAPSHOT_INVALID",
          product.getOaFormItemId(),
          product.getAccountingMonth(),
          List.of(),
          "技术资料生效版本快照无法校验：" + exception.getMessage());
    }
    if (version.getContentFingerprint() == null
        || !version.getContentFingerprint().equals(actual)) {
      throw error(
          "TECH_DATA_CONTENT_FINGERPRINT_MISMATCH",
          product.getOaFormItemId(),
          product.getAccountingMonth(),
          List.of(),
          "技术资料生效版本内容与提交指纹不一致");
    }
  }

  private void requireLines(
      QuoteTechProduct product,
      List<QuoteTechModule> modules,
      List<QuoteTechPackageItem> packageItems,
      List<QuoteTechAuxItem> auxiliaryItems,
      List<QuoteTechSalaryItem> salaryItems) {
    List<String> missing = new ArrayList<>();
    if (required(modules, "PACKAGE") && packageItems.isEmpty()) missing.add("PACKAGE");
    if (required(modules, "AUXILIARY") && auxiliaryItems.isEmpty()) missing.add("AUXILIARY");
    if (required(modules, "SALARY") && salaryItems.isEmpty()) missing.add("SALARY");
    if (!missing.isEmpty()) {
      throw error(
          "TECH_DATA_DETAIL_MISSING",
          product.getOaFormItemId(),
          product.getAccountingMonth(),
          missing,
          "技术资料生效版本缺少必填明细：" + missing);
    }
  }

  private void requireTotal(
      QuoteTechProduct product,
      String module,
      BigDecimal stored,
      List<BigDecimal> values) {
    BigDecimal sum = values.stream().filter(Objects::nonNull)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
    if (amount(stored).compareTo(sum) != 0) {
      throw error(
          "TECH_DATA_TOTAL_MISMATCH",
          product.getOaFormItemId(),
          product.getAccountingMonth(),
          List.of(module),
          "技术资料" + module + "合计与生效明细不一致");
    }
  }

  private boolean required(List<QuoteTechModule> modules, String type) {
    return modules.stream().anyMatch(module -> type.equals(module.getModuleType())
        && Integer.valueOf(1).equals(module.getRequiredFlag()));
  }

  private EffectiveTechnicalDataInput.PackageLine packageLine(QuoteTechPackageItem item) {
    return new EffectiveTechnicalDataInput.PackageLine(
        item.getId(), item.getLineNo(), item.getComponentMaterialNo(), item.getComponentName(),
        item.getStandardQuantity(), item.getStandardUnit(), item.getReferenceUnitPrice(),
        item.getAmount());
  }

  private EffectiveTechnicalDataInput.AuxiliaryLine auxiliaryLine(QuoteTechAuxItem item) {
    return new EffectiveTechnicalDataInput.AuxiliaryLine(
        item.getId(), item.getLineNo(), item.getSubjectCode(), item.getSubjectName(),
        item.getAuxiliaryName(), item.getStandardQuantity(), item.getStandardUnit(),
        item.getReferenceUnitPrice(), item.getLossRate(), item.getAmount());
  }

  private EffectiveTechnicalDataInput.SalaryLine salaryLine(QuoteTechSalaryItem item) {
    return new EffectiveTechnicalDataInput.SalaryLine(
        item.getId(), item.getLineNo(), item.getLaborType(), item.getAmount());
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private void requireScope(Long oaFormItemId, String accountingMonth) {
    if (oaFormItemId == null || oaFormItemId <= 0) {
      throw new IllegalArgumentException("报价产品行ID必须大于0");
    }
    try {
      YearMonth.parse(accountingMonth);
    } catch (DateTimeParseException | NullPointerException exception) {
      throw new IllegalArgumentException("核算月份必须为yyyy-MM");
    }
  }

  private EffectiveTechnicalDataException error(
      String code,
      Long oaFormItemId,
      String accountingMonth,
      List<String> modules,
      String message) {
    return new EffectiveTechnicalDataException(
        code,
        oaFormItemId,
        accountingMonth,
        modules,
        "产品" + oaFormItemId + " / " + accountingMonth + "：" + message);
  }
}
