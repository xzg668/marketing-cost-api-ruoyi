package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.PriceLinkedType2MergedRow;
import java.math.BigDecimal;
import java.util.Optional;

/** 从系统统一变量注册表动态读取类型 2 对账所需的 vat_rate。 */
@FunctionalInterface
public interface PriceLinkedType2VatRateResolver {

  Optional<BigDecimal> resolve(PriceLinkedType2MergedRow mergedRow);
}
