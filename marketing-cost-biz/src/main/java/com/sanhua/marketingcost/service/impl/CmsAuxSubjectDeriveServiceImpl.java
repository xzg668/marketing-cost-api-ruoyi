package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.dto.CmsAuxSubjectDeriveResponse;
import com.sanhua.marketingcost.entity.AuxSubject;
import com.sanhua.marketingcost.entity.CmsCostDeriveLog;
import com.sanhua.marketingcost.entity.CmsCostImportBatch;
import com.sanhua.marketingcost.entity.CmsProductSubjectCostRaw;
import com.sanhua.marketingcost.mapper.AuxSubjectMapper;
import com.sanhua.marketingcost.mapper.CmsCostDeriveLogMapper;
import com.sanhua.marketingcost.mapper.CmsCostImportBatchMapper;
import com.sanhua.marketingcost.mapper.CmsProductSubjectCostRawMapper;
import com.sanhua.marketingcost.service.CmsAuxSubjectDeriveService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CmsAuxSubjectDeriveServiceImpl implements CmsAuxSubjectDeriveService {
  private static final String SOURCE_CMS = "CMS";
  private static final String FIRST_SUBJECT_AUX_MATERIAL = "辅助材料";
  private static final String EXCLUDED_AUX_SUBJECT_PACKAGING = "包装辅料";
  private static final BigDecimal CENT_TO_YUAN = new BigDecimal("100");

  private final CmsCostImportBatchMapper batchMapper;
  private final CmsProductSubjectCostRawMapper productSubjectCostRawMapper;
  private final AuxSubjectMapper auxSubjectMapper;
  private final CmsCostDeriveLogMapper deriveLogMapper;

  public CmsAuxSubjectDeriveServiceImpl(
      CmsCostImportBatchMapper batchMapper,
      CmsProductSubjectCostRawMapper productSubjectCostRawMapper,
      AuxSubjectMapper auxSubjectMapper,
      CmsCostDeriveLogMapper deriveLogMapper) {
    this.batchMapper = batchMapper;
    this.productSubjectCostRawMapper = productSubjectCostRawMapper;
    this.auxSubjectMapper = auxSubjectMapper;
    this.deriveLogMapper = deriveLogMapper;
  }

  @Override
  @Transactional
  public CmsAuxSubjectDeriveResponse deriveAuxSubjects(Long importBatchId) {
    if (importBatchId == null) {
      throw new IllegalArgumentException("importBatchId 不能为空");
    }
    CmsCostImportBatch batch = batchMapper.selectById(importBatchId);
    if (batch == null) {
      throw new IllegalArgumentException("CMS 导入批次不存在: " + importBatchId);
    }
    String businessUnitType = batch.getBusinessUnitType();
    Map<String, AuxAggregate> aggregates = aggregateAuxSubjects(importBatchId, businessUnitType);

    CmsAuxSubjectDeriveResponse response = new CmsAuxSubjectDeriveResponse();
    response.setImportBatchId(importBatchId);
    for (AuxAggregate aggregate : aggregates.values()) {
      AuxSubject existing = findExisting(aggregate.parentCode, aggregate.secondSubjectCode, businessUnitType);
      if (existing != null) {
        response.setAuxSkipCount(response.getAuxSkipCount() + 1);
        insertLog(
            importBatchId,
            aggregate,
            "SKIPPED",
            "CMS辅料科目已存在，首月锁定不覆盖",
            "lp_aux_subject",
            existing.getId(),
            businessUnitType);
        continue;
      }
      AuxSubject auxSubject = toAuxSubject(importBatchId, aggregate, businessUnitType);
      auxSubjectMapper.insert(auxSubject);
      response.setAuxInsertCount(response.getAuxInsertCount() + 1);
      insertLog(
          importBatchId,
          aggregate,
          "INSERTED",
          "CMS辅料科目新增锁定",
          "lp_aux_subject",
          auxSubject.getId(),
          businessUnitType);
    }

    batch.setAuxInsertCount(response.getAuxInsertCount());
    batch.setAuxSkipCount(response.getAuxSkipCount());
    batch.setErrorCount(response.getErrorCount());
    batch.setErrorMessage(response.getErrorMessage());
    batchMapper.updateById(batch);
    return response;
  }

  private Map<String, AuxAggregate> aggregateAuxSubjects(
      Long importBatchId,
      String businessUnitType) {
    List<CmsProductSubjectCostRaw> rows =
        productSubjectCostRawMapper.selectList(
            new QueryWrapper<CmsProductSubjectCostRaw>()
                .eq("import_batch_id", importBatchId)
                .eq("first_subject_name", FIRST_SUBJECT_AUX_MATERIAL)
                .eq(StringUtils.hasText(businessUnitType), "business_unit_type", businessUnitType));
    Map<String, List<CmsProductSubjectCostRaw>> grouped = new LinkedHashMap<>();
    for (CmsProductSubjectCostRaw row : rows) {
      if (!StringUtils.hasText(row.getParentCode())
          || !FIRST_SUBJECT_AUX_MATERIAL.equals(row.getFirstSubjectName())
          || !StringUtils.hasText(row.getSecondSubjectCode())
          || !StringUtils.hasText(row.getSecondSubjectName())
          || !StringUtils.hasText(row.getPeriod())) {
        continue;
      }
      if (EXCLUDED_AUX_SUBJECT_PACKAGING.equals(row.getSecondSubjectName())) {
        continue;
      }
      grouped
          .computeIfAbsent(groupKey(row), ignored -> new ArrayList<>())
          .add(row);
    }

    Map<String, AuxAggregate> result = new LinkedHashMap<>();
    for (Map.Entry<String, List<CmsProductSubjectCostRaw>> entry : grouped.entrySet()) {
      List<CmsProductSubjectCostRaw> parentSubjectRows = entry.getValue();
      String earliestPeriod =
          parentSubjectRows.stream()
              .map(CmsProductSubjectCostRaw::getPeriod)
              .filter(StringUtils::hasText)
              .min(String::compareTo)
              .orElse(null);
      CmsProductSubjectCostRaw firstRow = parentSubjectRows.get(0);
      AuxAggregate aggregate =
          new AuxAggregate(
              firstRow.getParentCode(),
              firstRow.getSecondSubjectCode(),
              firstRow.getSecondSubjectName(),
              earliestPeriod);
      parentSubjectRows.stream()
          .filter(row -> earliestPeriod.equals(row.getPeriod()))
          .sorted(Comparator.comparing(row -> row.getRowNo() == null ? Integer.MAX_VALUE : row.getRowNo()))
          .forEach(row -> {
            aggregate.amount = aggregate.amount.add(toYuan(row.getMaterialPrice()));
            aggregate.fillMeta(row.getParentName(), row.getParentSpec(), row.getParentType());
          });
      result.put(entry.getKey(), aggregate);
    }
    return result;
  }

  private AuxSubject findExisting(String parentCode, String auxSubjectCode, String businessUnitType) {
    QueryWrapper<AuxSubject> query =
        new QueryWrapper<AuxSubject>()
            .eq("material_code", parentCode)
            .eq("aux_subject_code", auxSubjectCode)
            .eq("source", SOURCE_CMS)
            .eq(StringUtils.hasText(businessUnitType), "business_unit_type", businessUnitType)
            .last("LIMIT 1");
    if (!StringUtils.hasText(businessUnitType)) {
      query.isNull("business_unit_type");
    }
    return auxSubjectMapper.selectOne(query);
  }

  private AuxSubject toAuxSubject(Long importBatchId, AuxAggregate aggregate, String businessUnitType) {
    AuxSubject auxSubject = new AuxSubject();
    auxSubject.setMaterialCode(aggregate.parentCode);
    auxSubject.setProductName(aggregate.productName);
    auxSubject.setSpec(aggregate.spec);
    auxSubject.setModel(aggregate.model);
    auxSubject.setAuxSubjectCode(aggregate.secondSubjectCode);
    auxSubject.setAuxSubjectName(aggregate.secondSubjectName);
    auxSubject.setUnitPrice(aggregate.amount.setScale(6, RoundingMode.HALF_UP));
    auxSubject.setPeriod(aggregate.period);
    auxSubject.setSource(SOURCE_CMS);
    auxSubject.setSourceImportBatchId(importBatchId);
    auxSubject.setLockStatus("LOCKED");
    auxSubject.setAmountCalcMode("DIRECT");
    auxSubject.setBusinessUnitType(businessUnitType);
    return auxSubject;
  }

  private void insertLog(
      Long importBatchId,
      AuxAggregate aggregate,
      String status,
      String message,
      String targetTable,
      Long targetId,
      String businessUnitType) {
    CmsCostDeriveLog log = new CmsCostDeriveLog();
    log.setImportBatchId(importBatchId);
    log.setDeriveType("AUX_SUBJECT");
    log.setParentCode(aggregate.parentCode);
    log.setSubjectCode(aggregate.secondSubjectCode);
    log.setSubjectName(aggregate.secondSubjectName);
    log.setPeriod(aggregate.period);
    log.setStatus(status);
    log.setMessage(message);
    log.setAmount(aggregate.amount.setScale(6, RoundingMode.HALF_UP));
    log.setTargetTable(targetTable);
    log.setTargetId(targetId);
    log.setBusinessUnitType(businessUnitType);
    deriveLogMapper.insert(log);
  }

  private BigDecimal toYuan(BigDecimal cent) {
    if (cent == null) {
      return BigDecimal.ZERO;
    }
    return cent.divide(CENT_TO_YUAN, 6, RoundingMode.HALF_UP);
  }

  private String groupKey(CmsProductSubjectCostRaw row) {
    return row.getParentCode() + "|" + row.getSecondSubjectCode() + "|" + row.getSecondSubjectName();
  }

  private static final class AuxAggregate {
    private final String parentCode;
    private final String secondSubjectCode;
    private final String secondSubjectName;
    private final String period;
    private BigDecimal amount = BigDecimal.ZERO;
    private String productName;
    private String spec;
    private String model;

    private AuxAggregate(
        String parentCode,
        String secondSubjectCode,
        String secondSubjectName,
        String period) {
      this.parentCode = parentCode;
      this.secondSubjectCode = secondSubjectCode;
      this.secondSubjectName = secondSubjectName;
      this.period = period;
    }

    private void fillMeta(String productName, String spec, String model) {
      if (!StringUtils.hasText(this.productName) && StringUtils.hasText(productName)) {
        this.productName = productName;
      }
      if (!StringUtils.hasText(this.spec) && StringUtils.hasText(spec)) {
        this.spec = spec;
      }
      if (!StringUtils.hasText(this.model) && StringUtils.hasText(model)) {
        this.model = model;
      }
    }
  }
}
