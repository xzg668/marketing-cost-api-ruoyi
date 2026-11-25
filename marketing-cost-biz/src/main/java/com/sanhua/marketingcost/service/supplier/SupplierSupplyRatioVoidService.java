package com.sanhua.marketingcost.service.supplier;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioImportBatchMapper;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataPendingState;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

/** Discard the selected import's current versions. Historical versions are never reactivated. */
@Service
public class SupplierSupplyRatioVoidService {
  private final SupplierSupplyRatioMapper ratios;
  private final SupplierSupplyRatioImportBatchMapper batches;
  private final BusinessChangeLogMapper changes;
  private final SupplierSupplyRatioImportLock lock;
  private final TechnicalDataPendingState pending;
  private final ApplicationEventPublisher events;
  private final ObjectMapper json;
  @org.springframework.beans.factory.annotation.Autowired
  private JdbcTemplate jdbc;

  public SupplierSupplyRatioVoidService(SupplierSupplyRatioMapper ratios,
      SupplierSupplyRatioImportBatchMapper batches, BusinessChangeLogMapper changes,
      SupplierSupplyRatioImportLock lock, TechnicalDataPendingState pending,
      ApplicationEventPublisher events, ObjectMapper json) {
    this.ratios = ratios;
    this.batches = batches;
    this.changes = changes;
    this.lock = lock;
    this.pending = pending;
    this.events = events;
    this.json = json.copy().findAndRegisterModules();
  }

  public record Page(long total, List<SupplierSupplyRatioImportBatchMapper.Batch> records) {}
  public record Result(int voidedRows) {}
  public record Voided(String businessUnitType, Set<String> materialCodes) {}
  public record Group(String materialCode,String materialName,String specModel) {}

  @Transactional(readOnly=true)
  public List<Group> groups(String businessUnitType,String materialCode,String model) {
    String unit=SupplierSupplyRatioScope.require(businessUnitType);
    String code=materialCode==null?"":materialCode.strip();
    String spec=model==null?"":model.strip();
    if (code.isBlank() && spec.isBlank()) throw new IllegalArgumentException("请填写料号或型号");
    var args=new ArrayList<Object>(List.of(unit));
    String sql="""
        SELECT material_code,MAX(material_name),MAX(spec_model)
        FROM lp_supplier_supply_ratio WHERE business_unit_type=? AND is_active=1 AND deleted=0
        """;
    if(!code.isBlank()) {sql+=" AND material_code=?";args.add(code);}
    if(!spec.isBlank()) {
      sql+=" AND spec_model LIKE ? ESCAPE '!'";
      args.add("%"+spec.replace("!","!!").replace("%","!%").replace("_","!_")+"%");
    }
    var results=jdbc.query(sql+" GROUP BY material_code ORDER BY material_code LIMIT 101",
        (row,index)->new Group(row.getString(1),row.getString(2),row.getString(3)),args.toArray());
    if(results.size()>100) throw new IllegalArgumentException("匹配到较多料号，请缩小型号范围或输入完整料号");
    return results;
  }

  @Transactional(readOnly=true)
  public List<SupplierSupplyRatio> group(String businessUnitType,String materialCode) {
    return groupRows(SupplierSupplyRatioScope.require(businessUnitType),materialCode,false);
  }

  @Transactional
  public Result voidGroup(String businessUnitType,String materialCode,List<Long> expectedIds,String actor) {
    String unit=SupplierSupplyRatioScope.require(businessUnitType);
    lock.acquire(unit);
    var rows=groupRows(unit,materialCode,true);
    if(rows.isEmpty() || expectedIds==null || !rows.stream().map(SupplierSupplyRatio::getId).toList().equals(expectedIds))
      throw new IllegalArgumentException("该料号当前供货比率已变化，请重新核对后失效");
    return retire(unit,rows,actor,"整组失效，不再参与取价");
  }

