package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.BindingCandidate;
import com.sanhua.marketingcost.dto.StandardBindingCheckRequest;
import com.sanhua.marketingcost.dto.StandardBindingDecision;
import com.sanhua.marketingcost.entity.MaterialFactorBindingStd;
import com.sanhua.marketingcost.mapper.MaterialFactorBindingStdMapper;
import com.sanhua.marketingcost.service.PriceLinkedStandardBindingService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PriceLinkedStandardBindingServiceImpl implements PriceLinkedStandardBindingService {

  public static final String ACTION_CREATE_HISTORY = "CREATE_HISTORY";
  public static final String ACTION_CONSISTENT = "CONSISTENT";
  public static final String ACTION_CONFLICT = "CONFLICT";
  public static final String ACTION_FAILED = "FAILED";

  private static final String STATUS_ACTIVE = "ACTIVE";
  private static final String SOURCE_EXCEL_FORMULA = "EXCEL_FORMULA";

  private final MaterialFactorBindingStdMapper standardBindingMapper;

  public PriceLinkedStandardBindingServiceImpl(
      MaterialFactorBindingStdMapper standardBindingMapper) {
    this.standardBindingMapper = standardBindingMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<StandardBindingDecision> checkAndRecord(StandardBindingCheckRequest request) {
    if (request == null) {
      return List.of(failed(null, null, "标准关系校验请求为空"));
    }
    if (!Boolean.TRUE.equals(request.getFormulaAvailable())
        || request.getCandidates().isEmpty()) {
      return List.of(failed(normalize(request.getMaterialCode()), null,
          "单价列无公式或公式未识别成功，请重新导入带公式的 Excel 或人工配置"));
    }
    return request.getCandidates().stream()
        .map(candidate -> decide(request, candidate))
        .toList();
  }

  private StandardBindingDecision decide(
      StandardBindingCheckRequest request, BindingCandidate candidate) {
    if (candidate == null || !StringUtils.hasText(candidate.getTokenName())
        || candidate.getFactorIdentityId() == null) {
      return failed(materialCode(request, candidate),
          candidate == null ? null : candidate.getTokenName(),
          "绑定候选缺少 tokenName 或 factorIdentityId");
    }

    String businessUnitType = normalize(request.getBusinessUnitType());
    if (!StringUtils.hasText(businessUnitType)) {
      return failed(materialCode(request, candidate), candidate.getTokenName(),
          "businessUnitType 必填，不能校验标准关系");
    }
    String materialCode = materialCode(request, candidate);
    if (!StringUtils.hasText(materialCode)) {
      return failed(null, candidate.getTokenName(), "materialCode 必填，不能校验标准关系");
    }
    String supplierCode = supplierCode(request, candidate);
    MaterialFactorBindingStd existing = findActive(
        businessUnitType, materialCode, supplierCode, candidate.getTokenName());
    if (existing == null) {
      MaterialFactorBindingStd created = createStandard(
          request, candidate, businessUnitType, materialCode, supplierCode);
      standardBindingMapper.insert(created);
      return decision(candidate, materialCode, ACTION_CREATE_HISTORY, created.getId(), null,
          candidate.getFactorIdentityId(), "首次识别到料号影响因素关系，已新增历史校验关系");
    }
    if (candidate.getFactorIdentityId().equals(existing.getFactorIdentityId())) {
      refreshExisting(request, existing);
      standardBindingMapper.updateById(existing);
      return decision(candidate, materialCode, ACTION_CONSISTENT, existing.getId(),
          existing.getFactorIdentityId(), candidate.getFactorIdentityId(),
          "本次公式识别结果与历史标准关系一致");
    }
    return decision(candidate, materialCode, ACTION_CONFLICT, existing.getId(),
        existing.getFactorIdentityId(), candidate.getFactorIdentityId(),
        "本次公式识别结果与历史标准关系不一致，默认不覆盖，请人工确认");
  }

  private MaterialFactorBindingStd findActive(
      String businessUnitType, String materialCode, String supplierCode, String tokenName) {
    return standardBindingMapper.selectOne(
        Wrappers.lambdaQuery(MaterialFactorBindingStd.class)
            .eq(MaterialFactorBindingStd::getBusinessUnitType, businessUnitType)
            .eq(MaterialFactorBindingStd::getMaterialCode, materialCode)
            .eq(MaterialFactorBindingStd::getSupplierCode, supplierCode)
            .eq(MaterialFactorBindingStd::getTokenName, tokenName)
            .eq(MaterialFactorBindingStd::getStatus, STATUS_ACTIVE)
            .last("LIMIT 1"));
  }

  private MaterialFactorBindingStd createStandard(
      StandardBindingCheckRequest request,
      BindingCandidate candidate,
      String businessUnitType,
      String materialCode,
      String supplierCode) {
    LocalDateTime now = LocalDateTime.now();
    MaterialFactorBindingStd standard = new MaterialFactorBindingStd();
    standard.setBusinessUnitType(businessUnitType);
    standard.setMaterialCode(materialCode);
    standard.setSupplierCode(supplierCode);
    standard.setTokenName(candidate.getTokenName());
    standard.setFactorIdentityId(candidate.getFactorIdentityId());
    standard.setSource(SOURCE_EXCEL_FORMULA);
    standard.setStatus(STATUS_ACTIVE);
    standard.setFirstImportBatchId(request.getFactorUploadBatchId());
    standard.setLastImportBatchId(request.getFactorUploadBatchId());
    standard.setLastFormula(normalize(request.getFormulaText()));
    standard.setCreatedBy(normalize(request.getOperator()));
    standard.setUpdatedBy(normalize(request.getOperator()));
    standard.setCreatedAt(now);
    standard.setUpdatedAt(now);
    return standard;
  }

  private void refreshExisting(StandardBindingCheckRequest request, MaterialFactorBindingStd existing) {
    existing.setLastImportBatchId(request.getFactorUploadBatchId());
    existing.setLastFormula(normalize(request.getFormulaText()));
    existing.setUpdatedBy(normalize(request.getOperator()));
    existing.setUpdatedAt(LocalDateTime.now());
  }

  private StandardBindingDecision decision(
      BindingCandidate candidate,
      String materialCode,
      String action,
      Long standardBindingId,
      Long oldFactorIdentityId,
      Long newFactorIdentityId,
      String reason) {
    StandardBindingDecision decision = new StandardBindingDecision();
    decision.setMaterialCode(materialCode);
    decision.setTokenName(candidate == null ? null : candidate.getTokenName());
    decision.setAction(action);
    decision.setStandardBindingId(standardBindingId);
    decision.setOldFactorIdentityId(oldFactorIdentityId);
    decision.setNewFactorIdentityId(newFactorIdentityId);
    decision.setReason(reason);
    decision.setCandidate(candidate);
    return decision;
  }

  private StandardBindingDecision failed(String materialCode, String tokenName, String reason) {
    StandardBindingDecision decision = new StandardBindingDecision();
    decision.setMaterialCode(materialCode);
    decision.setTokenName(tokenName);
    decision.setAction(ACTION_FAILED);
    decision.setReason(reason);
    return decision;
  }

  private String materialCode(StandardBindingCheckRequest request, BindingCandidate candidate) {
    if (candidate != null && StringUtils.hasText(candidate.getMaterialCode())) {
      return normalize(candidate.getMaterialCode());
    }
    return normalize(request.getMaterialCode());
  }

  private String supplierCode(StandardBindingCheckRequest request, BindingCandidate candidate) {
    if (StringUtils.hasText(request.getSupplierCode())) {
      return normalize(request.getSupplierCode());
    }
    String key = candidate != null && StringUtils.hasText(candidate.getLinkedItemImportKey())
        ? candidate.getLinkedItemImportKey()
        : request.getLinkedItemImportKey();
    if (!StringUtils.hasText(key)) {
      return "";
    }
    String[] parts = key.split("\\|", -1);
    return parts.length > 1 ? normalize(parts[1]) : "";
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }
}
