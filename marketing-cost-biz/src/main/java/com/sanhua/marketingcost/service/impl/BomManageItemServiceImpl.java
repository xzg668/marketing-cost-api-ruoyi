package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.BomManageParentRow;
import com.sanhua.marketingcost.entity.BomManageItem;
import com.sanhua.marketingcost.mapper.BomManageItemMapper;
import com.sanhua.marketingcost.service.BomManageItemService;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * BOM 管理（老表视图服务）。
 *
 * <p>读取能力保留 —— {@link #page} / {@link #listDetails} 走
 * {@link BomManageItemMapper} 的自定义 SQL，底层表已切到新表
 * {@code lp_bom_costing_row}（见 Mapper 文件注释）。
 */
@Service
public class BomManageItemServiceImpl implements BomManageItemService {

  private final BomManageItemMapper bomManageItemMapper;

  public BomManageItemServiceImpl(BomManageItemMapper bomManageItemMapper) {
    this.bomManageItemMapper = bomManageItemMapper;
  }

  @Override
  public Page<BomManageParentRow> page(
      String oaNo, String bomCode, String materialNo, String shapeAttr, int page, int pageSize) {
    int current = Math.max(page, 1);
    int size = Math.max(pageSize, 1);
    String oaNoFilter = trimToNull(oaNo);
    String bomCodeFilter = trimToNull(bomCode);
    String materialNoFilter = trimToNull(materialNo);
    String shapeAttrFilter = trimToNull(shapeAttr);
    long total = Objects.requireNonNullElse(
        bomManageItemMapper.countParentRows(
            oaNoFilter, bomCodeFilter, materialNoFilter, shapeAttrFilter),
        0L);
    Page<BomManageParentRow> pager = new Page<>(current, size);
    pager.setTotal(total);
    if (total <= 0) {
      pager.setRecords(List.of());
      return pager;
    }
    long offset = (long) (current - 1) * size;
    List<BomManageParentRow> records = bomManageItemMapper.selectParentRows(
        oaNoFilter, bomCodeFilter, materialNoFilter, shapeAttrFilter, offset, size);
    pager.setRecords(records == null ? List.of() : records);
    return pager;
  }

  @Override
  public List<BomManageItem> listDetails(
      String oaNo, Long oaFormItemId, String bomCode, String rootItemCode, String shapeAttr) {
    String oaNoFilter = trimToNull(oaNo);
    String bomCodeFilter = trimToNull(bomCode);
    String rootItemCodeFilter = trimToNull(rootItemCode);
    String shapeAttrFilter = trimToNull(shapeAttr);
    if (!StringUtils.hasText(oaNoFilter) || oaFormItemId == null
        || !StringUtils.hasText(bomCodeFilter) || !StringUtils.hasText(rootItemCodeFilter)) {
      return List.of();
    }
    List<BomManageItem> rows = bomManageItemMapper.selectDetailRows(
        oaNoFilter, oaFormItemId, bomCodeFilter, rootItemCodeFilter, shapeAttrFilter);
    return rows == null ? List.of() : rows;
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