  private List<SupplierSupplyRatio> groupRows(String unit,String materialCode,boolean locking) {
    if(materialCode==null || materialCode.isBlank() || materialCode.length()>64)
      throw new IllegalArgumentException("请选择完整料号");
    var query=Wrappers.lambdaQuery(SupplierSupplyRatio.class)
        .eq(SupplierSupplyRatio::getBusinessUnitType,unit)
        .eq(SupplierSupplyRatio::getMaterialCode,materialCode.strip())
        .eq(SupplierSupplyRatio::getIsActive,1).eq(SupplierSupplyRatio::getDeleted,0)
        .orderByAsc(SupplierSupplyRatio::getId);
    if(locking) query.last("FOR UPDATE");
    return ratios.selectList(query);
  }

  @Transactional(readOnly = true)
  public Page list(String businessUnitType, int page, int pageSize) {
    String unit = SupplierSupplyRatioScope.require(businessUnitType);
    if (page < 1 || pageSize < 1 || pageSize > 200)
      throw new IllegalArgumentException("导入记录分页参数无效");
    return new Page(batches.count(unit), batches.list(unit, pageSize, (long) (page - 1) * pageSize));
  }

  @Transactional
  public Result voidBatch(String businessUnitType, String batchNo, int expectedActiveRows, String actor) {
    String unit = SupplierSupplyRatioScope.require(businessUnitType);
    if (batchNo == null || batchNo.isBlank() || batchNo.trim().length() > 64)
      throw new IllegalArgumentException("请选择供货比率导入记录");
    lock.acquire(unit);
    var rows = ratios.selectList(Wrappers.lambdaQuery(SupplierSupplyRatio.class)
        .eq(SupplierSupplyRatio::getBusinessUnitType, unit)
        .eq(SupplierSupplyRatio::getSourceType, "EXCEL")
        .eq(SupplierSupplyRatio::getSourceBatchNo, batchNo.trim())
        .eq(SupplierSupplyRatio::getIsActive, 1).eq(SupplierSupplyRatio::getDeleted, 0)
        .orderByAsc(SupplierSupplyRatio::getId).last("FOR UPDATE"));
    if (rows.isEmpty()) throw new IllegalArgumentException("该批次已无生效数据，请刷新导入记录");
    if (expectedActiveRows != rows.size())
      throw new IllegalArgumentException("该批次生效条数已变化，请刷新导入记录后重新作废");

    return retire(unit,rows,actor,"作废本次导入，不再参与取价");
  }

  private Result retire(String unit,List<SupplierSupplyRatio> rows,String actor,String reason) {
    LocalDateTime now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var materials = new TreeSet<String>();
    for (var row : rows) {
      if (ratios.retireVersion(unit, row.getId(), actor, now) != 1)
        throw new IllegalArgumentException("供货比率已变化，请刷新导入记录后重新作废");
      var discarded = new SupplierSupplyRatio();
      BeanUtils.copyProperties(row, discarded);
      discarded.setIsActive(0);
      discarded.setUpdatedBy(actor);
      discarded.setUpdatedAt(now);
      var log = SupplierSupplyRatioChanges.version(row, discarded, actor, now,
          "FINANCE_VOID", reason, json);
      log.setRequestId(row.getSourceBatchNo());
      log.setIdempotencyKey("SUPPLIER_RATIO_VOID:" + row.getId());
      if (changes.insert(log) != 1) throw new IllegalStateException("供货比率作废记录保存失败");
      materials.add(row.getMaterialCode());
    }
    var codes = new ArrayList<>(materials);
    for (int start = 0; start < codes.size(); start += 256)
      pending.changed(new TechnicalDataPendingState.Change(Set.of("PRICE"), unit,
          Set.copyOf(codes.subList(start, Math.min(start + 256, codes.size()))), Set.of()));
    events.publishEvent(new Voided(unit, Set.copyOf(materials)));
    return new Result(rows.size());
  }
}
