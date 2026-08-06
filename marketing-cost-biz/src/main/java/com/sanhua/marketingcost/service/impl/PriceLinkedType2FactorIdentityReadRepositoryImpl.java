package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.entity.PriceVariableBinding;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.mapper.PriceVariableBindingMapper;
import com.sanhua.marketingcost.service.PriceLinkedType2FactorIdentityReadRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

@Repository
public class PriceLinkedType2FactorIdentityReadRepositoryImpl
    implements PriceLinkedType2FactorIdentityReadRepository {

  private static final String STATUS_ACTIVE = "ACTIVE";

  private final FactorIdentityMapper factorIdentityMapper;
  private final FactorMonthlyPriceMapper factorMonthlyPriceMapper;
  private final PriceVariableBindingMapper priceVariableBindingMapper;

  public PriceLinkedType2FactorIdentityReadRepositoryImpl(
      FactorIdentityMapper factorIdentityMapper,
      FactorMonthlyPriceMapper factorMonthlyPriceMapper,
      PriceVariableBindingMapper priceVariableBindingMapper) {
    this.factorIdentityMapper = factorIdentityMapper;
    this.factorMonthlyPriceMapper = factorMonthlyPriceMapper;
    this.priceVariableBindingMapper = priceVariableBindingMapper;
  }

  @Override
  public List<FactorIdentity> findActiveIdentities(String businessUnitType) {
    return factorIdentityMapper.selectList(
        Wrappers.lambdaQuery(FactorIdentity.class)
            .eq(FactorIdentity::getBusinessUnitType, businessUnitType)
            .eq(FactorIdentity::getStatus, STATUS_ACTIVE));
  }

  @Override
  public List<FactorMonthlyPrice> findActiveMonthlyPrices(
      Collection<Long> factorIdentityIds, String priceMonth) {
    List<Long> ids = usableIds(factorIdentityIds);
    if (ids.isEmpty()) {
      return List.of();
    }
    return factorMonthlyPriceMapper.selectList(
        Wrappers.lambdaQuery(FactorMonthlyPrice.class)
            .in(FactorMonthlyPrice::getFactorIdentityId, ids)
            .eq(FactorMonthlyPrice::getPriceMonth, priceMonth)
            .eq(FactorMonthlyPrice::getStatus, STATUS_ACTIVE));
  }

  @Override
  public Map<Long, Long> countActiveLegacyBindings(
      Collection<Long> factorIdentityIds) {
    List<Long> ids = usableIds(factorIdentityIds);
    if (ids.isEmpty()) {
      return Map.of();
    }
    List<PriceVariableBinding> bindings = priceVariableBindingMapper.selectList(
        Wrappers.lambdaQuery(PriceVariableBinding.class)
            .in(PriceVariableBinding::getFactorIdentityId, ids)
            .isNull(PriceVariableBinding::getExpiryDate));
    return bindings.stream()
        .map(PriceVariableBinding::getFactorIdentityId)
        .filter(Objects::nonNull)
        .collect(Collectors.groupingBy(id -> id, Collectors.counting()));
  }

  private List<Long> usableIds(Collection<Long> factorIdentityIds) {
    if (factorIdentityIds == null) {
      return List.of();
    }
    return factorIdentityIds.stream()
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }
}
