package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.SourceFactSnapshot;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.SourceSnapshot;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.SupplementSnapshot;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

/** Builds the immutable reference snapshot and the canonical content fingerprint. */
@Component
public class TechnicalDataVersionContentCodec {
  public com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryContent.ItemEvidence salaryEvidence(QuoteTechSalaryItem item) {
    if (item.getSourceReferenceId() == null || !(item.getSourceReferenceId().startsWith("CMS_SALARY:") || item.getSourceReferenceId().startsWith("SALARY_UPLOAD:"))) return null;
    return read(item.getSourceSnapshotJson(), com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryContent.ItemEvidence.class);
  }
  public String salaryEvidenceJson(com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSalaryContent.ItemEvidence value) { return write(value); }

  public TechnicalDataSupplementContent.Solder solder(QuoteTechDataVersion version) {
    return version == null ? null : read(version.getSolderItemsJson(), TechnicalDataSupplementContent.Solder.class);
  }
  public String solderJson(TechnicalDataSupplementContent.Solder value) { return write(value); }

  public TechnicalDataSupplementContent.Manufacturing manufacturing(QuoteTechDataVersion version) {
    return version == null ? null : read(version.getManufacturingJson(), TechnicalDataSupplementContent.Manufacturing.class);
  }

  public String manufacturingJson(TechnicalDataSupplementContent.Manufacturing manufacturing) { return write(manufacturing); }

  public TechnicalDataSupplementContent.Packaging packaging(QuoteTechDataVersion version) {
    return version == null ? null : read(version.getPackagingJson(), TechnicalDataSupplementContent.Packaging.class);
  }
  public String packagingJson(TechnicalDataSupplementContent.Packaging value) { return write(value); }
  public TechnicalDataSupplementContent.PackageItemEvidence packageEvidence(QuoteTechPackageItem item) {
    return read(item.getSourceSnapshotJson(), TechnicalDataSupplementContent.PackageItemEvidence.class);
  }
  public String packageEvidenceJson(TechnicalDataSupplementContent.PackageItemEvidence value) { return write(value); }

  private static final int SNAPSHOT_SCHEMA_VERSION = 1;
  public com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryContent.ItemEvidence auxiliaryEvidence(QuoteTechAuxItem item) {
    if (!"CMS_AMOUNT".equals(item.getPricingMethod()) && !"UPLOAD_AMOUNT".equals(item.getPricingMethod())) return null;
    return read(item.getSourceSnapshotJson(), com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryContent.ItemEvidence.class);
  }
  public String auxiliaryEvidenceJson(com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryContent.ItemEvidence value) { return write(value); }

  public boolean sameAuxiliaryEvidence(com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryContent.ItemEvidence expected,
      com.sanhua.marketingcost.dto.technicaldata.TechnicalDataAuxiliaryContent.ItemEvidence actual) {
    return sameSourceEvidence(expected, actual);
  }

  public boolean sameSourceEvidence(Object expected, Object actual) {
    com.fasterxml.jackson.databind.JsonNode left = canonicalMapper.valueToTree(expected), right = canonicalMapper.valueToTree(actual);
    // MySQL JSON 会规范化数值表示；10、10.0 是同一个原金额，不按序列化文本比较。
    return left.equals((a, b) -> a.isNumber() && b.isNumber() ? a.decimalValue().compareTo(b.decimalValue()) : a.equals(b) ? 0 : 1, right);
  }

  private final ObjectMapper canonicalMapper;

  public TechnicalDataVersionContentCodec(ObjectMapper objectMapper) {
    this.canonicalMapper = objectMapper.copy()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
  }

  public List<ModuleSnapshot> moduleSnapshots(List<QuoteTechModule> modules) {
    if (modules == null) return List.of();
    return modules.stream()
        .map(module -> new ModuleSnapshot(
            module.getModuleType(),
            Integer.valueOf(1).equals(module.getRequiredFlag()),
            module.getEntryMode(),
            module.getModuleStatus(),
            module.getReferenceSourceType(),
            module.getReferenceSourceId(),
            module.getReferenceSourceVersion(),
            module.getReferenceFingerprint(),
            module.getReferenceSnapshotJson(),
            module.getLastValidationCode(),
            module.getLastValidationMessage()))
        .sorted(modules.size() == TechnicalDataModuleType.values().length
            ? Comparator.comparingInt(module -> TechnicalDataModuleType.orderOf(module.moduleType()))
            : Comparator.comparing(ModuleSnapshot::moduleType))
        .toList();
  }

