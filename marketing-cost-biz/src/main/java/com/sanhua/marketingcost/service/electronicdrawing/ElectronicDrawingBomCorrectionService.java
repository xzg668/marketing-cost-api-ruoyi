package com.sanhua.marketingcost.service.electronicdrawing;

import static com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingMaterialResolutionException.*;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingBomCorrectionRequest;
import com.sanhua.marketingcost.dto.electronicdrawing.ElectronicDrawingMaterialResolutionResponse;
import com.sanhua.marketingcost.entity.BusinessChangeLog;
import com.sanhua.marketingcost.entity.ElectronicDrawingSourceNode;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.QuoteBomSupplementVersion;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomSupplementVersionMapper;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 本单修订只生成新草稿；不覆盖图库原文、旧来源节点和已核算版本。 */
@Service
public class ElectronicDrawingBomCorrectionService {
  private final ElectronicDrawingWorkflowContextPort contexts;
  private final QuoteBomPreparationRecordMapper preparations;
  private final QuoteBomSupplementVersionMapper versions;
  private final ElectronicDrawingSourceNodeRepository nodes;
  private final MaterialMasterRawMapper materials;
  private final ElectronicDrawingActorProvider actors;
  private final ElectronicDrawingMaterialResolutionService resolutions;
  private final ObjectMapper json;
  private final BusinessChangeLogMapper changes;

  public ElectronicDrawingBomCorrectionService(
      ElectronicDrawingWorkflowContextPort contexts,
      QuoteBomPreparationRecordMapper preparations,
      QuoteBomSupplementVersionMapper versions,
      ElectronicDrawingSourceNodeRepository nodes,
      MaterialMasterRawMapper materials,
      ElectronicDrawingActorProvider actors,
      ElectronicDrawingMaterialResolutionService resolutions,
      ObjectMapper json,
      BusinessChangeLogMapper changes) {
    this.contexts = contexts;
    this.preparations = preparations;
    this.versions = versions;
    this.nodes = nodes;
    this.materials = materials;
    this.actors = actors;
    this.resolutions = resolutions;
    this.json = json;
    this.changes = changes;
  }

