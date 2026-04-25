package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.BomManageParentRow;
import com.sanhua.marketingcost.dto.BomManageRefreshRequest;
import com.sanhua.marketingcost.entity.BomManageItem;
import com.sanhua.marketingcost.mapper.BomManageItemMapper;
import com.sanhua.marketingcost.service.BomManageItemService;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * BOM 管理（老表视图服务）。
 *
 * <p>T5.5：读取能力保留 —— {@link #page} / {@link #listDetails} 走
 * {@link BomManageItemMapper} 的自定义 SQL，底层表已切到新表
 * {@code lp_bom_costing_row}（见 Mapper 文件注释）。
 *
 * <p>{@link #refresh} 整段废弃：T3 导入 + T4 层级构建 + T5 拍平 三阶段流程
 * 已经取代"手工 BOM → 扁平表"的写入路径，POST /api/v1/bom-manage/refresh
 * 端点保留但仅作空操作（保留 3~6 个月回滚窗口）。
 */
@Service
public class BomManageItemServiceImpl implements BomManageItemService {

  private static final Logger log = LoggerFactory.getLogger(BomManageItemServiceImpl.class);

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

  /**
   * T5.5 下线的 no-op 入口。
   *
   * <p>老的"手工 BOM + OA → 扁平表"写入路径已被 T3 Excel 导入 + T4 层级构建 + T5 拍平
   * 三阶段流程取代；本方法保留签名和端点仅为回滚窗口内向前兼容，不做任何写库操作。
   *
   * <p>任何调用会打一条 WARN，便于监控谁还在用老端点，推动切换。
   */
  @Override
  public int refresh(BomManageRefreshRequest request) {
    String oaNo = request == null ? null : trimToNull(request.getOaNo());
    log.warn(
        "[T5.5-deprecated] /bom-manage/refresh 已下线 —— 请走 /bom/import + /bom/build-hierarchy + /bom/flatten 新三段流程 (oaNo={})",
        oaNo);
    return 0;
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
