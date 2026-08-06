package com.sanhua.marketingcost.service.materialshape;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.MaterialQuoteShapePolicy;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.mapper.MaterialQuoteShapePolicyMapper;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 固定形态规则和 U9 回退解析；SUPPLIER_RATIO 留给 QEB-05。 */
@Service
public class MaterialQuoteShapeResolverImpl
    implements MaterialQuoteShapeResolver {

  private final MaterialQuoteShapePolicyMapper policyMapper;
  private final ShapePolicyFingerprint fingerprint;

  public MaterialQuoteShapeResolverImpl(
      MaterialQuoteShapePolicyMapper policyMapper,
      ShapePolicyFingerprint fingerprint) {
    this.policyMapper = policyMapper;
    this.fingerprint = fingerprint;
  }

  @Override
  @Transactional(readOnly = true)
  public MaterialQuoteShapeResolution resolve(
      MaterialQuoteShapeRequest request) {
    NormalizedRequest normalized = normalizeRequest(request);
    List<MaterialQuoteShapePolicy> policies =
        findEffectivePolicies(normalized);
    if (policies.size() > 1) {
      throw new IllegalStateException(
          "同一组织、料号、月份命中多条启用形态规则: org="
              + normalized.materialOrgCode()
              + ", material="
              + normalized.materialCode()
              + ", month="
              + normalized.accountingMonth());
    }
    if (policies.isEmpty()) {
      return resolveFromU9(normalized);
    }
    return resolveFromPolicy(normalized, policies.getFirst());
  }

  @Override
  @Transactional(readOnly = true)
  public Map<String, MaterialQuoteShapeResolution> resolveAll(
      List<MaterialQuoteShapeRequest> requests) {
    if (requests == null || requests.isEmpty()) {
      return Map.of();
    }
    Map<BatchKey, List<NormalizedRequest>> groups =
        requests.stream()
            .map(MaterialQuoteShapeResolverImpl::normalizeRequest)
            .collect(
                Collectors.groupingBy(
                    request ->
                        new BatchKey(
                            request.materialOrgCode(), request.accountingMonth()),
                    LinkedHashMap::new,
                    Collectors.toList()));
    Map<String, MaterialQuoteShapeResolution> results = new LinkedHashMap<>();
    for (List<NormalizedRequest> group : groups.values()) {
      Map<String, List<MaterialQuoteShapePolicy>> policiesByMaterial =
          findEffectivePolicies(group);
      for (NormalizedRequest request : group) {
        if (results.containsKey(request.materialCode())) {
          throw new IllegalArgumentException(
              "批量形态解析存在重复料号: " + request.materialCode());
        }
        List<MaterialQuoteShapePolicy> policies =
            policiesByMaterial.getOrDefault(request.materialCode(), List.of());
        if (policies.size() > 1) {
          results.put(
              request.materialCode(),
              blockedResult(
                  request,
                  "同一组织、料号、月份命中多条启用形态规则: org="
                      + request.materialOrgCode()
                      + ", material="
                      + request.materialCode()
                      + ", month="
                      + request.accountingMonth()));
        } else if (policies.isEmpty()) {
          results.put(request.materialCode(), resolveFromU9(request));
        } else {
          results.put(
              request.materialCode(), resolveFromPolicy(request, policies.getFirst()));
        }
      }
    }
    return Map.copyOf(results);
  }

  private List<MaterialQuoteShapePolicy> findEffectivePolicies(
      NormalizedRequest request) {
    List<MaterialQuoteShapePolicy> rows =
        policyMapper.selectList(
            Wrappers.<MaterialQuoteShapePolicy>lambdaQuery()
                .eq(
                    MaterialQuoteShapePolicy::getMaterialOrgCode,
                    request.materialOrgCode())
                .eq(
                    MaterialQuoteShapePolicy::getMaterialCode,
                    request.materialCode())
                .eq(
                    MaterialQuoteShapePolicy::getEnabled,
                    MaterialQuoteShapePolicy.ENABLED)
                .le(
                    MaterialQuoteShapePolicy::getEffectiveFromMonth,
                    request.accountingMonth())
                .and(
                    nested ->
                        nested
                            .isNull(
                                MaterialQuoteShapePolicy::getEffectiveToMonth)
                            .or()
                            .ge(
                                MaterialQuoteShapePolicy::getEffectiveToMonth,
                                request.accountingMonth())));
    if (rows == null || rows.isEmpty()) {
      return List.of();
    }
    YearMonth month = YearMonth.parse(request.accountingMonth());
    return rows.stream()
        .filter(row -> MaterialQuoteShapePolicy.ENABLED.equals(row.getEnabled()))
        .filter(
            row -> request.materialOrgCode().equals(row.getMaterialOrgCode()))
        .filter(row -> request.materialCode().equals(row.getMaterialCode()))
        .filter(row -> isEffective(row, month))
        .toList();
  }

  private Map<String, List<MaterialQuoteShapePolicy>> findEffectivePolicies(
      List<NormalizedRequest> requests) {
    NormalizedRequest first = requests.getFirst();
    Set<String> materialCodes =
        requests.stream()
            .map(NormalizedRequest::materialCode)
            .collect(Collectors.toSet());
    List<MaterialQuoteShapePolicy> rows =
        policyMapper.selectList(
            Wrappers.<MaterialQuoteShapePolicy>lambdaQuery()
                .eq(MaterialQuoteShapePolicy::getMaterialOrgCode, first.materialOrgCode())
                .in(MaterialQuoteShapePolicy::getMaterialCode, materialCodes)
                .eq(MaterialQuoteShapePolicy::getEnabled, MaterialQuoteShapePolicy.ENABLED)
                .le(MaterialQuoteShapePolicy::getEffectiveFromMonth, first.accountingMonth())
                .and(
                    nested ->
                        nested
                            .isNull(MaterialQuoteShapePolicy::getEffectiveToMonth)
                            .or()
                            .ge(
                                MaterialQuoteShapePolicy::getEffectiveToMonth,
                                first.accountingMonth())));
    if (rows == null || rows.isEmpty()) {
      return Map.of();
    }
    YearMonth month = YearMonth.parse(first.accountingMonth());
    return rows.stream()
        .filter(row -> row != null)
        .filter(row -> MaterialQuoteShapePolicy.ENABLED.equals(row.getEnabled()))
        .filter(row -> first.materialOrgCode().equals(row.getMaterialOrgCode()))
        .filter(row -> materialCodes.contains(row.getMaterialCode()))
        .filter(row -> isEffective(row, month))
        .collect(
            Collectors.groupingBy(
                MaterialQuoteShapePolicy::getMaterialCode,
                LinkedHashMap::new,
                Collectors.toList()));
  }

  private static MaterialQuoteShapeResolution blockedResult(
      NormalizedRequest request, String reason) {
    return result(
        request,
        tryNormalizeU9(request.sourceU9Shape()),
        null,
        MaterialQuoteShapeSource.U9,
        null,
        null,
        null,
        null,
        reason);
  }

  private MaterialQuoteShapeResolution resolveFromU9(
      NormalizedRequest request) {
    try {
      QuoteMaterialShape u9Shape =
          QuoteMaterialShape.fromU9(request.sourceU9Shape());
      return result(
          request,
          u9Shape,
          u9Shape,
          MaterialQuoteShapeSource.U9,
          null,
          null,
          null,
          null,
          null);
    } catch (IllegalArgumentException ex) {
      return result(
          request,
          null,
          null,
          MaterialQuoteShapeSource.U9,
          null,
          null,
          null,
          null,
          "无法识别料号 "
              + request.materialCode()
              + " 的 U9 料品形态: "
              + display(request.sourceU9Shape()));
    }
  }

  private MaterialQuoteShapeResolution resolveFromPolicy(
      NormalizedRequest request, MaterialQuoteShapePolicy policy) {
    String policyFingerprint = fingerprint.calculate(policy);
    QuoteMaterialShape normalizedU9 = tryNormalizeU9(request.sourceU9Shape());
    String mode = upper(policy.getPolicyMode());
    if (MaterialQuoteShapePolicy.MODE_FIXED.equals(mode)) {
      try {
        QuoteMaterialShape effective =
            QuoteMaterialShape.valueOf(
                QuoteMaterialShape.normalize(policy.getFixedTargetShape()));
        return result(
            request,
            normalizedU9,
            effective,
            MaterialQuoteShapeSource.FIXED_POLICY,
            policy,
            policyFingerprint,
            policy.getConditionConfigJson(),
            policy.getActionConfigJson(),
            null);
      } catch (IllegalArgumentException ex) {
        return result(
            request,
            normalizedU9,
            null,
            MaterialQuoteShapeSource.FIXED_POLICY,
            policy,
            policyFingerprint,
            policy.getConditionConfigJson(),
            policy.getActionConfigJson(),
            "固定形态规则目标值非法: policyId=" + policy.getId());
      }
    }
    if (MaterialQuoteShapePolicy.MODE_SUPPLIER_RATIO.equals(mode)) {
      return result(
          request,
          normalizedU9,
          null,
          MaterialQuoteShapeSource.SUPPLIER_RATIO,
          policy,
          policyFingerprint,
          policy.getConditionConfigJson(),
          policy.getActionConfigJson(),
          "命中供货比例形态规则，必须由 QEB-05 解析主供应商后确定最终形态");
    }
    throw new IllegalStateException(
        "未知形态规则模式: policyId=" + policy.getId() + ", mode=" + mode);
  }

  private static MaterialQuoteShapeResolution result(
      NormalizedRequest request,
      QuoteMaterialShape normalizedU9,
      QuoteMaterialShape effective,
      MaterialQuoteShapeSource source,
      MaterialQuoteShapePolicy policy,
      String policyFingerprint,
      String conditionConfigJson,
      String actionConfigJson,
      String blockingReason) {
    return new MaterialQuoteShapeResolution(
        request.materialOrgCode(),
        request.materialCode(),
        request.accountingMonth(),
        request.sourceU9Shape(),
        normalizedU9,
        effective,
        source,
        policy == null ? null : policy.getId(),
        policyFingerprint,
        conditionConfigJson,
        actionConfigJson,
        blockingReason);
  }

  private static boolean isEffective(
      MaterialQuoteShapePolicy policy, YearMonth month) {
    YearMonth from = parseStoredMonth(policy.getEffectiveFromMonth(), "生效开始月");
    YearMonth to =
        StringUtils.hasText(policy.getEffectiveToMonth())
            ? parseStoredMonth(policy.getEffectiveToMonth(), "生效结束月")
            : null;
    return !month.isBefore(from) && (to == null || !month.isAfter(to));
  }

  private static NormalizedRequest normalizeRequest(
      MaterialQuoteShapeRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("形态解析请求不能为空");
    }
    String org = MaterialOrganization.normalize(request.materialOrgCode());
    String materialCode = required("料号", request.materialCode());
    String month = normalizeMonth(request.accountingMonth());
    return new NormalizedRequest(
        org, materialCode, month, trimToNull(request.sourceU9Shape()));
  }

  private static String normalizeMonth(String value) {
    String normalized = required("核算月份", value);
    try {
      return YearMonth.parse(normalized).toString();
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException("核算月份格式错误，应为 YYYY-MM", ex);
    }
  }

  private static YearMonth parseStoredMonth(String value, String field) {
    try {
      return YearMonth.parse(value);
    } catch (RuntimeException ex) {
      throw new IllegalStateException("形态规则" + field + "非法: " + value, ex);
    }
  }

  private static QuoteMaterialShape tryNormalizeU9(String value) {
    try {
      return QuoteMaterialShape.fromU9(value);
    } catch (IllegalArgumentException ex) {
      return null;
    }
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

  private static String upper(String value) {
    String normalized = trimToNull(value);
    return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
  }

  private static String display(String value) {
    return value == null ? "<空>" : value;
  }

  private record NormalizedRequest(
      String materialOrgCode,
      String materialCode,
      String accountingMonth,
      String sourceU9Shape) {}

  private record BatchKey(String materialOrgCode, String accountingMonth) {}
}
