package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.MaterialPriceType;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.mapper.MaterialPriceTypeMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.MaterialPriceTypeRouteSyncService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MaterialPriceTypeRouteSyncServiceImpl
    implements MaterialPriceTypeRouteSyncService {

  private static final int DEFAULT_PRIORITY = 1;
  private final MaterialPriceTypeMapper mapper;

  public MaterialPriceTypeRouteSyncServiceImpl(MaterialPriceTypeMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public SyncResult sync(RouteCommand command) {
    RouteCommand normalized = normalize(command);
    MaterialPriceType current = findCurrent(normalized.materialCode(), normalized.businessUnitType());
    if (current != null
        && normalized.priceType().equals(PriceTypeEnum.normalizeRouteText(current.getPriceType()))) {
      return new SyncResult(current, false);
    }

    MaterialPriceType route = new MaterialPriceType();
    route.setMaterialCode(normalized.materialCode());
    route.setMaterialName(normalized.materialName());
    route.setMaterialSpec(normalized.materialSpec());
    route.setUnit(normalized.unit());
    route.setBusinessUnitType(normalized.businessUnitType());
    route.setPriceType(normalized.priceType());
    route.setSource(normalized.source());
    route.setSourceSystem(normalized.sourceSystem());
    route.setPriority(DEFAULT_PRIORITY);
    // 当前版本只由 created_at、id 决定；价格本身的生效期属于价格源，不复制到类型路由。
    mapper.insert(route);
    return new SyncResult(route, true);
  }

  private MaterialPriceType findCurrent(String materialCode, String businessUnitType) {
    var query = Wrappers.lambdaQuery(MaterialPriceType.class)
        .eq(MaterialPriceType::getMaterialCode, materialCode);
    if (businessUnitType == null) {
      query.and(q -> q.isNull(MaterialPriceType::getBusinessUnitType)
          .or()
          .eq(MaterialPriceType::getBusinessUnitType, ""));
    } else {
      query.eq(MaterialPriceType::getBusinessUnitType, businessUnitType);
    }
    List<MaterialPriceType> rows = mapper.selectList(
        query.orderByDesc(MaterialPriceType::getCreatedAt)
            .orderByDesc(MaterialPriceType::getId)
            .last("LIMIT 1 FOR UPDATE"));
    return rows == null || rows.isEmpty() ? null : rows.getFirst();
  }

  private RouteCommand normalize(RouteCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("价格类型同步参数不能为空");
    }
    String materialCode = trimToNull(command.materialCode());
    String priceType = PriceTypeEnum.normalizeRouteText(command.priceType());
    if (materialCode == null || priceType == null) {
      throw new IllegalArgumentException("价格类型同步缺少物料代码或价格类型");
    }
    String businessUnitType = firstText(
        command.businessUnitType(), BusinessUnitContext.getCurrentBusinessUnitType());
    return new RouteCommand(
        materialCode,
        trimToNull(command.materialName()),
        trimToNull(command.materialSpec()),
        trimToNull(command.unit()),
        businessUnitType,
        priceType,
        firstText(command.source(), "formal_price"),
        firstText(command.sourceSystem(), "FORMAL_PRICE"));
  }

  private String firstText(String... values) {
    for (String value : values) {
      String normalized = trimToNull(value);
      if (normalized != null) {
        return normalized;
      }
    }
    return null;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
