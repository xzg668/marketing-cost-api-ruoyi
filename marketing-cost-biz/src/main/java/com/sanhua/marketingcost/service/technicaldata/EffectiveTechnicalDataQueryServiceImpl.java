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

  private static final List<String> MODULE_ORDER =
      List.of("PROFILE", "PACKAGE", "AUXILIARY", "SALARY");

  private final QuoteTechProductMapper productMapper;
  private final QuoteTechTaskMapper taskMapper;
  private final QuoteTechModuleMapper moduleMapper;
  private final QuoteTechDataVersionMapper versionMapper;
  private final QuoteTechPackageItemMapper packageItemMapper;
  private final QuoteTechAuxItemMapper auxItemMapper;
  private final QuoteTechSalaryItemMapper salaryItemMapper;
  private final TechnicalDataVersionContentCodec contentCodec;

  public EffectiveTechnicalDataQueryServiceImpl(
      QuoteTechProductMapper productMapper,
      QuoteTechTaskMapper taskMapper,
      QuoteTechModuleMapper moduleMapper,
      QuoteTechDataVersionMapper versionMapper,
      QuoteTechPackageItemMapper packageItemMapper,
      QuoteTechAuxItemMapper auxItemMapper,
      QuoteTechSalaryItemMapper salaryItemMapper,
      TechnicalDataVersionContentCodec contentCodec) {
    this.productMapper = productMapper;
    this.taskMapper = taskMapper;
    this.moduleMapper = moduleMapper;
    this.versionMapper = versionMapper;
    this.packageItemMapper = packageItemMapper;
    this.auxItemMapper = auxItemMapper;
    this.salaryItemMapper = salaryItemMapper;
    this.contentCodec = contentCodec;
  }

  @Override
  @Transactional(readOnly = true)
  public EffectiveTechnicalDataInput resolve(Long oaFormItemId, String accountingMonth) {
    requireScope(oaFormItemId, accountingMonth);
    List<QuoteTechProduct> candidates = productMapper.selectActiveCandidatesByItemAndMonth(
        oaFormItemId, accountingMonth);
    if (candidates == null || candidates.isEmpty()) {
      return null;
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
    List<QuoteTechModule> modules = requireModules(product, accountingMonth);
    List<String> requiredModules = modules.stream()
        .filter(module -> Integer.valueOf(1).equals(module.getRequiredFlag()))
        .map(QuoteTechModule::getModuleType)
        .toList();
    if (product.getEffectiveVersionId() == null) {
      if (requiredModules.isEmpty()) return null;
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

    return new EffectiveTechnicalDataInput(
        product.getId(),
        version.getId(),
        version.getVersionNo(),
        product.getAccountingMonth(),
        EffectiveTechnicalDataInput.SOURCE_EFFECTIVE_VERSION,
        version.getContentFingerprint(),
        LocalDateTime.now(),
        required(modules, "PACKAGE"),
        required(modules, "AUXILIARY"),
        required(modules, "SALARY"),
        amount(version.getPackageTotalAmount()),
        amount(version.getAuxiliaryTotalAmount()),
        amount(version.getSalaryTotalAmount()),
        packageItems.stream().map(this::packageLine).toList(),
        auxiliaryItems.stream().map(this::auxiliaryLine).toList(),
        salaryItems.stream().map(this::salaryLine).toList());
  }

  private List<QuoteTechModule> requireModules(
      QuoteTechProduct product, String accountingMonth) {
    List<QuoteTechModule> modules = moduleMapper.selectByProductId(product.getId());
    Map<String, QuoteTechModule> byType = new LinkedHashMap<>();
    for (QuoteTechModule module : modules == null ? List.<QuoteTechModule>of() : modules) {
      if (module == null || !MODULE_ORDER.contains(module.getModuleType())
          || byType.put(module.getModuleType(), module) != null) {
        throw error(
            "TECH_DATA_MODULE_SET_INVALID",
            product.getOaFormItemId(),
            accountingMonth,
            List.of(),
            "技术资料模块集合损坏或存在重复模块");
      }
    }
    List<String> missing = MODULE_ORDER.stream().filter(type -> !byType.containsKey(type)).toList();
    if (!missing.isEmpty()) {
      throw error(
          "TECH_DATA_MODULE_MISSING",
          product.getOaFormItemId(),
          accountingMonth,
          missing,
          "技术资料缺少模块：" + missing);
    }
    return MODULE_ORDER.stream().map(byType::get).toList();
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
        item.getId(), item.getLineNo(), item.getProcessCode(), item.getProcessName(),
        item.getLaborType(), item.getStandardHours(), item.getHourlyRate(),
        item.getPersonCoefficient(), item.getAmount());
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
