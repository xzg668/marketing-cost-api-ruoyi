package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.integration.drawing.DrawingVerificationCodec;
import com.sanhua.marketingcost.mapper.DrawingVerificationResultMapper;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingProductLookup;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingSourceNodeRepository;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowContextPort;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingWorkflowStage;
import com.sanhua.marketingcost.service.electronicdrawing.DrawingVerificationService;
import com.sanhua.marketingcost.service.quotebom.LiveU9BomGateway;
import com.sanhua.marketingcost.service.quotebom.U9MonthlySnapshotIdentity;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import com.sanhua.marketingcost.service.quotebom.CurrentU9BomResult;
import com.sanhua.marketingcost.service.quotebom.QuoteBomReadContext;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Current source facts, never a filename/button-based decision or a failed-read fallback. */
@Component
public class TechnicalBomAbsenceQuery {
  public record Result(String state, String evidence, String message) {
    public boolean allowsEntry() { return "MISSING".equals(state); }
  }
  private final LiveU9BomGateway u9;
  private final QuoteBomMonthlySnapshotMapper monthly;
  private final ElectronicDrawingWorkflowContextPort contexts;
  private final ElectronicDrawingSourceNodeRepository nodes;
  private final ElectronicDrawingProductLookup products;
  private final DrawingVerificationService verifications;
  private final DrawingVerificationResultMapper results;
  private final DrawingVerificationCodec codec;
  public TechnicalBomAbsenceQuery(LiveU9BomGateway u9, QuoteBomMonthlySnapshotMapper monthly, ElectronicDrawingWorkflowContextPort contexts,
      ElectronicDrawingSourceNodeRepository nodes, ElectronicDrawingProductLookup products,
      DrawingVerificationService verifications, DrawingVerificationResultMapper results, DrawingVerificationCodec codec) {
    this.u9 = u9; this.monthly = monthly; this.contexts = contexts; this.nodes = nodes; this.products = products;
    this.verifications = verifications; this.results = results; this.codec = codec;
  }
  public Result read(QuoteBomReadContext context) {
    try {
      // Reading the entry page must not claim/backfill a monthly card. A saved U9 card retains priority.
      var saved = monthly.selectU9MonthlyByIdentity(U9MonthlySnapshotIdentity.from(context).identityKey());
      if (saved != null && "SUCCESS".equals(saved.getSyncStatus()))
        return new Result("U9", saved.getStructureFingerprint(), "本产品已有当月U9 BOM，沿用现有来源");
      if (saved != null && !"NOT_FOUND".equals(saved.getSyncStatus())) return blocked("本产品当月U9 BOM尚未检查完成");
      var original = u9.readLive(context);
      if (original == null) return blocked("U9来源未返回结论，请重新检查");
      if (original.status() == CurrentU9BomResult.Status.AVAILABLE)
        return new Result("U9", original.structureFingerprint(), "本产品已有U9 BOM，沿用现有来源");
      if (original.status() != CurrentU9BomResult.Status.NOT_FOUND)
        return blocked("U9来源尚未确认缺失：" + Objects.toString(original.message(), original.status().name()));
      var drawing = contexts.load(context.oaFormItemId(), context.businessUnitType(), context.priceOrgCode(), context.accountingMonth());
      if (!drawing.active()) return blocked("当前产品的图库来源范围已失效，请刷新");
      if (drawing.sourceVersionId() != null && !nodes.findByVersionId(drawing.sourceVersionId()).isEmpty())
        return new Result("GALLERY", "DRAWING:" + drawing.sourceVersionId(), "已有电子图库明细");
      var task = verifications.current(drawing).orElse(null);
      if (task != null) {
        if (!verifications.matchesCurrentInputs(drawing, task)) return blocked("图库检查不属于本产品的当前资料，请重新检查");
        if (!"RECEIVED".equals(task.getTaskState()) || task.getCurrentResultId() == null)
          return blocked("图库检查尚未完成或待确认，不能当作没有图库明细");
        var result = results.findById(task.getCurrentResultId());
        if (result == null || !Objects.equals(result.getVerificationTaskId(), task.getId())) return blocked("图库检查结果归属不完整");
        var summary = codec.parse(result.getRawSummaryJson()).summary();
        if (summary.data().values().stream().allMatch(value -> value.params().items().isEmpty()) && summary.plmData().isEmpty())
          return missing("DRAWING_RESULT_EMPTY:" + result.getId());
        return blocked("图库已返回资料，需先完成该产品明细接入或确认");
      }
      if (ElectronicDrawingWorkflowStage.NOT_FOUND.equals(drawing.workflowStage())) return missing("DRAWING_NOT_FOUND:" + drawing.workflowId());
      if (ElectronicDrawingWorkflowStage.isElectronicDrawing(drawing.workflowStage()))
        return blocked("图库正在处理或上次检查未完成，请先核实当前图库结果");
      if (products.drawingOptions(drawing).isEmpty()) return missing("NO_MATCHED_DRAWING:" + drawing.workflowId());
      return blocked("尚未确认本产品的图库结果，请先检查已有图号");
    } catch (RuntimeException error) {
      return blocked("来源检查失败，不能判定为没有BOM：" + Objects.toString(error.getMessage(), "请稍后重试"));
    }
  }
  private Result missing(String evidence) { return new Result("MISSING", "U9_NOT_FOUND|" + evidence, "本产品没有可用U9或电子图库明细，可参考成品或上传完整BOM"); }
  private Result blocked(String message) { return new Result("UNCONFIRMED", null, message); }
}
