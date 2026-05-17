package com.sanhua.marketingcost.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.QuoteBasePriceDetectResult;
import com.sanhua.marketingcost.entity.FactorQuoteBaseMapping;
import com.sanhua.marketingcost.entity.QuoteBasePriceMappingRule;
import java.util.List;

public interface QuoteBasePriceMappingService {

  Page<QuoteBasePriceMappingRule> pageRules(
      String businessUnitType,
      String quoteFieldCode,
      String keyword,
      Boolean enabled,
      int page,
      int pageSize);

  List<QuoteBasePriceMappingRule> listEnabledRules(String businessUnitType, String keyword);

  QuoteBasePriceMappingRule createRule(QuoteBasePriceMappingRule rule, String operator);

  QuoteBasePriceMappingRule updateRule(Long id, QuoteBasePriceMappingRule patch, String operator);

  void setRuleEnabled(Long id, boolean enabled, String operator);

  void deleteRule(Long id);

  FactorQuoteBaseMapping saveMapping(FactorQuoteBaseMapping mapping, String operator);

  void setMappingEnabled(Long id, boolean enabled, String operator);

  void deleteMapping(Long id);

  Page<FactorQuoteBaseMapping> pageMappings(
      Long factorIdentityId,
      String quoteFieldCode,
      String keyword,
      Boolean enabled,
      int page,
      int pageSize);

  List<FactorQuoteBaseMapping> listMappingsByFactorIdentityId(Long factorIdentityId);

  QuoteBasePriceDetectResult detectAndSaveFactorQuoteBaseMapping(Long factorIdentityId);
}
