package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
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
  private static final int SNAPSHOT_SCHEMA_VERSION = 1;
  private final ObjectMapper canonicalMapper;

  public TechnicalDataVersionContentCodec(ObjectMapper objectMapper) {
    this.canonicalMapper = objectMapper.copy()
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
        .sorted(Comparator.comparing(ModuleSnapshot::moduleType))
        .toList();
  }

  public String referenceSnapshotJson(List<ModuleSnapshot> modules) {
    return write(new ReferenceSnapshot(SNAPSHOT_SCHEMA_VERSION, List.copyOf(modules)));
  }

  public List<ModuleSnapshot> readReferenceSnapshot(String json) {
    if (json == null || json.isBlank()) {
      throw new IllegalArgumentException("历史提交版本缺少参照快照");
    }
    try {
      ReferenceSnapshot snapshot = canonicalMapper.readValue(json, ReferenceSnapshot.class);
      if (snapshot.schemaVersion() != SNAPSHOT_SCHEMA_VERSION || snapshot.modules() == null) {
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
    return sha256(write(content));
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