  public String referenceSnapshotJson(List<ModuleSnapshot> modules) {
    int schema = modules.size() == TechnicalDataModuleType.values().length ? 2 : SNAPSHOT_SCHEMA_VERSION;
    return write(new ReferenceSnapshot(schema, List.copyOf(modules)));
  }

  public List<ModuleSnapshot> readReferenceSnapshot(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("历史提交版本缺少参照快照");
    }
    try {
      ReferenceSnapshot snapshot = canonicalMapper.readValue(json, ReferenceSnapshot.class);
      if ((snapshot.schemaVersion() != 1 && snapshot.schemaVersion() != 2) || snapshot.modules() == null) {
        throw new IllegalArgumentException("历史提交版本参照快照格式不受支持");
      }
      return List.copyOf(snapshot.modules());
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("历史提交版本参照快照无法解析", exception);
    }
  }

  public String fingerprint(
      QuoteTechDataVersion version,
      List<ModuleSnapshot> modules,
      List<QuoteTechPackageItem> packageItems,
      List<QuoteTechAuxItem> auxItems,
      List<QuoteTechSalaryItem> salaryItems) {
    return sha256(versionContentJson(version, modules, packageItems, auxItems, salaryItems));
  }

  public String versionContentJson(
      QuoteTechDataVersion version, List<ModuleSnapshot> modules,
      List<QuoteTechPackageItem> packageItems, List<QuoteTechAuxItem> auxItems,
      List<QuoteTechSalaryItem> salaryItems) {
    VersionContent content = new VersionContent(
        version.getProductModel(),
        version.getProductProperty(),
        Integer.valueOf(1).equals(version.getNewProductFlag()),
        amount(version.getPackageTotalAmount()),
        amount(version.getAuxiliaryTotalAmount()),
        amount(version.getSalaryTotalAmount()),
        List.copyOf(modules),
        packages(packageItems),
        auxiliaries(auxItems),
        salaries(salaryItems));
    int schema = schemaVersion(version);
    // 旧版本仍按原有四模块字节协议计算，不为历史补造空的新模块导致指纹变化。
    return schema == 1 ? write(content)
        : write(new VersionContentV2(2, content, supplementContent(version)));
  }

  public TechnicalDataSupplementContent.ProductFees productFees(QuoteTechDataVersion version) {
    return version == null ? null : read(version.getProductFeesJson(), TechnicalDataSupplementContent.ProductFees.class);
  }

  public String productFeesJson(TechnicalDataSupplementContent.ProductFees fees) { return write(fees); }

  public TechnicalDataSupplementContent.DrawingBom drawingBom(QuoteTechDataVersion version) {
    return version == null ? null : read(version.getDrawingBomJson(), TechnicalDataSupplementContent.DrawingBom.class);
  }

  public String drawingBomJson(TechnicalDataSupplementContent.DrawingBom drawing) { return write(drawing); }

  public TechnicalDataSupplementContent.NetLoss netLoss(QuoteTechDataVersion version) {
    return version == null ? null : read(version.getNetLossJson(), TechnicalDataSupplementContent.NetLoss.class);
  }

  public String netLossJson(TechnicalDataSupplementContent.NetLoss value) { return write(value); }

  public TechnicalDataSupplementContent.Prices prices(QuoteTechDataVersion version) {
    return version == null ? null : read(version.getPriceItemsJson(), TechnicalDataSupplementContent.Prices.class);
  }

  public String pricesJson(TechnicalDataSupplementContent.Prices value) { return write(value); }

  public SupplementSnapshot supplementContent(QuoteTechDataVersion version) {
    if (schemaVersion(version) == 1) return null;
    return new SupplementSnapshot(
        productFees(version),
        read(version.getDrawingBomJson(), TechnicalDataSupplementContent.DrawingBom.class),
        read(version.getManufacturingJson(), TechnicalDataSupplementContent.Manufacturing.class),
        read(version.getPackagingJson(), TechnicalDataSupplementContent.Packaging.class),
        read(version.getSolderItemsJson(), TechnicalDataSupplementContent.Solder.class),
        read(version.getNetLossJson(), TechnicalDataSupplementContent.NetLoss.class),
        read(version.getPriceItemsJson(), TechnicalDataSupplementContent.Prices.class),
        read(version.getSourceFactsJson(), SourceSnapshot.class));
  }

  public String sourceFactsJson(String productSourceJson, List<QuoteTechModule> modules) {
    return sourceFactsJson(productSourceJson, modules, List.of());
  }

  public String sourceFactsJson(String productSourceJson, List<QuoteTechModule> modules,
      List<TechnicalDataSupplementContent.Dependency> dependencies) {
    return write(new SourceSnapshot(productSourceJson, modules.stream()
        .sorted(Comparator.comparingInt(module -> TechnicalDataModuleType.orderOf(module.getModuleType())))
        .map(module -> new SourceFactSnapshot(module.getModuleType(), module.getSourceAvailability(),
            module.getRequirementReasonCode(), module.getRequirementReason(),
            module.getSourceReference(), module.getSourceCheckedAt())).toList(), dependencies));
  }

  public int supplementItemCount(QuoteTechDataVersion version, String moduleType) {
    SupplementSnapshot content = supplementContent(version);
    if (content == null) return 0;
    return switch (moduleType) {
      case "PROFILE" -> content.productFees() == null ? 0 : 1;
      case "DRAWING_BOM" -> content.drawingBom() == null ? 0 : size(content.drawingBom().nodes());
      case "MANUFACTURING" -> content.manufacturing() == null ? 0 : size(content.manufacturing().items());
      case "SOLDER" -> content.solder() == null ? 0 : size(content.solder().items());
      case "NET_LOSS" -> content.netLoss() == null ? 0 : 1;
      case "PRICE" -> content.prices() == null ? 0 : size(content.prices().items());
      default -> 0;
    };
  }

  private int size(List<?> items) { return items == null ? 0 : items.size(); }

  public int schemaVersion(QuoteTechDataVersion version) {
    int schema = version.getContentSchemaVersion() == null ? 1 : version.getContentSchemaVersion();
    if (schema != 1 && schema != 2) throw new IllegalArgumentException("不支持的技术版本结构：" + schema);
    if (schema == 1 && (version.getProductFeesJson() != null || version.getDrawingBomJson() != null
        || version.getManufacturingJson() != null || version.getPackagingJson() != null
        || version.getSolderItemsJson() != null || version.getNetLossJson() != null
        || version.getPriceItemsJson() != null || version.getSourceFactsJson() != null)) {
      throw new IllegalArgumentException("旧版本结构不能包含未计入指纹的新模块内容");
    }
    return schema;
  }

  private <T> T read(String json, Class<T> type) {
    if (json == null) return null;
    try {
      return canonicalMapper.readValue(json, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(type.getSimpleName() + "版本内容不符合固定结构", exception);
    }
  }

  private List<PackageSnapshot> packages(List<QuoteTechPackageItem> items) {
    return items.stream()
        .map(item -> new PackageSnapshot(
            item.getLineNo(), item.getSortSeq(), item.getComponentMaterialNo(),
            item.getComponentName(), item.getComponentSpec(), item.getQuantity(),
            item.getOriginalUnit(), item.getStandardQuantity(), item.getStandardUnit(),
            item.getConversionFactor(), item.getPriceBasisType(), item.getReferenceUnitPrice(),
            item.getAmount(), item.getSourceReferenceId(), item.getSourceReferenceVersion(),
            item.getSourceSnapshotJson(), item.getRemark()))
        .sorted(Comparator.comparing(PackageSnapshot::lineNo)
            .thenComparing(PackageSnapshot::sortSeq, Comparator.nullsFirst(Integer::compareTo)))
        .toList();
  }

  private List<AuxSnapshot> auxiliaries(List<QuoteTechAuxItem> items) {
    return items.stream()
        .map(item -> new AuxSnapshot(
            item.getLineNo(), item.getSortSeq(), item.getSubjectCode(), item.getSubjectName(),
            item.getAuxiliaryMaterialNo(), item.getAuxiliaryName(), item.getAuxiliarySpec(),
            item.getPricingMethod(), item.getQuantity(),
            item.getOriginalUnit(), item.getStandardQuantity(), item.getStandardUnit(),
            item.getConversionFactor(), item.getReferenceUnitPrice(), item.getPriceUnit(),
            item.getLossRate(), item.getAmount(),
            item.getSourceReferenceId(), item.getSourceReferenceVersion(),
            item.getSourceSnapshotJson(), item.getRemark()))
        .sorted(Comparator.comparing(AuxSnapshot::lineNo)
            .thenComparing(AuxSnapshot::sortSeq, Comparator.nullsFirst(Integer::compareTo)))
        .toList();
  }

  private List<SalarySnapshot> salaries(List<QuoteTechSalaryItem> items) {
    return items.stream()
        .map(item -> new SalarySnapshot(
            item.getLineNo(), item.getSortSeq(), item.getProcessCode(), item.getProcessName(),
            item.getLaborType(), item.getWorkingHours(), item.getOriginalTimeUnit(),
            item.getStandardHours(), item.getStandardTimeUnit(), item.getConversionFactor(),
            item.getWageRate(), item.getRateUnit(), item.getHourlyRate(),
            item.getPersonCoefficient(), item.getAmount(), item.getSourceReferenceId(),
            item.getSourceReferenceVersion(), item.getSourceSnapshotJson(), item.getRemark()))
        .sorted(Comparator.comparing(SalarySnapshot::lineNo)
            .thenComparing(SalarySnapshot::sortSeq, Comparator.nullsFirst(Integer::compareTo)))
        .toList();
  }

  private BigDecimal amount(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private String write(Object value) {
    try {
      return canonicalMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("技术资料版本内容序列化失败", exception);
    }
  }

  private String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("运行环境缺少SHA-256", exception);
    }
  }

  public record ModuleSnapshot(
      String moduleType,
      boolean required,
      String entryMode,
      String moduleStatus,
      String referenceSourceType,
      String referenceSourceId,
      String referenceSourceVersion,
      String referenceFingerprint,
      String referenceSnapshotJson,
      String validationCode,
      String validationMessage) {}

  private record ReferenceSnapshot(int schemaVersion, List<ModuleSnapshot> modules) {}

  private record VersionContentV2(int schemaVersion, VersionContent details, SupplementSnapshot supplements) {}

  private record VersionContent(
      String productModel,
      String productProperty,
      boolean newProduct,
      BigDecimal packageTotalAmount,
      BigDecimal auxiliaryTotalAmount,
      BigDecimal salaryTotalAmount,
      List<ModuleSnapshot> modules,
      List<PackageSnapshot> packageItems,
      List<AuxSnapshot> auxiliaryItems,
      List<SalarySnapshot> salaryItems) {}

  private record PackageSnapshot(
      Integer lineNo,
      Integer sortSeq,
      String componentMaterialNo,
      String componentName,
      String componentSpec,
      BigDecimal quantity,
      String originalUnit,
      BigDecimal standardQuantity,
      String standardUnit,
      BigDecimal conversionFactor,
      String priceBasisType,
      BigDecimal referenceUnitPrice,
      BigDecimal amount,
      String sourceReferenceId,
      String sourceReferenceVersion,
      String sourceSnapshotJson,
      String remark) {}

  private record AuxSnapshot(
      Integer lineNo,
      Integer sortSeq,
      String subjectCode,
      String subjectName,
      String auxiliaryMaterialNo,
      String auxiliaryName,
      String auxiliarySpec,
      String pricingMethod,
      BigDecimal quantity,
      String originalUnit,
      BigDecimal standardQuantity,
      String standardUnit,
      BigDecimal conversionFactor,
      BigDecimal referenceUnitPrice,
      String priceUnit,
      BigDecimal lossRate,
      BigDecimal amount,
      String sourceReferenceId,
      String sourceReferenceVersion,
      String sourceSnapshotJson,
      String remark) {}

  private record SalarySnapshot(
      Integer lineNo,
      Integer sortSeq,
      String processCode,
      String processName,
      String laborType,
      BigDecimal workingHours,
      String originalTimeUnit,
      BigDecimal standardHours,
      String standardTimeUnit,
      BigDecimal conversionFactor,
      BigDecimal wageRate,
      String rateUnit,
      BigDecimal hourlyRate,
      BigDecimal personCoefficient,
      BigDecimal amount,
      String sourceReferenceId,
      String sourceReferenceVersion,
      String sourceSnapshotJson,
      String remark) {}
}
