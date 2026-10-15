package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.dto.CmsEffectiveSourceGenerateResponse;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.entity.CmsCostSourceEffectiveLog;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostSourceEffectiveMapper;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 一次事务内的默认来源同步：保留已有身份，记录业务变化，最后撤销未再选中的默认项。 */
final class CmsDefaultSourceSync {
  private final CmsCostSourceEffectiveMapper effectiveMapper;
  private final CmsCostSourceEffectiveLogMapper logMapper;
  private final String operator;
  private final Map<Long, CmsCostSourceEffective> pendingDefaults = new LinkedHashMap<>();

  CmsDefaultSourceSync(
      CmsCostSourceEffectiveMapper effectiveMapper,
      CmsCostSourceEffectiveLogMapper logMapper,
      int costYear,
      String businessUnitType,
      List<String> sourceTypes,
      String operator) {
    this.effectiveMapper = effectiveMapper;
    this.logMapper = logMapper;
    this.operator = operator;
    // 不载入可能很长的 source_row_ids；撤销审计只需业务身份和原有期间、金额。
    List<CmsCostSourceEffective> defaults = effectiveMapper.selectList(
        new QueryWrapper<CmsCostSourceEffective>()
            .select("id", "cost_year", "business_unit_type", "source_type", "parent_code",
                "period", "subject_code", "subject_name", "amount_yuan")
            .eq("cost_year", costYear)
            .eq("business_unit_type", businessUnitType)
            .eq("default_flag", 1)
            .in("source_type", sourceTypes));
    for (CmsCostSourceEffective source : defaults) {
      pendingDefaults.put(source.getId(), source);
    }
  }

  void recordSelection(
      CmsCostSourceEffective previous,
      CmsCostSourceEffective current,
      CmsEffectiveSourceGenerateResponse response) {
    pendingDefaults.remove(current.getId());
    if (previous != null && sameBusinessValues(previous, current)) {
      response.setSkippedCount(response.getSkippedCount() + 1);
      return;
    }
    if (previous == null) {
      response.setInsertedCount(response.getInsertedCount() + 1);
    } else {
      response.setUpdatedCount(response.getUpdatedCount() + 1);
    }
    insertLog(previous, current, "DEFAULT", current.getRefreshReason());
  }

  void removeMissingDefaults(CmsEffectiveSourceGenerateResponse response) {
    for (CmsCostSourceEffective previous : pendingDefaults.values()) {
      // 限定 default_flag，避免撤销已由人工指定的来源。
      int removed = effectiveMapper.delete(new QueryWrapper<CmsCostSourceEffective>()
          .eq("id", previous.getId()).eq("default_flag", 1));
      if (removed > 0) {
        response.setRemovedCount(response.getRemovedCount() + removed);
        insertLog(previous, null, "CANCEL", "CMS 同步后已无可生效的默认来源，撤销原默认来源");
      }
    }
  }

  private boolean sameBusinessValues(CmsCostSourceEffective previous, CmsCostSourceEffective current) {
    // 同步会重建原始表的行 ID。引用照常更新，物理行号变化不属于成本业务变更。
    return Objects.equals(previous.getPeriod(), current.getPeriod())
        && sameAmount(previous.getAmountYuan(), current.getAmountYuan())
        && Objects.equals(previous.getSubjectCode(), current.getSubjectCode())
        && Objects.equals(previous.getSubjectName(), current.getSubjectName())
        && Objects.equals(previous.getSourceTable(), current.getSourceTable());
  }

  private boolean sameAmount(BigDecimal previous, BigDecimal current) {
    if (previous == null || current == null) {
      return previous == current;
    }
    return previous.compareTo(current) == 0;
  }

  private void insertLog(
      CmsCostSourceEffective previous, CmsCostSourceEffective current, String action, String message) {
    CmsCostSourceEffective identity = current == null ? previous : current;
    CmsCostSourceEffectiveLog log = new CmsCostSourceEffectiveLog();
    log.setEffectiveSourceId(identity.getId());
    log.setCostYear(identity.getCostYear());
    log.setBusinessUnitType(identity.getBusinessUnitType());
    log.setSourceType(identity.getSourceType());
    log.setParentCode(identity.getParentCode());
    log.setSubjectCode(identity.getSubjectCode());
    log.setSubjectName(identity.getSubjectName());
    log.setOldPeriod(previous == null ? null : previous.getPeriod());
    log.setNewPeriod(current == null ? null : current.getPeriod());
    log.setOldAmountYuan(previous == null ? null : previous.getAmountYuan());
    log.setNewAmountYuan(current == null ? null : current.getAmountYuan());
    log.setActionType(action);
    log.setMessage(message);
    log.setOperator(operator);
    logMapper.insert(log);
  }
}
