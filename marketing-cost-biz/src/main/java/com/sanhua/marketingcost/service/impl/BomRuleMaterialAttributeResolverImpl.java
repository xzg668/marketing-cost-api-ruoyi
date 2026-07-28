package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.rule.BomRuleMaterialAttributeResolver;
import com.sanhua.marketingcost.service.rule.BomRuleMaterialAttributes;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 规则属性直接读取 U9 原始料品档案 lp_material_master_raw 的当前有效组织行。 */
@Service
public class BomRuleMaterialAttributeResolverImpl
    implements BomRuleMaterialAttributeResolver {

  private static final Logger log =
      LoggerFactory.getLogger(BomRuleMaterialAttributeResolverImpl.class);

  private final MaterialMasterRawMapper materialMasterRawMapper;

  public BomRuleMaterialAttributeResolverImpl(MaterialMasterRawMapper materialMasterRawMapper) {
    this.materialMasterRawMapper = materialMasterRawMapper;
  }

  @Override
  public Map<String, BomRuleMaterialAttributes> resolve(
      Set<String> materialCodes, String materialOrganizationCode) {
    Set<String> normalizedCodes = new LinkedHashSet<>();
    for (String materialCode : materialCodes == null ? Set.<String>of() : materialCodes) {
      String normalized = trimToNull(materialCode);
      if (normalized != null) {
        normalizedCodes.add(normalized);
      }
    }
    if (normalizedCodes.isEmpty()) {
      return Map.of();
    }

    String organizationCode = MaterialOrganization.normalize(materialOrganizationCode);
    List<MaterialMasterRaw> masters =
        materialMasterRawMapper.selectByLatestBatchAndCodes(
            normalizedCodes, null, organizationCode);
    Map<String, BomRuleMaterialAttributes> result = new LinkedHashMap<>();
    for (MaterialMasterRaw master : masters == null ? List.<MaterialMasterRaw>of() : masters) {
      if (master == null) {
        continue;
      }
      String materialCode = trimToNull(master.getMaterialCode());
      if (materialCode != null) {
        result.put(
            materialCode,
            new BomRuleMaterialAttributes(
                trimToNull(master.getMainCategoryCode()),
                trimToNull(master.getPurchaseCategory())));
      }
    }
    Set<String> missingCodes = new LinkedHashSet<>(normalizedCodes);
    missingCodes.removeAll(result.keySet());
    if (!missingCodes.isEmpty()) {
      log.warn(
          "BOM辅料排除未查到 U9 原始料品档案当前有效组织行，按默认保留处理: organization={} count={} sample={}",
          organizationCode,
          missingCodes.size(),
          missingCodes.stream().limit(10).toList());
    }
    List<String> unclassifiedCodes = result.entrySet().stream()
        .filter(entry -> entry.getValue().mainCategoryCode() == null)
        .map(Map.Entry::getKey)
        .limit(10)
        .toList();
    if (!unclassifiedCodes.isEmpty()) {
      log.warn(
          "BOM辅料排除 U9 原始料品主分类为空，按默认保留处理: organization={} sample={}",
          organizationCode,
          unclassifiedCodes);
    }
    return Map.copyOf(result);
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
