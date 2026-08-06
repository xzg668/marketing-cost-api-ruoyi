package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyQuery;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyRequest;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyResponse;
import com.sanhua.marketingcost.entity.MaterialQuoteShapePolicy;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.mapper.MaterialQuoteShapePolicyMapper;
import com.sanhua.marketingcost.service.MaterialQuoteShapePolicyService;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 料品报价形态规则维护实现。只读写规则表，不触碰报价最终 BOM 和历史成本。 */
@Service
public class MaterialQuoteShapePolicyServiceImpl
    implements MaterialQuoteShapePolicyService {

  private static final Set<String> MODES =
      Set.of(
          MaterialQuoteShapePolicy.MODE_FIXED,
          MaterialQuoteShapePolicy.MODE_SUPPLIER_RATIO);

  private final MaterialQuoteShapePolicyMapper mapper;
  private final ObjectMapper objectMapper;

  public MaterialQuoteShapePolicyServiceImpl(
      MaterialQuoteShapePolicyMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public List<MaterialQuoteShapePolicyResponse> list(
      MaterialQuoteShapePolicyQuery query) {
    MaterialQuoteShapePolicyQuery filter =
        query == null ? new MaterialQuoteShapePolicyQuery() : query;
    String orgCode = normalizeOptionalOrganization(filter.getMaterialOrgCode());
    String materialCode = trimToNull(filter.getMaterialCode());
    String materialName = trimToNull(filter.getMaterialName());
    String materialSpec = trimToNull(filter.getMaterialSpec());
    String materialModel = trimToNull(filter.getMaterialModel());
    String policyMode = normalizeOptionalMode(filter.getPolicyMode());
    String effectiveMonth = normalizeOptionalMonth(filter.getEffectiveMonth(), "生效月份");
    validateEnabled(filter.getEnabled(), false);

    var wrapper =
        Wrappers.<MaterialQuoteShapePolicy>lambdaQuery()
            .eq(
                orgCode != null,
                MaterialQuoteShapePolicy::getMaterialOrgCode,
                orgCode)
            .like(
                materialCode != null,
                MaterialQuoteShapePolicy::getMaterialCode,
                materialCode)
            .like(
                materialName != null,
                MaterialQuoteShapePolicy::getMaterialName,
                materialName)
            .like(
                materialSpec != null,
                MaterialQuoteShapePolicy::getMaterialSpec,
                materialSpec)
            .like(
                materialModel != null,
                MaterialQuoteShapePolicy::getMaterialModel,
                materialModel)
            .eq(
                policyMode != null,
                MaterialQuoteShapePolicy::getPolicyMode,
                policyMode)
            .eq(
                filter.getEnabled() != null,
                MaterialQuoteShapePolicy::getEnabled,
                filter.getEnabled());
    if (effectiveMonth != null) {
      wrapper
          .le(
              MaterialQuoteShapePolicy::getEffectiveFromMonth,
              effectiveMonth)
          .and(
              nested ->
                  nested
                      .isNull(MaterialQuoteShapePolicy::getEffectiveToMonth)
                      .or()
                      .ge(
                          MaterialQuoteShapePolicy::getEffectiveToMonth,
                          effectiveMonth));
    }
    wrapper
        .orderByDesc(MaterialQuoteShapePolicy::getEnabled)
        .orderByDesc(MaterialQuoteShapePolicy::getEffectiveFromMonth)
        .orderByDesc(MaterialQuoteShapePolicy::getId);
    return mapper.selectList(wrapper).stream()
        .map(MaterialQuoteShapePolicyResponse::from)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public MaterialQuoteShapePolicyResponse get(Long id) {
    return MaterialQuoteShapePolicyResponse.from(requiredExisting(id));
  }

  @Override
  @Transactional
  public MaterialQuoteShapePolicyResponse create(
      MaterialQuoteShapePolicyRequest request) {
    MaterialQuoteShapePolicy entity = normalizeRequest(request);
    validateNoEnabledOverlap(entity, null);
    LocalDateTime now = LocalDateTime.now();
    entity.setCreatedAt(now);
    entity.setUpdatedAt(now);
    if (mapper.insert(entity) != 1) {
      throw new IllegalStateException("料品形态规则保存失败");
    }
    return MaterialQuoteShapePolicyResponse.from(entity);
  }

  @Override
  @Transactional
  public MaterialQuoteShapePolicyResponse update(
      Long id, MaterialQuoteShapePolicyRequest request) {
    MaterialQuoteShapePolicy existing = requiredExisting(id);
    MaterialQuoteShapePolicy entity = normalizeRequest(request);
    entity.setId(existing.getId());
    entity.setCreatedAt(existing.getCreatedAt());
    entity.setCreatedBy(existing.getCreatedBy());
    entity.setUpdatedAt(LocalDateTime.now());
    entity.setUpdatedBy(existing.getUpdatedBy());
    validateNoEnabledOverlap(entity, id);
    if (mapper.updateById(entity) != 1) {
      throw new IllegalStateException("料品形态规则修改失败: id=" + id);
    }
    return MaterialQuoteShapePolicyResponse.from(entity);
  }

  @Override
  @Transactional
  public MaterialQuoteShapePolicyResponse setEnabled(Long id, Integer enabled) {
    validateEnabled(enabled, true);
    MaterialQuoteShapePolicy existing = requiredExisting(id);
    MaterialQuoteShapePolicy updated = copy(existing);
    updated.setEnabled(enabled);
    updated.setUpdatedAt(LocalDateTime.now());
    if (MaterialQuoteShapePolicy.ENABLED.equals(enabled)) {
      validateNoEnabledOverlap(updated, id);
    }
    if (mapper.updateById(updated) != 1) {
      throw new IllegalStateException("料品形态规则启停失败: id=" + id);
    }
    return MaterialQuoteShapePolicyResponse.from(updated);
  }

  @Override
  @Transactional
  public boolean delete(Long id) {
    if (id == null || id <= 0 || mapper.selectById(id) == null) {
      return false;
    }
    return mapper.deleteById(id) == 1;
  }

  private MaterialQuoteShapePolicy normalizeRequest(
      MaterialQuoteShapePolicyRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("规则内容不能为空");
    }
    MaterialQuoteShapePolicy entity = new MaterialQuoteShapePolicy();
    entity.setMaterialOrgCode(
        MaterialOrganization.normalize(request.getMaterialOrgCode()));
    entity.setMaterialCode(
        requiredText("料号", request.getMaterialCode(), 64));
    entity.setMaterialName(optionalText("名称", request.getMaterialName(), 255));
    entity.setMaterialSpec(optionalText("规格", request.getMaterialSpec(), 255));
    entity.setMaterialModel(optionalText("型号", request.getMaterialModel(), 255));
    String mode = normalizeRequiredMode(request.getPolicyMode());
    entity.setPolicyMode(mode);
    entity.setEffectiveFromMonth(
        normalizeRequiredMonth(request.getEffectiveFromMonth(), "生效开始月"));
    entity.setEffectiveToMonth(
        normalizeOptionalMonth(request.getEffectiveToMonth(), "生效结束月"));
    YearMonth from = YearMonth.parse(entity.getEffectiveFromMonth());
    if (entity.getEffectiveToMonth() != null
        && YearMonth.parse(entity.getEffectiveToMonth()).isBefore(from)) {
      throw new IllegalArgumentException("生效结束月不能早于生效开始月");
    }
    Integer enabled =
        request.getEnabled() == null
            ? MaterialQuoteShapePolicy.ENABLED
            : request.getEnabled();
    validateEnabled(enabled, true);
    entity.setEnabled(enabled);
    entity.setRemark(optionalText("备注", request.getRemark(), 1000));

    if (MaterialQuoteShapePolicy.MODE_FIXED.equals(mode)) {
      entity.setFixedTargetShape(
          normalizeConfigurableFixedShape(request.getFixedTargetShape()));
      entity.setConditionConfigJson(null);
      entity.setActionConfigJson(null);
    } else {
      entity.setFixedTargetShape(null);
      // 主供应商及内外部属性统一来自供货比率关系表，不在规则中重复维护供应商名单。
      entity.setConditionConfigJson(null);
      entity.setActionConfigJson(
          normalizeActionJson(request.getActionConfigJson()));
    }
    return entity;
  }

  private static String normalizeConfigurableFixedShape(String value) {
    String normalized = QuoteMaterialShape.normalize(value);
    if (QuoteMaterialShape.VIRTUAL.name().equals(normalized)) {
      throw new IllegalArgumentException("固定报价形态不再支持虚拟件");
    }
    return normalized;
  }

  private String normalizeActionJson(String json) {
    if (!StringUtils.hasText(json)) {
      return null;
    }
    JsonNode root = readObject(json, "动作 JSON");
    JsonNode excluded = root.get("excludedDirectChildMaterialCodes");
    if (excluded != null && !excluded.isArray()) {
      throw new IllegalArgumentException(
          "动作 JSON 的 excludedDirectChildMaterialCodes 必须是数组");
    }
    LinkedHashSet<String> excludedCodes = new LinkedHashSet<>();
    if (excluded != null) {
      for (JsonNode item : excluded) {
        String code = item.isTextual() ? trimToNull(item.asText()) : null;
        if (code == null) {
          throw new IllegalArgumentException("排除子件料号不能为空");
        }
        excludedCodes.add(code);
      }
    }
    if (excludedCodes.isEmpty()) {
      return null;
    }
    Map<String, Object> normalized = new LinkedHashMap<>();
    normalized.put(
        "excludedDirectChildMaterialCodes", new ArrayList<>(excludedCodes));
    return writeJson(normalized, "动作 JSON");
  }

  private JsonNode readObject(String json, String fieldName) {
    if (!StringUtils.hasText(json)) {
      throw new IllegalArgumentException(fieldName + "不能为空");
    }
    try {
      JsonNode root = objectMapper.readTree(json);
      if (root == null || !root.isObject()) {
        throw new IllegalArgumentException(fieldName + "必须是 JSON 对象");
      }
      return root;
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(fieldName + "格式非法", ex);
    }
  }

  private String writeJson(Map<String, Object> value, String fieldName) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException(fieldName + "无法规范化", ex);
    }
  }

  private void validateNoEnabledOverlap(
      MaterialQuoteShapePolicy candidate, Long excludedId) {
    if (!MaterialQuoteShapePolicy.ENABLED.equals(candidate.getEnabled())) {
      return;
    }
    List<MaterialQuoteShapePolicy> existingRows =
        mapper.selectList(
            Wrappers.<MaterialQuoteShapePolicy>lambdaQuery()
                .eq(
                    MaterialQuoteShapePolicy::getMaterialOrgCode,
                    candidate.getMaterialOrgCode())
                .eq(
                    MaterialQuoteShapePolicy::getMaterialCode,
                    candidate.getMaterialCode())
                .eq(
                    MaterialQuoteShapePolicy::getEnabled,
                    MaterialQuoteShapePolicy.ENABLED));
    YearMonth candidateFrom = YearMonth.parse(candidate.getEffectiveFromMonth());
    YearMonth candidateTo = parseNullableMonth(candidate.getEffectiveToMonth());
    for (MaterialQuoteShapePolicy existing : existingRows) {
      if (!MaterialQuoteShapePolicy.ENABLED.equals(existing.getEnabled())
          || (excludedId != null && excludedId.equals(existing.getId()))
          || !candidate.getMaterialOrgCode().equals(existing.getMaterialOrgCode())
          || !candidate.getMaterialCode().equals(existing.getMaterialCode())) {
        continue;
      }
      YearMonth existingFrom =
          parseStoredMonth(existing.getEffectiveFromMonth(), "已有规则生效开始月");
      YearMonth existingTo =
          parseStoredNullableMonth(existing.getEffectiveToMonth(), "已有规则生效结束月");
      if (rangesOverlap(candidateFrom, candidateTo, existingFrom, existingTo)) {
        throw new IllegalArgumentException(
            "同一组织、料号的启用规则月份重叠，冲突规则 ID=" + existing.getId());
      }
    }
  }

  private static boolean rangesOverlap(
      YearMonth leftFrom,
      YearMonth leftTo,
      YearMonth rightFrom,
      YearMonth rightTo) {
    boolean leftEndsAfterRightStarts =
        leftTo == null || !leftTo.isBefore(rightFrom);
    boolean rightEndsAfterLeftStarts =
        rightTo == null || !rightTo.isBefore(leftFrom);
    return leftEndsAfterRightStarts && rightEndsAfterLeftStarts;
  }

  private MaterialQuoteShapePolicy requiredExisting(Long id) {
    if (id == null || id <= 0) {
      throw new IllegalArgumentException("规则 ID 必须大于 0");
    }
    MaterialQuoteShapePolicy existing = mapper.selectById(id);
    if (existing == null) {
      throw new IllegalArgumentException("料品形态规则不存在: id=" + id);
    }
    return existing;
  }

  private static MaterialQuoteShapePolicy copy(
      MaterialQuoteShapePolicy source) {
    MaterialQuoteShapePolicy target = new MaterialQuoteShapePolicy();
    target.setId(source.getId());
    target.setMaterialOrgCode(source.getMaterialOrgCode());
    target.setMaterialCode(source.getMaterialCode());
    target.setMaterialName(source.getMaterialName());
    target.setMaterialSpec(source.getMaterialSpec());
    target.setMaterialModel(source.getMaterialModel());
    target.setPolicyMode(source.getPolicyMode());
    target.setFixedTargetShape(source.getFixedTargetShape());
    target.setConditionConfigJson(source.getConditionConfigJson());
    target.setActionConfigJson(source.getActionConfigJson());
    target.setEffectiveFromMonth(source.getEffectiveFromMonth());
    target.setEffectiveToMonth(source.getEffectiveToMonth());
    target.setEnabled(source.getEnabled());
    target.setRemark(source.getRemark());
    target.setCreatedAt(source.getCreatedAt());
    target.setCreatedBy(source.getCreatedBy());
    target.setUpdatedAt(source.getUpdatedAt());
    target.setUpdatedBy(source.getUpdatedBy());
    return target;
  }

  private static void validateEnabled(Integer enabled, boolean required) {
    if (enabled == null && !required) {
      return;
    }
    if (!MaterialQuoteShapePolicy.ENABLED.equals(enabled)
        && !MaterialQuoteShapePolicy.DISABLED.equals(enabled)) {
      throw new IllegalArgumentException("enabled 仅支持 0 或 1");
    }
  }

  private static String normalizeRequiredMode(String value) {
    String mode = normalizeOptionalMode(value);
    if (mode == null) {
      throw new IllegalArgumentException("规则模式不能为空");
    }
    return mode;
  }

  private static String normalizeOptionalMode(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    String mode = normalized.toUpperCase(Locale.ROOT);
    if (!MODES.contains(mode)) {
      throw new IllegalArgumentException(
          "规则模式仅支持 FIXED 或 SUPPLIER_RATIO");
    }
    return mode;
  }

  private static String normalizeOptionalOrganization(String value) {
    return StringUtils.hasText(value) ? MaterialOrganization.normalize(value) : null;
  }

  private static String normalizeRequiredMonth(String value, String fieldName) {
    String normalized = normalizeOptionalMonth(value, fieldName);
    if (normalized == null) {
      throw new IllegalArgumentException(fieldName + "不能为空");
    }
    return normalized;
  }

  private static String normalizeOptionalMonth(String value, String fieldName) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    try {
      return YearMonth.parse(normalized).toString();
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException(fieldName + "格式错误，应为 YYYY-MM", ex);
    }
  }

  private static YearMonth parseNullableMonth(String value) {
    return value == null ? null : YearMonth.parse(value);
  }

  private static YearMonth parseStoredMonth(String value, String fieldName) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalStateException(fieldName + "为空");
    }
    try {
      return YearMonth.parse(value);
    } catch (DateTimeParseException ex) {
      throw new IllegalStateException(fieldName + "格式非法: " + value, ex);
    }
  }

  private static YearMonth parseStoredNullableMonth(
      String value, String fieldName) {
    return StringUtils.hasText(value) ? parseStoredMonth(value, fieldName) : null;
  }

  private static String requiredText(
      String fieldName, String value, int maxLength) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      throw new IllegalArgumentException(fieldName + "不能为空");
    }
    validateLength(fieldName, normalized, maxLength);
    return normalized;
  }

  private static String optionalText(
      String fieldName, String value, int maxLength) {
    String normalized = trimToNull(value);
    if (normalized != null) {
      validateLength(fieldName, normalized, maxLength);
    }
    return normalized;
  }

  private static void validateLength(
      String fieldName, String value, int maxLength) {
    if (value.length() > maxLength) {
      throw new IllegalArgumentException(
          fieldName + "长度不能超过 " + maxLength);
    }
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
