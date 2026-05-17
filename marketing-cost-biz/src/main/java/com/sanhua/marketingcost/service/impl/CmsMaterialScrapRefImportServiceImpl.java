package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CmsCostExcelParseError;
import com.sanhua.marketingcost.dto.CmsCostExcelParseResult;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefImportRequest;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefImportResponse;
import com.sanhua.marketingcost.dto.CmsMaterialScrapRefSourceRow;
import com.sanhua.marketingcost.entity.MaterialScrapRef;
import com.sanhua.marketingcost.mapper.MaterialScrapRefMapper;
import com.sanhua.marketingcost.service.CmsCostExcelParseService;
import com.sanhua.marketingcost.service.CmsMaterialScrapRefImportService;
import com.sanhua.marketingcost.util.CmsFieldNormalizeUtils;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CmsMaterialScrapRefImportServiceImpl implements CmsMaterialScrapRefImportService {
  private static final String STATUS_IMPORTED = "IMPORTED";
  private static final String SOURCE_TYPE_CURRENT_EXCEL = "CMS_EXCEL";
  private static final String DEFAULT_BUSINESS_UNIT = "COMMERCIAL";

  private final MaterialScrapRefMapper materialScrapRefMapper;
  private final CmsCostExcelParseService excelParseService;

  public CmsMaterialScrapRefImportServiceImpl(
      MaterialScrapRefMapper materialScrapRefMapper,
      CmsCostExcelParseService excelParseService) {
    this.materialScrapRefMapper = materialScrapRefMapper;
    this.excelParseService = excelParseService;
  }

  @Override
  @Transactional
  public CmsMaterialScrapRefImportResponse importExcel(InputStream input, String businessUnitType) {
    CmsCostExcelParseResult<CmsMaterialScrapRefSourceRow> parseResult =
        excelParseService.parseMaterialScrapRef(input);
    validateParseResult(parseResult);

    CmsMaterialScrapRefImportRequest request = new CmsMaterialScrapRefImportRequest();
    request.setBusinessUnitType(businessUnitType);
    request.setSourceRows(parseResult.getRows());
    return importSourceRows(request);
  }

  @Override
  @Transactional
  public CmsMaterialScrapRefImportResponse importSourceRows(CmsMaterialScrapRefImportRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("CMS 原材料对应回收废料导入请求不能为空");
    }
    String businessUnitType = normalizeBusinessUnit(request.getBusinessUnitType());
    List<CmsMaterialScrapRefSourceRow> sourceRows =
        request.getSourceRows() == null ? List.of() : request.getSourceRows();
    List<Candidate> candidates = toCandidates(sourceRows);
    Map<MappingKey, Candidate> selected = selectCurrentCandidates(candidates);

    for (Candidate candidate : selected.values()) {
      upsertCurrentMapping(candidate, businessUnitType);
    }

    return buildResponse(sourceRows, candidates, selected.values(), STATUS_IMPORTED);
  }

  private List<Candidate> toCandidates(List<CmsMaterialScrapRefSourceRow> sourceRows) {
    List<Candidate> candidates = new ArrayList<>();
    for (CmsMaterialScrapRefSourceRow row : sourceRows) {
      String materialCode = normalizedMaterialCode(row);
      String scrapCode = normalizedScrapCode(row);
      if (CmsFieldNormalizeUtils.isCurrentMappingCandidate(
          materialCode, scrapCode, row.getSequenceStatus())) {
        candidates.add(new Candidate(row, materialCode, scrapCode));
      }
    }
    return candidates;
  }

  private Map<MappingKey, Candidate> selectCurrentCandidates(List<Candidate> candidates) {
    Map<MappingKey, Candidate> selected = new LinkedHashMap<>();
    for (Candidate candidate : candidates) {
      MappingKey key = new MappingKey(candidate.materialCode(), candidate.scrapCode());
      selected.merge(
          key,
          candidate,
          (left, right) -> currentCandidateComparator().compare(left, right) >= 0 ? left : right);
    }
    return selected;
  }

  private Comparator<Candidate> currentCandidateComparator() {
    return Comparator.comparingInt((Candidate candidate) -> sequenceStatusRank(candidate.sourceRow()))
        .thenComparing(candidate -> versionNumber(candidate.sourceRow().getCmsVersion()), nullsLow())
        .thenComparing(candidate -> normalizedVersion(candidate.sourceRow().getCmsVersion()), nullsLow())
        .thenComparing(candidate -> candidate.sourceRow().getApprovalTime(), nullsLow())
        .thenComparing(candidate -> candidate.sourceRow().getSyncTime(), nullsLow())
        .thenComparingInt(candidate -> Objects.requireNonNullElse(candidate.sourceRow().getRowNo(), 0));
  }

  private int sequenceStatusRank(CmsMaterialScrapRefSourceRow row) {
    String status = CmsFieldNormalizeUtils.normalize(row.getSequenceStatus());
    if ("已完成".equals(status)) {
      return 2;
    }
    return StringUtils.hasText(status) ? 0 : 1;
  }

  private BigDecimal versionNumber(String version) {
    String normalized = normalizedVersion(version);
    if (!StringUtils.hasText(normalized)) {
      return null;
    }
    try {
      return new BigDecimal(normalized);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String normalizedVersion(String version) {
    return CmsFieldNormalizeUtils.normalizeToNull(version);
  }

  private <T extends Comparable<? super T>> Comparator<T> nullsLow() {
    return Comparator.nullsFirst(Comparator.naturalOrder());
  }

  private void upsertCurrentMapping(Candidate candidate, String businessUnitType) {
    MaterialScrapRef existing =
        materialScrapRefMapper.selectOne(
            Wrappers.lambdaQuery(MaterialScrapRef.class)
                .eq(MaterialScrapRef::getBusinessUnitType, businessUnitType)
                .eq(MaterialScrapRef::getMaterialCode, candidate.materialCode())
                .eq(MaterialScrapRef::getScrapCode, candidate.scrapCode())
                .last("LIMIT 1"));
    MaterialScrapRef current = existing == null ? new MaterialScrapRef() : existing;

    applyCurrentFields(current, candidate, businessUnitType);
    if (existing == null) {
      materialScrapRefMapper.insert(current);
    } else {
      materialScrapRefMapper.updateById(current);
    }
  }

  private void applyCurrentFields(MaterialScrapRef current, Candidate candidate, String businessUnitType) {
    CmsMaterialScrapRefSourceRow row = candidate.sourceRow();
    current.setMaterialCode(candidate.materialCode());
    current.setMaterialName(row.getMaterialName());
    current.setMaterialSpec(row.getMaterialSpec());
    current.setMaterialUnit(row.getMaterialUnit());
    current.setScrapCode(candidate.scrapCode());
    current.setScrapName(row.getRecycleMaterialName());
    current.setScrapSpec(row.getRecycleMaterialSpec());
    current.setScrapUnit(row.getRecycleMaterialUnit());
    if (current.getRatio() == null) {
      current.setRatio(BigDecimal.ONE);
    }
    current.setBusinessUnitType(businessUnitType);
    current.setSourceType(currentSourceType(row.getSourceType()));
    current.setSourceDocNo(row.getSequenceNo());
    current.setCmsRecordId(row.getCmsRecordId());
    current.setLinkDetailId(row.getLinkDetailId());
    current.setCmsPostingPeriod(row.getPostingPeriod());
    current.setCmsEffectiveDate(row.getEffectiveDate());
    current.setApprovalTime(row.getApprovalTime());
    current.setSyncTime(row.getSyncTime());
  }

  private String currentSourceType(String sourceType) {
    if (!StringUtils.hasText(sourceType) || "EXCEL".equalsIgnoreCase(sourceType.trim())) {
      return SOURCE_TYPE_CURRENT_EXCEL;
    }
    return "CMS_" + sourceType.trim().toUpperCase();
  }

  private CmsMaterialScrapRefImportResponse buildResponse(
      List<CmsMaterialScrapRefSourceRow> sourceRows,
      List<Candidate> candidates,
      Collection<Candidate> selected,
      String status) {
    CmsMaterialScrapRefImportResponse response = new CmsMaterialScrapRefImportResponse();
    response.setStatus(status);
    response.setSourceRowCount(sourceRows.size());
    response.setEffectiveRowCount(candidates.size());
    response.setSkippedRowCount(sourceRows.size() - candidates.size());
    response.setConflictRowCount(candidates.size() - selected.size());
    response.setUpdatedMappingCount(selected.size());
    return response;
  }

  private void validateParseResult(CmsCostExcelParseResult<?> parseResult) {
    if (parseResult == null || !parseResult.hasErrors()) {
      return;
    }
    List<String> messages = new ArrayList<>();
    for (CmsCostExcelParseError error : parseResult.getErrors()) {
      StringBuilder message = new StringBuilder();
      if (error.getRowNo() != null) {
        message.append("第").append(error.getRowNo()).append("行");
      }
      if (error.getColumnName() != null) {
        message.append("[").append(error.getColumnName()).append("]");
      }
      message.append(error.getMessage());
      messages.add(message.toString());
    }
    throw new IllegalArgumentException("CMS 原材料对应回收废料 Excel 解析失败: " + String.join("; ", messages));
  }

  private String normalizedMaterialCode(CmsMaterialScrapRefSourceRow row) {
    if (StringUtils.hasText(row.getNormalizedMaterialCode())) {
      return row.getNormalizedMaterialCode();
    }
    return CmsFieldNormalizeUtils.normalize(row.getMaterialCode());
  }

  private String normalizedScrapCode(CmsMaterialScrapRefSourceRow row) {
    if (StringUtils.hasText(row.getNormalizedRecycleMaterialCode())) {
      return row.getNormalizedRecycleMaterialCode();
    }
    return CmsFieldNormalizeUtils.normalize(row.getRecycleMaterialCode());
  }

  private String normalizeBusinessUnit(String businessUnitType) {
    return StringUtils.hasText(businessUnitType) ? businessUnitType.trim() : DEFAULT_BUSINESS_UNIT;
  }

  private record MappingKey(String materialCode, String scrapCode) {}

  private record Candidate(
      CmsMaterialScrapRefSourceRow sourceRow,
      String materialCode,
      String scrapCode) {}
}
