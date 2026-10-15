package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.BindingCandidate;
import com.sanhua.marketingcost.dto.StandardBindingCheckRequest;
import com.sanhua.marketingcost.dto.StandardBindingDecision;
import com.sanhua.marketingcost.mapper.PriceVariableBindingMapper;
import com.sanhua.marketingcost.service.PriceLinkedStandardBindingService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PriceLinkedStandardBindingServiceImpl implements PriceLinkedStandardBindingService {

  public static final String ACTION_CREATE_BINDING = "CREATE_BINDING";
  public static final String ACTION_CONSISTENT = "CONSISTENT";
  public static final String ACTION_CONFLICT = "CONFLICT";
  public static final String ACTION_FAILED = "FAILED";

  private final PriceVariableBindingMapper bindingMapper;

  public PriceLinkedStandardBindingServiceImpl(PriceVariableBindingMapper bindingMapper) {
    this.bindingMapper = bindingMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public List<StandardBindingDecision> checkAgainstPreviousRule(
      StandardBindingCheckRequest request) {
    if (request == null) {
      return List.of(failed(null, null, "标准关系校验请求为空"));
    }
    if (!Boolean.TRUE.equals(request.getFormulaAvailable()) || request.getCandidates().isEmpty()) {
      return List.of(
          failed(
              normalize(request.getMaterialCode()), null, "单价列无公式或公式未识别成功，请重新导入带公式的 Excel 或人工配置"));
    }
    return request.getCandidates().stream().map(candidate -> decide(request, candidate)).toList();
  }

  private StandardBindingDecision decide(
      StandardBindingCheckRequest request, BindingCandidate candidate) {
    if (candidate == null
        || !StringUtils.hasText(candidate.getTokenName())
        || candidate.getFactorIdentityId() == null) {
      return failed(
          materialCode(request, candidate),
          candidate == null ? null : candidate.getTokenName(),
          "绑定候选缺少 tokenName 或 factorIdentityId");
    }

    String businessUnitType = normalize(request.getBusinessUnitType());
    if (!StringUtils.hasText(businessUnitType)) {
      return failed(
          materialCode(request, candidate),
          candidate.getTokenName(),
          "businessUnitType 必填，不能校验标准关系");
    }
    String materialCode = materialCode(request, candidate);
    if (!StringUtils.hasText(materialCode)) {
      return failed(null, candidate.getTokenName(), "materialCode 必填，不能校验标准关系");
    }
    if (request.getLinkedItemId() == null
        || !StringUtils.hasText(request.getOrganizationCode())
        || !StringUtils.hasText(request.getPricingMonth()))
      return failed(materialCode, candidate.getTokenName(), "缺少当前规则、组织或月份，不能校验历史绑定");
    Long previousIdentity =
        bindingMapper.findPreviousFactorIdentity(request, candidate.getTokenName());
    if (previousIdentity == null)
      return decision(
          candidate,
          materialCode,
          ACTION_CREATE_BINDING,
          null,
          null,
          candidate.getFactorIdentityId(),
          "首次识别到因素，写入本条规则绑定");
    if (candidate.getFactorIdentityId().equals(previousIdentity))
      return decision(
          candidate,
          materialCode,
          ACTION_CONSISTENT,
          null,
          previousIdentity,
          candidate.getFactorIdentityId(),
          "与之前规则的实际绑定一致");
    return decision(
        candidate,
        materialCode,
        ACTION_CONFLICT,
        null,
        previousIdentity,
        candidate.getFactorIdentityId(),
        "与之前规则的实际绑定不一致，请人工确认");
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

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
  }
}
