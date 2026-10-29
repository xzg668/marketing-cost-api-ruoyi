package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.SupplierSupplyRatioExcelRow;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioImportResponse;
import com.sanhua.marketingcost.dto.SupplierSupplyRatioWorkbookParseResult;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.entity.BusinessChangeLog;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import com.sanhua.marketingcost.service.supplier.SupplierSupplyRatioImportLock;
import com.sanhua.marketingcost.service.supplier.SupplierSupplyRatioScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import com.sanhua.marketingcost.service.SupplierSupplyRatioImportService;
import com.sanhua.marketingcost.service.SupplierSupplyRatioWorkbookParser;
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class SupplierSupplyRatioImportServiceImpl implements SupplierSupplyRatioImportService {
  private final SupplierSupplyRatioMapper mapper;
  private final SupplierSupplyRatioWorkbookParser parser;
  private final org.springframework.context.ApplicationEventPublisher events;
  private final BusinessChangeLogMapper changes;
  private final SupplierSupplyRatioImportLock lock;
  private final ObjectMapper json;

  public SupplierSupplyRatioImportServiceImpl(SupplierSupplyRatioMapper mapper,
      SupplierSupplyRatioWorkbookParser parser, org.springframework.context.ApplicationEventPublisher events,
      BusinessChangeLogMapper changes, SupplierSupplyRatioImportLock lock, ObjectMapper json) {
    this.mapper = mapper;
    this.parser = parser;
    this.events = events;
    this.changes = changes;
    this.lock = lock;
    this.json = json.copy().findAndRegisterModules();
  }

  @Override
  @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
  public SupplierSupplyRatioImportResponse importExcel(InputStream input, String sourceFileName,
      String businessUnitType, String operator, String sheetName) {
    SupplierSupplyRatioScope.require(businessUnitType);
    SupplierSupplyRatioWorkbookParseResult parsed =
        parser.parse(input, sourceFileName, sheetName);
    if (parsed.hasErrors()) {
      throw new IllegalArgumentException(parsed.getErrors().stream()
          .limit(10).map(this::errorMessage).reduce((a, b) -> a + "；" + b).orElse("导入校验失败"));
    }
    return importRows(parsed.getRows(), sourceFileName, businessUnitType, operator);
  }

  @Override
  @Transactional(rollbackFor = Exception.class, isolation = Isolation.REPEATABLE_READ)
  public SupplierSupplyRatioImportResponse importRows(List<SupplierSupplyRatioExcelRow> rows,
      String sourceFileName, String businessUnitType, String operator) {
    if (rows == null || rows.isEmpty()) {
      throw new IllegalArgumentException("没有可导入的有效数据，原数据未变更");
    }
    String bu = SupplierSupplyRatioScope.require(businessUnitType);
    bounded(sourceFileName, "文件名", 255, true);
    String username = StringUtils.hasText(operator) ? operator.trim() : "system";
    SupplierSupplyRatioImportResponse response = new SupplierSupplyRatioImportResponse();
    response.setBatchNo("SSR-EXCEL-" + UUID.randomUUID());
    response.setTotalRows(rows.size());

    // 先校验整份文件；同一供应商的冲突值不能靠文件顺序覆盖。
    Map<String, java.util.Set<String>> suppliedCodes = new java.util.HashMap<>();
    for (SupplierSupplyRatioExcelRow row : rows) {
      validate(row);
      if (StringUtils.hasText(row.getSupplierCode())) suppliedCodes
          .computeIfAbsent(normalized(row.getSupplierName()), ignored -> new java.util.HashSet<>())
          .add(normalized(row.getSupplierCode()));
    }
    Map<String, Map<String, SupplierSupplyRatioExcelRow>> groups = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (SupplierSupplyRatioExcelRow row : rows) {
      if (!StringUtils.hasText(row.getSupplierCode())) {
        var codes = suppliedCodes.getOrDefault(normalized(row.getSupplierName()), java.util.Set.of());
        if (codes.size() > 1) throw new IllegalArgumentException("供应商名称对应多个代码，请核实：" + row.getSupplierName());
        if (codes.size() == 1) row.setSupplierCode(codes.iterator().next());
      }
      String material = normalized(row.getMaterialCode());
      String supplier = SupplierSupplyRatioNormalizeUtils.supplierIdentity(row.getSupplierCode(), row.getSupplierName());
      Map<String, SupplierSupplyRatioExcelRow> suppliers =
          groups.computeIfAbsent(material, ignored -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER));
      SupplierSupplyRatioExcelRow previous = suppliers.putIfAbsent(supplier, row);
      if (previous != null) {
        if (!sameValues(previous, row)) {
          throw new IllegalArgumentException("同一物料和供应商存在冲突记录：" + material + "/" + supplier
              + "，Excel第" + previous.getRowNo() + "、" + row.getRowNo() + "行");
        }
        response.setDuplicateRows(response.getDuplicateRows() + 1);
      }
    }

    // A transaction-bound database lock also serializes the first import into an empty scope.
    lock.acquire(bu);
    LocalDateTime now = LocalDateTime.now();
    var changedMaterials = new java.util.LinkedHashSet<String>();
    for (var group : groups.values()) {
      for (SupplierSupplyRatioExcelRow row : group.values()) {
        if (row.getSupplyRatio() == null) response.setUnfilledRatioRows(response.getUnfilledRatioRows()+1);
        boolean hasCode = StringUtils.hasText(row.getSupplierCode());
        var current = hasCode
            ? mapper.currentRelation(bu, normalized(row.getMaterialCode()), normalized(row.getSupplierCode()))
            : mapper.currentRelationByName(bu, normalized(row.getMaterialCode()), row.getSupplierName().trim(), false);
        if (hasCode && current.isEmpty()) current = mapper.currentRelationByName(
            bu, normalized(row.getMaterialCode()), row.getSupplierName().trim(), true);
        if (current.size() > 1) throw new IllegalStateException("同一物料/供应商存在多个当前版本，请核查既有数据");
        SupplierSupplyRatio previous = current.isEmpty() ? null : current.getFirst();
        boolean codeAdded = previous != null && hasCode && !StringUtils.hasText(previous.getSupplierCode());
        if (previous != null && !codeAdded && sameBusinessValues(previous,row)) {
          response.setUnchangedRows(response.getUnchangedRows()+1);
          continue;
        }
        var next = newRecord(row,bu,sourceFileName,response.getBatchNo(),username,now);
        if (previous != null) {
          // Retain the established identity spelling; only this relationship becomes historical.
          next.setMaterialCode(previous.getMaterialCode());
          if (StringUtils.hasText(previous.getSupplierCode())) next.setSupplierCode(previous.getSupplierCode());
          if (mapper.retireVersion(bu,previous.getId(),username,now)!=1)
            throw new IllegalStateException("供货关系版本已变化，请重新导入");
          response.setChangedRelations(response.getChangedRelations()+1);
          response.setDeactivatedRows(response.getDeactivatedRows()+1);
        } else response.setNewRelations(response.getNewRelations()+1);
        if (mapper.insert(next)!=1 || next.getId()==null) throw new IllegalStateException("供货关系版本保存失败");
        if (changes.insert(change(previous,next,username,now))!=1) throw new IllegalStateException("供货关系变更日志保存失败");
        response.setInsertedRows(response.getInsertedRows()+1);
        changedMaterials.add(next.getMaterialCode());
      }
    }
    if (changedMaterials.isEmpty()) response.setBatchNo(null);
    else events.publishEvent(new com.sanhua.marketingcost.service.pricing.SupplierRatiosImported(bu, java.util.Set.copyOf(changedMaterials)));
    return response;
  }

  private SupplierSupplyRatio newRecord(SupplierSupplyRatioExcelRow row, String bu,
      String file, String batch, String operator, LocalDateTime now) {
    SupplierSupplyRatio entity = new SupplierSupplyRatio();
    entity.setBusinessUnitType(bu);
    entity.setMaterialCode(normalized(row.getMaterialCode()));
    entity.setMaterialName(row.getMaterialName().trim());
    entity.setSpecModel(trim(row.getSpecModel()));
    entity.setUnit(trimToNull(row.getUnit()));
    entity.setMaterialShape(trimToNull(row.getMaterialShape()));
    entity.setSupplierName(row.getSupplierName().trim());
    entity.setSupplierCode(SupplierSupplyRatioNormalizeUtils.normalizeToNull(row.getSupplierCode()));
    entity.setSupplyRatio(row.getSupplyRatio());
    entity.setSourceType("EXCEL");
    entity.setSourceBatchNo(batch);
    entity.setIsActive(1);
    entity.setImportFileName(file);
    entity.setImportedBy(operator);
    entity.setImportedAt(now);
    entity.setCreatedBy(operator);
    entity.setCreatedAt(now);
    entity.setUpdatedBy(operator);
    entity.setUpdatedAt(now);
    entity.setDeleted(0);
    return entity;
  }

  private void validate(SupplierSupplyRatioExcelRow row) {
    if (row == null) throw new IllegalArgumentException("导入行不能为空");
    bounded(normalized(row.getMaterialCode()), "物料代码",64,true);
    bounded(row.getMaterialName(), "物料名称",180,true);
    bounded(normalized(row.getSupplierCode()), "供应商代码",64,false);
    bounded(row.getSupplierName(), "供应商名称",180,true);
    bounded(row.getSpecModel(), "型号",255,false);
    bounded(row.getUnit(), "单位",32,false);
    bounded(row.getMaterialShape(), "形态属性",64,false);
    BigDecimal ratio = row.getSupplyRatio();
    if (ratio != null) {
      try { ratio.setScale(6, java.math.RoundingMode.UNNECESSARY); }
      catch (ArithmeticException ex) { throw new IllegalArgumentException("第"+row.getRowNo()+"行比例小数最多6位（百分数最多4位），原数据未变更"); }
    }
    if (ratio != null && (ratio.signum() < 0 || ratio.compareTo(BigDecimal.ONE) > 0)) {
      throw new IllegalArgumentException("第" + row.getRowNo() + "行比例必须是0到100%之间的数字");
    }
  }

  private boolean sameValues(SupplierSupplyRatioExcelRow a, SupplierSupplyRatioExcelRow b) {
    boolean sameRatio = a.getSupplyRatio() == null || b.getSupplyRatio() == null
        ? a.getSupplyRatio() == b.getSupplyRatio()
        : a.getSupplyRatio().compareTo(b.getSupplyRatio()) == 0;
    return sameRatio
        && Objects.equals(trim(a.getMaterialName()), trim(b.getMaterialName()))
        && Objects.equals(trim(a.getSupplierName()), trim(b.getSupplierName()))
        && Objects.equals(trim(a.getSpecModel()), trim(b.getSpecModel()))
        && Objects.equals(trim(a.getUnit()), trim(b.getUnit()))
        && Objects.equals(trim(a.getMaterialShape()), trim(b.getMaterialShape()));
  }

  private boolean sameBusinessValues(SupplierSupplyRatio previous, SupplierSupplyRatioExcelRow row) {
    var before = new SupplierSupplyRatioExcelRow();
    before.setSupplyRatio(previous.getSupplyRatio()); before.setMaterialName(previous.getMaterialName());
    before.setSupplierName(previous.getSupplierName()); before.setSpecModel(previous.getSpecModel());
    before.setUnit(previous.getUnit()); before.setMaterialShape(previous.getMaterialShape());
    return sameValues(before,row);
  }

  private BusinessChangeLog change(SupplierSupplyRatio previous,SupplierSupplyRatio next,String actor,LocalDateTime now) {
    var entry=new BusinessChangeLog();
    entry.setBizDomain("SUPPLIER_SUPPLY_RATIO"); entry.setBizType("SUPPLIER_RELATION");
    entry.setBizId(next.getId()); entry.setBizDetailId(previous==null?null:previous.getId());
    entry.setFieldName("SUPPLIER_RELATION"); entry.setFieldLabel("供货关系");
    try {
      entry.setBeforeValue(previous==null?null:json.writeValueAsString(previous));
      entry.setAfterValue(json.writeValueAsString(next));
    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
      throw new IllegalStateException("供货关系变更依据保存失败",ex);
    }
    entry.setChangeReason(previous==null?"导入新增供货关系":"导入变更供货关系");
    entry.setChangedByName(actor); entry.setChangedAt(now); entry.setCreatedAt(now);
    entry.setChangeSource("FINANCE_IMPORT"); entry.setSubmitBatchNo(next.getSourceBatchNo());
    entry.setIdempotencyKey("SUPPLIER_RATIO_VERSION:"+next.getId());
    return entry;
  }

  private void bounded(String value,String label,int max,boolean mandatory) {
    if (mandatory) required(value,label);
    if (value!=null && value.trim().length()>max) throw new IllegalArgumentException(label+"最多"+max+"字符");
  }

  private String errorMessage(SupplierSupplyRatioWorkbookParseResult.ParseError row) {
    return (row.getRowNo() == null ? "" : "第" + row.getRowNo() + "行：") + row.getMessage();
  }

  private String required(String value, String name) {
    if (!StringUtils.hasText(value)) throw new IllegalArgumentException(name + "不能为空");
    return value.trim();
  }

  private String normalized(String value) {
    return SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(value);
  }

  private String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
