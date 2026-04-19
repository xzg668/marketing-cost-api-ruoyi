package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.PriceVariable;
import com.sanhua.marketingcost.mapper.PriceVariableMapper;
import com.sanhua.marketingcost.service.PriceVariableService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PriceVariableServiceImpl implements PriceVariableService {
  private final PriceVariableMapper priceVariableMapper;

  public PriceVariableServiceImpl(PriceVariableMapper priceVariableMapper) {
    this.priceVariableMapper = priceVariableMapper;
  }

  @Override
  public List<PriceVariable> list(String status) {
    var query = Wrappers.lambdaQuery(PriceVariable.class);
    if (StringUtils.hasText(status)) {
      query.eq(PriceVariable::getStatus, status.trim());
    }
    query.orderByAsc(PriceVariable::getId);
    return priceVariableMapper.selectList(query);
  }
}
