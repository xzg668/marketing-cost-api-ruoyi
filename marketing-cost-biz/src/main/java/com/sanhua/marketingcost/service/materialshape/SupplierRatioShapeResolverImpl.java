package com.sanhua.marketingcost.service.materialshape;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** QEB 报价专用的确定性主供应商解析，不改变旧供货比例取价服务。 */
@Service
public class SupplierRatioShapeResolverImpl
    implements SupplierRatioShapeResolver {

  private static final BigDecimal ZERO = BigDecimal.ZERO;

  private final SupplierSupplyRatioMapper mapper;

  public SupplierRatioShapeResolverImpl(SupplierSupplyRatioMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  @Transactional(readOnly = true)
  public SupplierRatioResolution resolve(
      MaterialQuoteShapeResolution policyResolution) {
    NormalizedRequest request = normalize(policyResolution);
    return resolveNormalized(policyResolution, request, effectiveRows(request));
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, SupplierRatioResolution> resolveAll(
      List<MaterialQuoteShapeResolution> policyResolutions) {
    if (policyResolutions == null || policyResolutions.isEmpty()) {
      return Map.of();
    }
    Map<BatchKey, List<ResolutionRequest>> groups =
        policyResolutions.stream()
            .map(resolution -> new ResolutionRequest(resolution, normalize(resolution)))
            .collect(
                Collectors.groupingBy(
                    request ->
                        new BatchKey(
                            request.request().materialOrganizationCode(),
                            request.request().accountingMonth()),
                    LinkedHashMap::new,
                    Collectors.toList()));
    Map<String, SupplierRatioResolution> results = new LinkedHashMap<>();
    for (List<ResolutionRequest> group : groups.values()) {
      Map<String, List<SupplierSupplyRatio>> candidatesByMaterial =
          effectiveRows(group.stream().map(ResolutionRequest::request).toList());
      for (ResolutionRequest entry : group) {
        String materialCode = entry.request().materialCode();
        if (results.containsKey(materialCode)) {
          throw new IllegalArgumentException(
              "批量供货比例形态解析存在重复料号: " + materialCode);
        }
        results.put(
            materialCode,
            resolveNormalized(
                entry.resolution(),
                entry.request(),
                candidatesByMaterial.getOrDefault(materialCode, List.of())));
      }
    }
    return Map.copyOf(results);
  }

  private SupplierRatioResolution resolveNormalized(
      MaterialQuoteShapeResolution policyResolution,
      NormalizedRequest request,
      List<SupplierSupplyRatio> candidates) {
    if (candidates.isEmpty()) {
      return fallbackToU9(request, policyResolution);
    }

    String duplicateCode = duplicateSupplierCode(candidates);
    if (duplicateCode != null) {
      return blocked(
          request,
          policyResolution,
          "同一供应商存在重复有效供货比例记录，不能聚合或任选: supplierCode="
              + duplicateCode);
    }

    BigDecimal maximum =
        candidates.stream()
            .map(SupplierSupplyRatio::getSupplyRatio)
            .max(BigDecimal::compareTo)
            .orElseThrow();
    List<SupplierSupplyRatio> maximumRows =
        candidates.stream()
            .filter(row -> row.getSupplyRatio().compareTo(maximum) == 0)
            .toList();
    if (maximumRows.size() > 1) {
      return blocked(
          request,
          policyResolution,
          "最大供货比例并列，不能按更新时间、导入顺序或ID猜供应商: ratio="
              + maximum.toPlainString()
              + ", suppliers="
              + supplierEvidence(maximumRows));
    }

    SupplierSupplyRatio selected = maximumRows.getFirst();
    String selectedCode = trimToNull(selected.getSupplierCode());
    QuoteMaterialShape effectiveShape;
    try {
      effectiveShape = targetShapeFromRelationship(selected.getMaterialShape());
    } catch (IllegalArgumentException ex) {
      return blocked(
          request,
          policyResolution,
          "主供应商供货关系的形态属性无法判断内外部: 记录ID="
              + selected.getId()
              + ", materialShape="
              + selected.getMaterialShape()
              + ", "
              + ex.getMessage());
    }
    boolean internal = effectiveShape == QuoteMaterialShape.MANUFACTURE;
    return new SupplierRatioResolution(
        request.materialOrganizationCode(),
        request.priceOrgCode(),
        request.materialCode(),
        request.accountingMonth(),
        effectiveShape,
        policyResolution.policyId(),
        policyResolution.policyFingerprint(),
        selected.getId(),
        selectedCode,
        trimToNull(selected.getSupplierName()),
        selected.getSupplyRatio(),
        internal,
        policyResolution.conditionConfigJson(),
        policyResolution.actionConfigJson(),
        null);
  }

  /**
   * 供货关系中的形态属性就是内外部判断依据，不再维护第二份供应商名单。
   * 制造/自制/内部关系按制造件；采购/委外/外部关系在报价 BOM 中统一按委外加工件。
   */
  private static QuoteMaterialShape targetShapeFromRelationship(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException("形态属性不能为空");
    }
    return switch (normalized.toUpperCase(Locale.ROOT)) {
      case "MANUFACTURE", "MANUFACTURED", "制造", "制造件", "自制", "自制件", "内部", "内部供应商" ->
          QuoteMaterialShape.MANUFACTURE;
      case "PURCHASE", "PURCHASED", "采购", "采购件", "OUTSOURCE", "OUTSOURCED", "委外", "委外件", "委外加工", "委外加工件", "外部", "外部供应商" ->
          QuoteMaterialShape.OUTSOURCE;
      default -> throw new IllegalArgumentException("仅支持制造/自制/内部或采购/委外/外部关系");
    };
  }

  private List<SupplierSupplyRatio> effectiveRows(NormalizedRequest request) {
    return effectiveRows(List.of(request))
        .getOrDefault(request.materialCode(), List.of());
  }

  private Map<String, List<SupplierSupplyRatio>> effectiveRows(
      List<NormalizedRequest> requests) {
    NormalizedRequest first = requests.getFirst();
    LocalDate monthStart = first.month().atDay(1);
    Set<String> materialCodes =
        requests.stream()
            .map(NormalizedRequest::materialCode)
            .collect(Collectors.toSet());
    List<SupplierSupplyRatio> rows =
        mapper.selectList(
            new QueryWrapper<SupplierSupplyRatio>()
                .eq("business_unit_type", first.materialOrganizationCode())
                .in("material_code", materialCodes)
                .eq("deleted", 0)
                .and(
                    nested ->
                        nested
                            .isNull("effective_from")
                            .or()
                            .le("effective_from", monthStart))
                .and(
                    nested ->
                        nested
                            .isNull("effective_to")
                            .or()
                            .ge("effective_to", monthStart)));
    if (rows == null || rows.isEmpty()) {
      return Map.of();
    }
    return rows.stream()
        .filter(row -> row != null)
        .filter(row -> Integer.valueOf(0).equals(row.getDeleted()))
        .filter(
            row ->
                equalsIgnoreCase(
                    first.materialOrganizationCode(),
                    row.getBusinessUnitType()))
        .filter(row -> materialCodes.contains(trimToNull(row.getMaterialCode())))
        .filter(row -> activeOn(row, monthStart))
        .filter(row -> row.getSupplyRatio() != null)
        .filter(row -> row.getSupplyRatio().compareTo(ZERO) > 0)
        .collect(
            Collectors.groupingBy(
                row -> trimToNull(row.getMaterialCode()),
                LinkedHashMap::new,
                Collectors.toList()));
  }

  private static String duplicateSupplierCode(
      List<SupplierSupplyRatio> candidates) {
    Map<String, List<Long>> idsByCode = new LinkedHashMap<>();
    for (SupplierSupplyRatio row : candidates) {
      String code = trimToNull(row.getSupplierCode());
      if (code != null) {
        idsByCode
            .computeIfAbsent(codeKey(code), ignored -> new ArrayList<>())
            .add(row.getId());
      }
    }
    return idsByCode.entrySet().stream()
        .filter(entry -> entry.getValue().size() > 1)
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(null);
  }

  private static String supplierEvidence(
      List<SupplierSupplyRatio> rows) {
    return rows.stream()
        .map(
            row -> {
              String code = trimToNull(row.getSupplierCode());
              return (code == null ? "<缺失编码>" : code)
                  + "#"
                  + row.getId();
            })
        .sorted()
        .toList()
        .toString();
  }

  private static SupplierRatioResolution blocked(
      NormalizedRequest request,
      MaterialQuoteShapeResolution policyResolution,
      String reason) {
    return new SupplierRatioResolution(
        request.materialOrganizationCode(),
        request.priceOrgCode(),
        request.materialCode(),
        request.accountingMonth(),
        null,
        policyResolution.policyId(),
        policyResolution.policyFingerprint(),
        null,
        null,
        null,
        null,
        null,
        policyResolution.conditionConfigJson(),
        policyResolution.actionConfigJson(),
        reason);
  }

  /** 没有当月供货关系时保持原始 U9 形态和原始 BOM，不执行委外子件排除动作。 */
  private static SupplierRatioResolution fallbackToU9(
      NormalizedRequest request,
      MaterialQuoteShapeResolution policyResolution) {
    QuoteMaterialShape defaultShape = policyResolution.normalizedU9Shape();
    if (defaultShape == null) {
      return blocked(
          request,
          policyResolution,
          "没有有效供货比例，且无法识别 U9 默认形态: org="
              + request.materialOrganizationCode()
              + ", material="
              + request.materialCode()
              + ", month="
              + request.accountingMonth());
    }
    return new SupplierRatioResolution(
        request.materialOrganizationCode(),
        request.priceOrgCode(),
        request.materialCode(),
        request.accountingMonth(),
        defaultShape,
        policyResolution.policyId(),
        policyResolution.policyFingerprint(),
        null,
        null,
        null,
        null,
        defaultShape == QuoteMaterialShape.MANUFACTURE,
        policyResolution.conditionConfigJson(),
        null,
        null);
  }

  private static NormalizedRequest normalize(
      MaterialQuoteShapeResolution resolution) {
    if (resolution == null) {
      throw new IllegalArgumentException("供货比例形态解析输入不能为空");
    }
    if (resolution.source() != MaterialQuoteShapeSource.SUPPLIER_RATIO) {
      throw new IllegalArgumentException("只允许解析SUPPLIER_RATIO形态规则结果");
    }
    String org = MaterialOrganization.normalize(resolution.materialOrgCode());
    MaterialOrganization organization = MaterialOrganization.fromCode(org);
    String materialCode = required("料号", resolution.materialCode());
    String monthText = required("核算月份", resolution.accountingMonth());
    YearMonth month;
    try {
      month = YearMonth.parse(monthText);
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException("核算月份格式错误，应为YYYY-MM", ex);
    }
    return new NormalizedRequest(
        org,
        organization.getPriceOrgCode(),
        materialCode,
        month.toString(),
        month);
  }

  private static boolean activeOn(
      SupplierSupplyRatio row, LocalDate date) {
    return (row.getEffectiveFrom() == null
            || !row.getEffectiveFrom().isAfter(date))
        && (row.getEffectiveTo() == null
            || !row.getEffectiveTo().isBefore(date));
  }

  private static boolean equalsIgnoreCase(String left, String right) {
    return right != null && left.equalsIgnoreCase(right.trim());
  }

  private static String required(String field, String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return normalized;
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static String codeKey(String value) {
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private record NormalizedRequest(
      String materialOrganizationCode,
      String priceOrgCode,
      String materialCode,
      String accountingMonth,
      YearMonth month) {}

  private record ResolutionRequest(
      MaterialQuoteShapeResolution resolution, NormalizedRequest request) {}

  private record BatchKey(
      String materialOrganizationCode, String accountingMonth) {}
}
