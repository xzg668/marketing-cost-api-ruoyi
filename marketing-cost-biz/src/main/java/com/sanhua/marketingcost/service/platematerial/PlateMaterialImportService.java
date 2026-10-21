package com.sanhua.marketingcost.service.platematerial;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.PlateMaterialMapping;
import com.sanhua.marketingcost.mapper.PlateMaterialMappingMapper;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialWorkbookParser.WorkbookData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlateMaterialImportService {
  public enum Action { INSERT, UPDATE, UNCHANGED }
  public record Change(int excelRow, String model, PlateMaterial material, String previousMaterial,
      Long mappingId, Integer expectedVersion, Action action) {}
  public record Preview(String fileName, String sha256, String sheet, String businessUnitType,
      int inserted, int updated, int unchanged, List<Change> changes,
      List<PlateMaterialWorkbookParser.RowError> errors,
      List<PlateMaterialWorkbookParser.Duplicate> duplicates, String fingerprint, boolean canConfirm) {}
  public record ImportResult(String batchId, int inserted, int updated, int unchanged, int duplicates) {}

  private final PlateMaterialWorkbookParser parser;
  private final PlateMaterialMappingMapper mapper;
  private final ObjectMapper json;

  public PlateMaterialImportService(PlateMaterialWorkbookParser parser,
      PlateMaterialMappingMapper mapper, ObjectMapper json) {
    this.parser = parser;
    this.mapper = mapper;
    this.json = json;
  }

  public Preview preview(byte[] bytes, String fileName, String unit) {
    PlateMaterialMappingService.requireUnit(unit);
    return plan(parser.parse(bytes, fileName), unit, false);
  }

  @Transactional
  public ImportResult confirm(byte[] bytes, String fileName, String unit, String fingerprint, long actor) {
    PlateMaterialMappingService.requireUnit(unit);
    PlateMaterialMappingService.requireActor(actor);
    var data = parser.parse(bytes, fileName);
    if (!data.errors().isEmpty()) throw new IllegalArgumentException("文件含非法业务行，请处理预览中的全部错误后重试");
    // Lock only the workbook's identities. Unique keys also protect concurrently created models.
    var preview = plan(data, unit, true);
    if (fingerprint == null || !fingerprint.equals(preview.fingerprint())) {
      throw PlateMaterialMappingService.changed();
    }
    String batch = "PMM-" + UUID.randomUUID();
    for (var change : preview.changes().stream().sorted(java.util.Comparator.comparing(Change::model)).toList()) {
      if (change.action() == Action.UNCHANGED) continue;
      var row = new PlateMaterialMapping();
      row.setId(change.mappingId());
      row.setBusinessUnitType(unit);
      row.setNormalizedModel(change.model());
      row.setPlateMaterial(change.material().value());
      row.setRowVersion(0);
      row.setSourceType("EXCEL");
      row.setSourceFileName(data.fileName());
      row.setSourceFileSha256(data.sha256());
      row.setSourceSheetName(PlateMaterialWorkbookParser.SHEET);
      row.setSourceRowNumber(change.excelRow());
      row.setImportBatchId(batch);
      row.setCreatedBy(actor);
      row.setUpdatedBy(actor);
      if (change.action() == Action.INSERT) {
        mapper.insert(row);
      } else if (mapper.updateWithVersion(row, change.expectedVersion()) != 1) {
        throw PlateMaterialMappingService.changed();
      }
    }
    return new ImportResult(batch, preview.inserted(), preview.updated(), preview.unchanged(), preview.duplicates().size());
  }

  private Preview plan(WorkbookData data, String unit, boolean lock) {
    List<String> models = data.rows().stream().map(PlateMaterialWorkbookParser.SourceRow::model).sorted().toList();
    Map<String, PlateMaterialMapping> existing = new HashMap<>();
    for (int offset = 0; offset < models.size(); offset += 500) {
      var chunk = models.subList(offset, Math.min(offset + 500, models.size()));
      mapper.findModels(unit, chunk, lock).forEach(row -> existing.put(row.getNormalizedModel(), row));
    }
    List<Change> changes = new ArrayList<>();
    int inserted = 0, updated = 0, unchanged = 0;
    for (var source : data.rows()) {
      var previous = existing.get(source.model());
      Action action = previous == null ? Action.INSERT
          : previous.getPlateMaterial().equals(source.material().value()) ? Action.UNCHANGED : Action.UPDATE;
      switch (action) {
        case INSERT -> inserted++;
        case UPDATE -> updated++;
        case UNCHANGED -> unchanged++;
      }
      changes.add(new Change(source.excelRow(), source.model(), source.material(),
          previous == null ? null : previous.getPlateMaterial(),
          previous == null ? null : previous.getId(),
          previous == null ? null : previous.getRowVersion(), action));
    }
    // This is a revision digest, not an authorization token. Write permission is checked separately.
    String fingerprint;
    try {
      fingerprint = PlateMaterialWorkbookParser.sha256(json.writeValueAsBytes(
          List.of(unit, data.fileName(), data.sha256(), changes)));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("导入预览版本生成失败", ex);
    }
    return new Preview(data.fileName(), data.sha256(), PlateMaterialWorkbookParser.SHEET, unit,
        inserted, updated, unchanged, List.copyOf(changes), data.errors(), data.duplicates(),
        fingerprint, data.errors().isEmpty());
  }
}
