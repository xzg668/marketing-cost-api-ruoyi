package com.sanhua.marketingcost.service.rule;

import java.util.Map;
import java.util.Set;

/** 按 BOM 子件料号和料品组织，从 U9 原始料品档案解析结算规则所需属性。 */
@FunctionalInterface
public interface BomRuleMaterialAttributeResolver {

  Map<String, BomRuleMaterialAttributes> resolve(
      Set<String> materialCodes, String materialOrganizationCode);
}
