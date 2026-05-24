package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.SyncMaterialMasterRow;
import com.sanhua.marketingcost.entity.MaterialMaster;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.service.MaterialMasterSyncService.BatchSummary;
import com.sanhua.marketingcost.service.MaterialMasterSyncService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * T15：主档同步实现。Python sync_material_master.py 的 Java 版。
 *
 * <p>事务策略：{@code Propagation.REQUIRES_NEW} —— 跑一个独立子事务。即使外层试算后续步骤
 * （部品/费用）失败回滚，主档增量也已落地。这是符合直觉的：sync 是 idempotent，next 试算重跑
 * 就直接命中 ON DUPLICATE KEY UPDATE 刷字段，不需要重做。
 *
 * <p>批量大小：典型 OA 涉及 30-100 料号，1 个 SQL 全装下，不需要分批。
 */
@Service
public class MaterialMasterSyncServiceImpl implements MaterialMasterSyncService {

  private static final Logger log = LoggerFactory.getLogger(MaterialMasterSyncServiceImpl.class);

  private final BomCostingRowMapper bomCostingRowMapper;
  private final MaterialMasterRawMapper rawMapper;
  private final MaterialMasterMapper masterMapper;

  public MaterialMasterSyncServiceImpl(
      BomCostingRowMapper bomCostingRowMapper,
      MaterialMasterRawMapper rawMapper,
      MaterialMasterMapper masterMapper) {
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.rawMapper = rawMapper;
    this.masterMapper = masterMapper;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
  public SyncResult syncByOaNo(String oaNo) {
    if (!StringUtils.hasText(oaNo)) {
      throw new RuntimeException("oaNo 为空");
    }
    String oa = oaNo.trim();

    // 1) OA 涉及的去重料号
    List<String> codes = bomCostingRowMapper.selectDistinctMaterialCodesByOaNo(oa);
    if (codes == null || codes.isEmpty()) {
      log.warn("OA {} 在 lp_bom_costing_row 无 BOM 行，跳过同步", oa);
      return new SyncResult(0, 0, 0, null);
    }

    // 2) staging 最新有效批次。raw 表会保留历史批次，后续 U9 接口接入也会继续写 raw，
    //    同步主表时必须固定到一个 active batch，避免同一批同步混入新旧数据。
    String batchId = rawMapper.selectLatestActiveBatchId(null);
    if (!StringUtils.hasText(batchId)) {
      throw new RuntimeException("staging 表 lp_material_master_raw 无数据");
    }

    // 3) 拉这些料号在最新有效批次的 staging 行
    List<MaterialMasterRaw> raws = rawMapper.selectByLatestBatchAndCodes(codes, null);
    if (raws == null || raws.isEmpty()) {
      log.warn("OA {} 涉及 {} 料号，staging 命中 0 行（批次 {}）", oa, codes.size(), batchId);
      return new SyncResult(codes.size(), 0, 0, batchId);
    }

    Map<String, MaterialMaster> existingByCode = loadExistingByCode(codes);

    // 4) 类型转换 + BU 推断 → SyncMaterialMasterRow
    List<SyncMaterialMasterRow> rows = new ArrayList<>(raws.size());
    for (MaterialMasterRaw r : raws) {
      rows.add(toSyncRow(r, existingByCode.get(r.getMaterialCode())));
    }

    // 5) 批量 UPSERT
    int affected = masterMapper.upsertBatch(rows);
    log.info(
        "T15 主档同步: oa={} codes={} stagingHits={} affected={} batch={}",
        oa, codes.size(), raws.size(), affected, batchId);
    return new SyncResult(codes.size(), raws.size(), affected, batchId);
  }

  @Override
  public List<BatchSummary> listBatchSummaries() {
    return rawMapper.listBatchSummaries();
  }

  /** staging 行 → 同步行：类型转换（VARCHAR → DECIMAL/INT，失败 null）+ BU 推断 + 主表非空兜底 */
  private SyncMaterialMasterRow toSyncRow(MaterialMasterRaw r, MaterialMaster existing) {
    SyncMaterialMasterRow row = new SyncMaterialMasterRow();
    row.setMaterialCode(r.getMaterialCode());
    row.setMaterialName(text(r.getMaterialName()));
    row.setItemSpec(text(r.getMaterialSpec()));
    row.setItemModel(text(r.getMaterialModel()));
    row.setDrawingNo(text(r.getDrawingNo()));
    row.setShapeAttr(text(r.getShapeAttr()));
    row.setMaterial(text(r.getGlobalSeg4Material()));

    // 当前 20260519 文件样例显示“全局段5(净重)”更像 kg 值，不能再按旧逻辑除以 1000。
    row.setNetWeightKg(parseDecimal(r.getGlobalSeg5NetWeight()));
    row.setGrossWeightG(parseDecimal(r.getGlobalSeg9GrossWeight()));

    row.setBusinessUnitType(text(r.getProductionDivision()) == null ? null : inferBu(r.getProductionDivision()));
    row.setBizUnit(text(r.getProductionDivision()));
    row.setProductionDept(text(r.getDepartmentName()));
    row.setProductionWorkshop(text(r.getDepartmentName()));

    row.setCostElement(text(r.getCostElement()));
    row.setFinanceCategory(text(r.getFinanceCategory()));
    row.setPurchaseCategory(text(r.getPurchaseCategory()));
    row.setProductionCategory(text(r.getProductionCategory()));
    row.setSalesCategory(text(r.getSalesCategory()));
    row.setMainCategoryCode(text(r.getMainCategoryCode()));
    row.setMainCategoryName(text(r.getMainCategoryName()));

    row.setProductPropertyClass(text(r.getGlobalSeg7ProductPropertyClass()));
    row.setProductProperty(parseDecimal(r.getPrivateSeg24ProductProperty()));
    row.setLossRate(parseDecimal(r.getGlobalSeg8LossRate()));
    row.setDailyCapacity(parseDecimal(r.getPrivateSeg25DailyCapacity()));
    row.setLeadTimeDays(parseInt(r.getPrivateSeg26LeadTime()));
    row.setPackageSize(text(r.getGlobalSeg15PackageSize()));

    row.setDefaultSupplier(text(r.getDefaultSupplier()));
    row.setDefaultBuyer(text(r.getDefaultBuyer()));
    row.setDefaultPlanner(text(r.getDefaultPlanner()));

    row.setLegacyU9Code(text(r.getLegacyU9Code()));
    row.setImportBatchId(r.getImportBatchId());
    row.setSource("u9_master");
    if (existing != null) {
      applyExistingNonNull(row, existing);
    }
    return row;
  }

  private Map<String, MaterialMaster> loadExistingByCode(Collection<String> codes) {
    if (codes == null || codes.isEmpty()) {
      return Map.of();
    }
    List<MaterialMaster> masters =
        masterMapper.selectList(Wrappers.<MaterialMaster>lambdaQuery()
            .in(MaterialMaster::getMaterialCode, codes));
    return toMasterMap(masters);
  }

  private Map<String, MaterialMaster> toMasterMap(List<MaterialMaster> masters) {
    Map<String, MaterialMaster> map = new HashMap<>();
    if (masters != null) {
      for (MaterialMaster master : masters) {
        if (master != null && StringUtils.hasText(master.getMaterialCode())) {
          map.put(master.getMaterialCode(), master);
        }
      }
    }
    return map;
  }

  private void applyExistingNonNull(SyncMaterialMasterRow row, MaterialMaster existing) {
    // U9 Excel/API 可能不是每个字段都全量给值；非空覆盖用于避免空值冲掉历史可用主档。
    row.setMaterialName(coalesce(row.getMaterialName(), existing.getMaterialName()));
    row.setItemSpec(coalesce(row.getItemSpec(), existing.getItemSpec()));
    row.setItemModel(coalesce(row.getItemModel(), existing.getItemModel()));
    row.setDrawingNo(coalesce(row.getDrawingNo(), existing.getDrawingNo()));
    row.setShapeAttr(coalesce(row.getShapeAttr(), existing.getShapeAttr()));
    row.setMaterial(coalesce(row.getMaterial(), existing.getMaterial()));
    row.setNetWeightKg(coalesce(row.getNetWeightKg(), existing.getNetWeightKg()));
    row.setGrossWeightG(coalesce(row.getGrossWeightG(), existing.getGrossWeightG()));
    row.setBusinessUnitType(coalesce(row.getBusinessUnitType(), existing.getBusinessUnitType()));
    row.setBizUnit(coalesce(row.getBizUnit(), existing.getBizUnit()));
    row.setProductionDept(coalesce(row.getProductionDept(), existing.getProductionDept()));
    row.setProductionWorkshop(coalesce(row.getProductionWorkshop(), existing.getProductionWorkshop()));
    row.setCostElement(coalesce(row.getCostElement(), existing.getCostElement()));
    row.setFinanceCategory(coalesce(row.getFinanceCategory(), existing.getFinanceCategory()));
    row.setPurchaseCategory(coalesce(row.getPurchaseCategory(), existing.getPurchaseCategory()));
    row.setProductionCategory(coalesce(row.getProductionCategory(), existing.getProductionCategory()));
    row.setSalesCategory(coalesce(row.getSalesCategory(), existing.getSalesCategory()));
    row.setMainCategoryCode(coalesce(row.getMainCategoryCode(), existing.getMainCategoryCode()));
    row.setMainCategoryName(coalesce(row.getMainCategoryName(), existing.getMainCategoryName()));
    row.setProductPropertyClass(coalesce(row.getProductPropertyClass(), existing.getProductPropertyClass()));
    row.setProductProperty(coalesce(row.getProductProperty(), existing.getProductProperty()));
    row.setLossRate(coalesce(row.getLossRate(), existing.getLossRate()));
    row.setDailyCapacity(coalesce(row.getDailyCapacity(), existing.getDailyCapacity()));
    row.setLeadTimeDays(coalesce(row.getLeadTimeDays(), existing.getLeadTimeDays()));
    row.setPackageSize(coalesce(row.getPackageSize(), existing.getPackageSize()));
    row.setDefaultSupplier(coalesce(row.getDefaultSupplier(), existing.getDefaultSupplier()));
    row.setDefaultBuyer(coalesce(row.getDefaultBuyer(), existing.getDefaultBuyer()));
    row.setDefaultPlanner(coalesce(row.getDefaultPlanner(), existing.getDefaultPlanner()));
    row.setLegacyU9Code(coalesce(row.getLegacyU9Code(), existing.getLegacyU9Code()));
  }

  private static String text(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static <T> T coalesce(T value, T fallback) {
    return value != null ? value : fallback;
  }

  /** "0.05" / "5%" / "1,234.56" / null / "" / "abc" → BigDecimal or null */
  static BigDecimal parseDecimal(String value) {
    if (!StringUtils.hasText(value)) return null;
    String s = value.trim().replace(",", "");
    if (s.endsWith("%")) s = s.substring(0, s.length() - 1);
    try {
      return new BigDecimal(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** "30" / "30.0" → 30；非数字 / null → null */
  static Integer parseInt(String value) {
    BigDecimal d = parseDecimal(value);
    return d == null ? null : d.intValue();
  }

  /** production_division 含"家用"→HOUSEHOLD，其他默认 COMMERCIAL */
  static String inferBu(String productionDivision) {
    if (!StringUtils.hasText(productionDivision)) return "COMMERCIAL";
    return productionDivision.contains("家用") ? "HOUSEHOLD" : "COMMERCIAL";
  }
}
