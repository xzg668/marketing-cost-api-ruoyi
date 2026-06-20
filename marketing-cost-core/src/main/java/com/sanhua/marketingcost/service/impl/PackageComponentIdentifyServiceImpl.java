package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.PackageComponentIdentifyService;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 默认包装组件父料号识别实现。 */
@Service
public class PackageComponentIdentifyServiceImpl implements PackageComponentIdentifyService {

  private static final String PACKAGE_MAIN_CATEGORY_PREFIX = "15155";
  private static final String VIRTUAL_SHAPE_ATTR = "虚拟";

  private final MaterialMasterRawMapper materialMasterRawMapper;

  public PackageComponentIdentifyServiceImpl(MaterialMasterRawMapper materialMasterRawMapper) {
    this.materialMasterRawMapper = materialMasterRawMapper;
  }

  @Override
  public boolean isPackageComponent(String materialCode) {
    String code = trimToNull(materialCode);
    if (code == null) {
      return false;
    }
    return Boolean.TRUE.equals(batchIdentify(List.of(code)).get(code));
  }

  @Override
  public Map<String, Boolean> batchIdentify(Collection<String> materialCodes) {
    return batchIdentify(materialCodes, MaterialOrganization.COMMERCIAL.getCode());
  }

  @Override
  public Map<String, Boolean> batchIdentify(Collection<String> materialCodes, String organizationCode) {
    Map<String, Boolean> result = new LinkedHashMap<>();
    Set<String> codes = new LinkedHashSet<>();
    if (materialCodes != null) {
      for (String materialCode : materialCodes) {
        String code = trimToNull(materialCode);
        if (code != null) {
          result.putIfAbsent(code, false);
          codes.add(code);
        }
      }
    }
    if (codes.isEmpty()) {
      return result;
    }

    List<MaterialMasterRaw> rows =
        selectRawRows(codes, organizationCode);
    if (rows == null || rows.isEmpty()) {
      return result;
    }
    for (MaterialMasterRaw row : rows) {
      if (row == null) {
        continue;
      }
      String code = trimToNull(row.getMaterialCode());
      if (code == null || !result.containsKey(code)) {
        continue;
      }
      if (matchesPackageComponentRule(row)) {
        result.put(code, true);
      }
    }
    return result;
  }

  private List<MaterialMasterRaw> selectRawRows(Set<String> codes, String organizationCode) {
    String organization = MaterialOrganization.normalize(organizationCode);
    if (MaterialOrganization.COMMERCIAL.getCode().equals(organization)) {
      return materialMasterRawMapper.selectByLatestBatchAndCodes(codes, null);
    }
    return materialMasterRawMapper.selectByLatestBatchAndCodes(codes, null, organization);
  }

  private boolean matchesPackageComponentRule(MaterialMasterRaw row) {
    String mainCategoryCode = trimToNull(row.getMainCategoryCode());
    String shapeAttr = trimToNull(row.getShapeAttr());
    return mainCategoryCode != null
        && mainCategoryCode.startsWith(PACKAGE_MAIN_CATEGORY_PREFIX)
        && VIRTUAL_SHAPE_ATTR.equals(shapeAttr);
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
