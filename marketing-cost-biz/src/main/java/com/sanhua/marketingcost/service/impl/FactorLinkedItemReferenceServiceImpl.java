package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.FactorLinkedItemReferenceDto;
import com.sanhua.marketingcost.mapper.PriceVariableBindingMapper;
import com.sanhua.marketingcost.service.FactorLinkedItemReferenceService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FactorLinkedItemReferenceServiceImpl implements FactorLinkedItemReferenceService {

  private final PriceVariableBindingMapper bindingMapper;

  public FactorLinkedItemReferenceServiceImpl(PriceVariableBindingMapper bindingMapper) {
    this.bindingMapper = bindingMapper;
  }

  @Override
  public List<FactorLinkedItemReferenceDto> listLinkedItems(
      Long factorIdentityId, String pricingMonth, String businessUnitType) {
    if (factorIdentityId == null) {
      throw new IllegalArgumentException("factorIdentityId 必填");
    }
    // 反查入口跟影响因素表筛选保持一致：月份和业务单元为空时才看全量。
    return bindingMapper.findLinkedItemReferencesByFactorIdentity(
        factorIdentityId, clean(pricingMonth), clean(businessUnitType));
  }

  private String clean(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
