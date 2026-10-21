package com.sanhua.marketingcost.service.technicaldata;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BusinessChangeLog;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.mapper.BusinessChangeLogMapper;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialMappingService;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialMode;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialModel;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Publishes the confirmed PROFILE selection when the complete product version becomes effective. */
@Service
public class TechnicalDataPlateMaterialPublication {
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataVersionContentCodec codec;
  private final PlateMaterialMappingService mappings;
  private final BusinessChangeLogMapper events;
  private final TechnicalDataAuditLogService audit;

  public TechnicalDataPlateMaterialPublication(QuoteTechnicalDataRepository repository,
      TechnicalDataVersionContentCodec codec, PlateMaterialMappingService mappings,
      BusinessChangeLogMapper events, TechnicalDataAuditLogService audit) {
    this.repository = repository;
    this.codec = codec;
    this.mappings = mappings;
    this.events = events;
    this.audit = audit;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void publish(QuoteTechProduct product, QuoteTechDataVersion effective, QuoteTechModule profile) {
    if (!Integer.valueOf(2).equals(product.getContentSchemaVersion())) return;
    var material = codec.plateMaterial(effective);
    if (material == null || material.mode() != PlateMaterialMode.MANUAL_REQUIRED) return;
    if (!Objects.equals(effective.getProductId(), product.getId())
        || !TechnicalDataSubmissionState.submitted(effective.getVersionStatus())
        || !"PROFILE".equals(profile.getModuleType())
        || !Objects.equals(profile.getProductId(), product.getId())
        || !Integer.valueOf(1).equals(profile.getRequiredFlag())
        || !TechnicalDataSubmissionState.submitted(profile.getModuleStatus())
        || profile.getCurrentVersionId() == null) {
      throw invalid("板片材质发布必须使用本产品最终有效资料中的已提交产品信息");
    }
    var source = repository.findVersion(profile.getCurrentVersionId())
        .orElseThrow(() -> invalid("板片材质的产品信息提交版本不存在"));
    if (!Objects.equals(source.getProductId(), product.getId())
        || !TechnicalDataSubmissionState.submitted(source.getVersionStatus())
        || !material.equals(codec.plateMaterial(source))) {
      throw invalid("最终材质与产品信息提交版本不一致");
    }
    String eventKey = "TD-PLATE-MATERIAL:" + source.getId();
    // A later return of another module must not republish this old PROFILE selection.
    if (events.selectCount(Wrappers.<BusinessChangeLog>lambdaQuery()
        .eq(BusinessChangeLog::getIdempotencyKey, eventKey)) > 0) return;
    var task = repository.findTask(product.getTaskId())
        .orElseThrow(() -> invalid("板片材质发布缺少所属补录任务"));
    if (!Objects.equals(task.getBusinessUnitType(), material.source().businessUnitType())
        || !Objects.equals(PlateMaterialModel.normalize(product.getSourceModel()),
            material.source().normalizedModel())
        || profile.getAssigneeUserId() == null || profile.getAssigneeUserId() <= 0) {
      throw invalid("板片材质的型号、业务单元或确认技术员不一致");
    }
    var mapping = mappings.registerConfirmedSelection(material.source().businessUnitType(),
        material.source().normalizedModel(), material.material().value(), profile.getAssigneeUserId());
    audit.record(task, product, source.getId(), "PLATE_MATERIAL_CONFIRMED",
        null, codec.plateMaterialJson(material),
        "最终有效版本 " + effective.getId() + " 确认材质，公共型号配置 ID=" + mapping.getId(),
        new TechnicalDataActor(profile.getAssigneeUserId(), profile.getAssigneeName(), Set.of()),
        eventKey, eventKey);
  }

  private TechnicalDataTaskException invalid(String message) {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, message);
  }
}