  @Transactional
  public ElectronicDrawingMaterialResolutionResponse save(
      Long taskId, ElectronicDrawingBomCorrectionRequest request, String month) {
    var actor = actors.current();
    if (actor == null || actor.userId() == null || actor.userId() <= 0)
      throw invalid(COMMAND_INVALID, "当前财务人员身份无效");
    if (request == null
        || request.sourceNodeId() == null
        || request.lines() == null
        || request.lines().isEmpty()
        || request.lines().size() > 30
        || request.reason() == null
        || request.reason().isBlank()
        || request.reason().length() > 500)
      throw invalid(COMMAND_INVALID, "请填写修正原因及 1–30 条材料的料号、用量和单位");
    var context = contexts.loadForCurrentBusinessUnit(taskId, month);
    var preparation = preparations.selectForElectronicDrawingImport(context.preparationId());
    if (preparation == null
        || !Objects.equals(
            preparation.getElectronicWorkflowVersion(), request.expectedTaskVersion())
        || !Objects.equals(context.revision(), request.expectedTaskVersion())
        || !Objects.equals(context.sourceVersionId(), request.expectedSourceVersionId())
        || !Objects.equals(
            preparation.getElectronicSourceVersionId(), request.expectedSourceVersionId()))
      throw invalid(TASK_VERSION_CONFLICT, "BOM 来源已变化，请刷新后修正");
    var state = resolutions.state(taskId, month);
    var selected =
        state.items().stream()
            .filter(row -> Objects.equals(row.sourceNodeId(), request.sourceNodeId()))
            .findFirst()
            .orElseThrow(() -> invalid(SOURCE_NODE_INVALID, "修正节点不属于当前报价 BOM"));
    if (!selected.canModify()) throw invalid(SOURCE_VERSION_INVALID, "当前 BOM 已不可编辑，请先发起修订");
    var original = versions.selectById(context.sourceVersionId());
    var originalNodes = nodes.findByVersionId(original.getId());
    var source =
        originalNodes.stream()
            .filter(row -> Objects.equals(row.getId(), request.sourceNodeId()))
            .findFirst()
            .orElseThrow();
    if (request.lines().size() > 1
        && originalNodes.stream()
            .anyMatch(
                row -> Objects.equals(row.getParentSourceSequence(), source.getSourceSequence())))
      throw invalid(COMMAND_INVALID, "有下级的部件不能直接拆成多条材料；请修正实际原材料行");
    var codes = new LinkedHashSet<String>();
    for (var line : request.lines()) {
      if (line == null
          || line.materialCode() == null
          || !line.materialCode().trim().matches("[0-9]{1,64}")
          || line.quantity() == null
          || line.quantity().signum() <= 0
          || line.quantity().stripTrailingZeros().scale() > 8
          || line.quantity().precision() - line.quantity().scale() > 12
          || line.unit() == null
          || line.unit().isBlank()) throw invalid(COMMAND_INVALID, "每条材料须填写数字料号、正数用量（最多8位小数）和单位");
      if (!codes.add(line.materialCode().trim())) throw invalid(COMMAND_INVALID, "同一料号请合并用量后填写");
    }
    var catalog = materials.selectByLatestBatchAndCodes(codes, null, context.materialOrgCode());
    var byCode = new LinkedHashMap<String, MaterialMasterRaw>();
    for (var material : catalog)
      if (byCode.put(material.getMaterialCode().trim(), material) != null)
        throw invalid(MATERIAL_NOT_FOUND, "所选料号在当前组织有多条记录，请核实料品档案");
    if (!byCode.keySet().equals(codes)) throw invalid(MATERIAL_NOT_FOUND, "所选料号不是当前物料组织内正常启用的料品");
    for (var line : request.lines()) {
      var material = byCode.get(line.materialCode().trim());
      if (material.getMaterialName() == null
          || material.getMaterialName().isBlank()
          || material.getMaterialName().length() > 180
          || material.getUnit() == null
          || material.getUnit().length() > 32)
        throw invalid(COMMAND_INVALID, "料品名称或单位不符合 BOM 保存要求，请核实料品档案：" + material.getMaterialCode());
      if (material.getUnit() == null
          || !line.unit().trim().equalsIgnoreCase(material.getUnit().trim()))
        throw invalid(COMMAND_INVALID, "用量单位应与料品档案一致：" + material.getMaterialCode());
    }
    var now = LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE);
    var latest =
        versions.selectList(
            Wrappers.<QuoteBomSupplementVersion>lambdaQuery()
                .eq(QuoteBomSupplementVersion::getPreparationId, context.preparationId())
                .eq(QuoteBomSupplementVersion::getSupplementScope, original.getSupplementScope())
                .orderByDesc(QuoteBomSupplementVersion::getVersionNo)
                .last("LIMIT 1"));
    var draft = new QuoteBomSupplementVersion();
    BeanUtils.copyProperties(
        original,
        draft,
        "id",
        "compositionFingerprint",
        "versionNo",
        "submittedBy",
        "submittedByName",
        "submittedAt",
        "reviewerUserId",
        "reviewerName",
        "reviewedAt",
        "reviewComment",
        "reuseValidUntil",
        "createdAt",
        "updatedAt");
    draft.setVersionNo(Math.addExact(latest.getFirst().getVersionNo(), 1));
    draft.setVersionStatus("DRAFT");
    draft.setReusedFromVersionId(original.getId());
    draft.setCreatedAt(now);
    draft.setUpdatedAt(now);
    if (versions.insert(draft) != 1) throw new IllegalStateException("BOM 修正版创建失败");
    var next = new ArrayList<ElectronicDrawingSourceNode>();
    for (var row : originalNodes) {
      if (!Objects.equals(row.getId(), source.getId())) {
        next.add(copy(row, draft.getId(), now));
        continue;
      }
      for (int index = 0; index < request.lines().size(); index++) {
        var line = request.lines().get(index);
        var material = byCode.get(line.materialCode().trim());
        var replacement = copy(row, draft.getId(), now);
        String sequence =
            request.lines().size() == 1
                ? row.getSourceSequence()
                : row.getSourceSequence() + ":C" + draft.getVersionNo() + ":" + (index + 1);
        if (sequence.length() > 128) throw invalid(COMMAND_INVALID, "节点层级过长，请联系管理员核实");
        replacement.setSourceSequence(sequence);
        replacement.setSourceName(material.getMaterialName());
        replacement.setMaterial(material.getMaterialSpec());
        replacement.setQty(line.quantity());
        replacement.setQuantityUnit(material.getUnit().trim());
        replacement.setCorrectedFromSourceNodeId(source.getId());
        replacement.setReferenceWeight(null);
        replacement.setReferenceWeightUnit(null);
        replacement.setMatchStatus(ElectronicDrawingSourceNode.MATCH_MANUAL);
        replacement.setResolvedMaterialCode(material.getMaterialCode().trim());
        replacement.setResolvedBy("USER:" + actor.userId() + ":" + actor.userName());
        replacement.setResolvedAt(now);
        next.add(replacement);
      }
    }
    nodes.insertAll(draft.getId(), next);
    var refreshed = contexts.attachSourceVersion(context, draft.getId(), now);
    refreshed =
        contexts.updateStage(
            refreshed,
            ElectronicDrawingWorkflowStage.MATCHED,
            actor.userId(),
            actor.userName(),
            now);
    var change = new BusinessChangeLog();
    change.setBizDomain("QUOTE_BOM");
    change.setBizType("ELECTRONIC_DRAWING");
    change.setBizId(refreshed.preparationId());
    change.setBizDetailId(draft.getId());
    change.setOaNo(refreshed.oaNo());
    change.setOaFormItemId(refreshed.oaFormItemId());
    change.setFieldName("FINANCE_BOM_CORRECTION");
    change.setFieldLabel("财务修正 BOM");
    change.setBeforeValue(String.valueOf(original.getId()));
    change.setAfterValue(audit(original.getId(), draft.getId(), source.getId(), request));
    change.setChangeReason(request.reason().trim());
    change.setChangedBy(actor.userId());
    change.setChangedByName(actor.userName());
    change.setChangedAt(now);
    change.setChangeSource("FINANCE_BOM_CORRECTION");
    change.setIdempotencyKey("FINANCE_BOM_CORRECTION:" + taskId + ":" + draft.getId());
    change.setCreatedAt(now);
    if (changes.insert(change) != 1) throw new IllegalStateException("BOM 修正操作记录保存失败");
    return resolutions.state(taskId, month);
  }

  private static ElectronicDrawingSourceNode copy(
      ElectronicDrawingSourceNode source, Long version, LocalDateTime now) {
    var result = new ElectronicDrawingSourceNode();
    BeanUtils.copyProperties(source, result, "id", "supplementVersionId", "createdAt", "updatedAt");
    result.setSupplementVersionId(version);
    result.setCreatedAt(now);
    result.setUpdatedAt(now);
    return result;
  }

  private String audit(
      Long before, Long after, Long sourceNode, ElectronicDrawingBomCorrectionRequest request) {
    try {
      return json.writeValueAsString(
          Map.of(
              "previousVersionId",
              before,
              "versionId",
              after,
              "sourceNodeId",
              sourceNode,
              "reason",
              request.reason().trim(),
              "materials",
              request.lines()));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("BOM 修正依据无法保存", e);
    }
  }

  private static ElectronicDrawingMaterialResolutionException invalid(String code, String message) {
    return new ElectronicDrawingMaterialResolutionException(code, message);
  }
}
