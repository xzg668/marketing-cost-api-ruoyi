package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.costruntrace.CostRunTraceDetailDto;
import com.sanhua.marketingcost.dto.costruntrace.CostRunTraceListItemDto;
import com.sanhua.marketingcost.dto.costruntrace.CostRunTraceListResponse;
import com.sanhua.marketingcost.entity.CostRunTraceSnapshot;
import com.sanhua.marketingcost.mapper.CostRunTraceSnapshotMapper;
import com.sanhua.marketingcost.service.CostRunTraceQueryService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CostRunTraceQueryServiceImpl implements CostRunTraceQueryService {

  private final CostRunTraceSnapshotMapper traceSnapshotMapper;

  public CostRunTraceQueryServiceImpl(CostRunTraceSnapshotMapper traceSnapshotMapper) {
    this.traceSnapshotMapper = traceSnapshotMapper;
  }

  @Override
  @Transactional(readOnly = true)
  public CostRunTraceListResponse listByCostRunNo(String costRunNo) {
    String runNo = requireCostRunNo(costRunNo);
    List<CostRunTraceSnapshot> snapshots =
        traceSnapshotMapper.selectList(
            Wrappers.lambdaQuery(CostRunTraceSnapshot.class)
                .eq(CostRunTraceSnapshot::getCostRunNo, runNo)
                .orderByAsc(CostRunTraceSnapshot::getTraceType)
                .orderByAsc(CostRunTraceSnapshot::getId));
    CostRunTraceListResponse response = new CostRunTraceListResponse();
    response.setCostRunNo(runNo);
    response.setTotal(snapshots.size());
    if (!snapshots.isEmpty()) {
      fillHeader(response, snapshots.get(0));
    }
    response.setRecords(snapshots.stream().map(this::toListItem).toList());
    return response;
  }

  @Override
  @Transactional(readOnly = true)
  public CostRunTraceDetailDto getByCostRunNoAndId(String costRunNo, Long traceId) {
    String runNo = requireCostRunNo(costRunNo);
    if (traceId == null || traceId <= 0) {
      return null;
    }
    List<CostRunTraceSnapshot> snapshots =
        traceSnapshotMapper.selectList(
            Wrappers.lambdaQuery(CostRunTraceSnapshot.class)
                .eq(CostRunTraceSnapshot::getCostRunNo, runNo)
                .eq(CostRunTraceSnapshot::getId, traceId)
                .last("LIMIT 1"));
    if (snapshots.isEmpty()) {
      return null;
    }
    return toDetail(snapshots.get(0));
  }

  private String requireCostRunNo(String costRunNo) {
    if (!StringUtils.hasText(costRunNo)) {
      throw new IllegalArgumentException("costRunNo is required");
    }
    return costRunNo.trim();
  }

  private void fillHeader(CostRunTraceListResponse response, CostRunTraceSnapshot snapshot) {
    response.setCostRunVersionId(snapshot.getCostRunVersionId());
    response.setVersionNo(snapshot.getVersionNo());
    response.setOaNo(snapshot.getOaNo());
    response.setOaFormItemId(snapshot.getOaFormItemId());
    response.setProductCode(snapshot.getProductCode());
    response.setPricingMonth(snapshot.getPricingMonth());
  }

  private CostRunTraceListItemDto toListItem(CostRunTraceSnapshot snapshot) {
    CostRunTraceListItemDto dto = new CostRunTraceListItemDto();
    dto.setId(snapshot.getId());
    dto.setCostRunVersionId(snapshot.getCostRunVersionId());
    dto.setCostRunNo(snapshot.getCostRunNo());
    dto.setVersionNo(snapshot.getVersionNo());
    dto.setOaNo(snapshot.getOaNo());
    dto.setOaFormItemId(snapshot.getOaFormItemId());
    dto.setProductCode(snapshot.getProductCode());
    dto.setPricingMonth(snapshot.getPricingMonth());
    dto.setTraceType(snapshot.getTraceType());
    dto.setTraceKey(snapshot.getTraceKey());
    dto.setPartItemId(snapshot.getPartItemId());
    dto.setCostItemId(snapshot.getCostItemId());
    dto.setMaterialCode(snapshot.getMaterialCode());
    dto.setMaterialName(snapshot.getMaterialName());
    dto.setCostCode(snapshot.getCostCode());
    dto.setCostName(snapshot.getCostName());
    dto.setSourceType(snapshot.getSourceType());
    dto.setSourceBatchNo(snapshot.getSourceBatchNo());
    dto.setSourceRefId(snapshot.getSourceRefId());
    dto.setUnitPrice(snapshot.getUnitPrice());
    dto.setQuantity(snapshot.getQuantity());
    dto.setBaseAmount(snapshot.getBaseAmount());
    dto.setRate(snapshot.getRate());
    dto.setAmount(snapshot.getAmount());
    dto.setSummary(snapshot.getSummary());
    dto.setBusinessUnitType(snapshot.getBusinessUnitType());
    dto.setCreatedAt(snapshot.getCreatedAt());
    dto.setUpdatedAt(snapshot.getUpdatedAt());
    return dto;
  }

  private CostRunTraceDetailDto toDetail(CostRunTraceSnapshot snapshot) {
    CostRunTraceDetailDto dto = new CostRunTraceDetailDto();
    dto.setId(snapshot.getId());
    dto.setCostRunVersionId(snapshot.getCostRunVersionId());
    dto.setCostRunNo(snapshot.getCostRunNo());
    dto.setVersionNo(snapshot.getVersionNo());
    dto.setOaNo(snapshot.getOaNo());
    dto.setOaFormItemId(snapshot.getOaFormItemId());
    dto.setProductCode(snapshot.getProductCode());
    dto.setPricingMonth(snapshot.getPricingMonth());
    dto.setTraceType(snapshot.getTraceType());
    dto.setTraceKey(snapshot.getTraceKey());
    dto.setPartItemId(snapshot.getPartItemId());
    dto.setCostItemId(snapshot.getCostItemId());
    dto.setBomRowId(snapshot.getBomRowId());
    dto.setPricePrepareItemId(snapshot.getPricePrepareItemId());
    dto.setMaterialCode(snapshot.getMaterialCode());
    dto.setMaterialName(snapshot.getMaterialName());
    dto.setCostCode(snapshot.getCostCode());
    dto.setCostName(snapshot.getCostName());
    dto.setSourceType(snapshot.getSourceType());
    dto.setSourceBatchNo(snapshot.getSourceBatchNo());
    dto.setSourceRefId(snapshot.getSourceRefId());
    dto.setUnitPrice(snapshot.getUnitPrice());
    dto.setQuantity(snapshot.getQuantity());
    dto.setBaseAmount(snapshot.getBaseAmount());
    dto.setRate(snapshot.getRate());
    dto.setAmount(snapshot.getAmount());
    dto.setSummary(snapshot.getSummary());
    dto.setSourceSnapshotJson(snapshot.getSourceSnapshotJson());
    dto.setFormulaSnapshotJson(snapshot.getFormulaSnapshotJson());
    dto.setVariablesJson(snapshot.getVariablesJson());
    dto.setStepsJson(snapshot.getStepsJson());
    dto.setChildrenJson(snapshot.getChildrenJson());
    dto.setBusinessUnitType(snapshot.getBusinessUnitType());
    dto.setCreatedAt(snapshot.getCreatedAt());
    dto.setUpdatedAt(snapshot.getUpdatedAt());
    return dto;
  }
}
