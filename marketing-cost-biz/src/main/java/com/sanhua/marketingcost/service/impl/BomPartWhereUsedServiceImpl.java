package com.sanhua.marketingcost.service.impl;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.BomPartWhereUsedItemResponse;
import com.sanhua.marketingcost.entity.BomPartWhereUsed;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.BomPartWhereUsedMapper;
import com.sanhua.marketingcost.service.BomPartWhereUsedService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class BomPartWhereUsedServiceImpl implements BomPartWhereUsedService {
  private final BomPartWhereUsedMapper mapper;

  public BomPartWhereUsedServiceImpl(BomPartWhereUsedMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public PageResult<BomPartWhereUsedItemResponse> page(
      String organizationCode,
      String partCode,
      String topProductCode,
      int page,
      int pageSize) {
    String normalizedPartCode = trimToNull(partCode);
    if (normalizedPartCode == null) {
      return new PageResult<>(List.of(), 0L);
    }

    String priceOrgCode =
        MaterialOrganization.fromCode(organizationCode).getPriceOrgCode();
    QueryWrapper<BomPartWhereUsed> query = new QueryWrapper<>();
    query.eq("price_org_code", priceOrgCode)
        .eq("part_code", normalizedPartCode);
    if (StringUtils.hasText(topProductCode)) {
      query.likeRight("top_product_code", topProductCode.trim());
    }
    query.orderByAsc("top_product_code");

    Page<BomPartWhereUsed> result =
        mapper.selectPage(new Page<>(safePage(page), safePageSize(pageSize)), query);
    List<BomPartWhereUsedItemResponse> records =
        result.getRecords().stream().map(BomPartWhereUsedServiceImpl::toResponse).toList();
    return new PageResult<>(records, result.getTotal());
  }

  private static BomPartWhereUsedItemResponse toResponse(BomPartWhereUsed row) {
    return new BomPartWhereUsedItemResponse(
        row.getPriceOrgCode(),
        row.getPartCode(),
        row.getPartName(),
        row.getPartSpec(),
        row.getTopProductCode(),
        row.getTopProductName(),
        row.getTopBomVersion(),
        row.getBomPurpose(),
        row.getTotalQtyPerTop(),
        row.getBomPathCount(),
        row.getMinLevel(),
        row.getMaxLevel(),
        isTrue(row.getHasLeafOccurrence()),
        isTrue(row.getHasNonLeafOccurrence()),
        row.getSamplePath(),
        row.getShapeAttr(),
        row.getSourceCategory(),
        row.getCostElementCode(),
        row.getSnapshotDate());
  }

  private static boolean isTrue(Integer value) {
    return value != null && value == 1;
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static int safePage(int page) {
    return Math.max(page, 1);
  }

  private static int safePageSize(int pageSize) {
    return Math.min(Math.max(pageSize, 1), 200);
  }
}
