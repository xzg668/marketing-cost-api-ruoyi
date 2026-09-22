package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import com.sanhua.marketingcost.entity.QuoteTechAuxItem;
import com.sanhua.marketingcost.entity.QuoteTechSalaryItem;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** 显式复制业务字段，不复制主键、审批身份和行版本。 */
final class TechnicalDataVersionCopies {
  private TechnicalDataVersionCopies() {}

  static QuoteTechDataVersion scopedDraft(QuoteTechDataVersion source, int versionNo, Long actorId,
      LocalDateTime now, Set<String> scope) {
    var draft = copiedDraft(source, versionNo, actorId, now);
    var empty = new QuoteTechDataVersion();
    empty.setNewProductFlag(0);
    empty.setPackageTotalAmount(BigDecimal.ZERO);
    empty.setAuxiliaryTotalAmount(BigDecimal.ZERO);
    empty.setSalaryTotalAmount(BigDecimal.ZERO);
    for (String type : TechnicalDataModuleType.orderedCodes()) {
      if (!scope.contains(type)) copyModule(empty, draft, type);
    }
    return draft;
  }

  static void copyModule(QuoteTechDataVersion from, QuoteTechDataVersion to, String type) {
    switch (TechnicalDataModuleType.valueOf(type)) {
      case PROFILE -> {
        to.setProductModel(from.getProductModel());
        to.setProductProperty(from.getProductProperty());
        to.setNewProductFlag(from.getNewProductFlag());
        to.setProductFeesJson(from.getProductFeesJson());
      }
      case DRAWING_BOM -> to.setDrawingBomJson(from.getDrawingBomJson());
      case MANUFACTURING -> to.setManufacturingJson(from.getManufacturingJson());
      case PACKAGE -> {
        to.setPackagingJson(from.getPackagingJson());
        to.setPackageTotalAmount(from.getPackageTotalAmount());
      }
      case AUXILIARY -> to.setAuxiliaryTotalAmount(from.getAuxiliaryTotalAmount());
      case SOLDER -> to.setSolderItemsJson(from.getSolderItemsJson());
      case SALARY -> to.setSalaryTotalAmount(from.getSalaryTotalAmount());
      case NET_LOSS -> to.setNetLossJson(from.getNetLossJson());
      case PRICE -> to.setPriceItemsJson(from.getPriceItemsJson());
    }
  }

  static QuoteTechDataVersion copiedDraft(
      QuoteTechDataVersion source,
      int versionNo,
      Long actorId,
      LocalDateTime createdAt) {
    QuoteTechDataVersion draft = new QuoteTechDataVersion();
    draft.setProductId(source.getProductId());
    draft.setVersionNo(versionNo);
    draft.setVersionStatus(QuoteTechDataVersion.STATUS_DRAFT);
    draft.setProductModel(source.getProductModel());
    draft.setProductProperty(source.getProductProperty());
    draft.setNewProductFlag(source.getNewProductFlag());
    draft.setContentSchemaVersion(source.getContentSchemaVersion());
    draft.setProductFeesJson(source.getProductFeesJson());
    draft.setDrawingBomJson(source.getDrawingBomJson());
    draft.setManufacturingJson(source.getManufacturingJson());
    draft.setPackagingJson(source.getPackagingJson());
    draft.setSolderItemsJson(source.getSolderItemsJson());
    draft.setNetLossJson(source.getNetLossJson());
    draft.setPriceItemsJson(source.getPriceItemsJson());
    draft.setSourceFactsJson(source.getSourceFactsJson());
    draft.setPackageTotalAmount(source.getPackageTotalAmount());
    draft.setAuxiliaryTotalAmount(source.getAuxiliaryTotalAmount());
    draft.setSalaryTotalAmount(source.getSalaryTotalAmount());
    draft.setReferenceSnapshotJson(source.getReferenceSnapshotJson());
    draft.setCreatedFromVersionId(source.getId());
    draft.setRowVersion(0);
    draft.setCreatedBy(actorId);
    draft.setUpdatedBy(actorId);
    draft.setCreatedAt(createdAt);
    draft.setUpdatedAt(createdAt);
    return draft;
  }

  static List<QuoteTechPackageItem> copyPackages(List<QuoteTechPackageItem> source) {
    return source.stream().map(item -> {
      QuoteTechPackageItem copy = new QuoteTechPackageItem();
      copy.setLineNo(item.getLineNo());
      copy.setSortSeq(item.getSortSeq());
      copy.setComponentMaterialNo(item.getComponentMaterialNo());
      copy.setComponentName(item.getComponentName());
      copy.setComponentSpec(item.getComponentSpec());
      copy.setQuantity(item.getQuantity());
      copy.setOriginalUnit(item.getOriginalUnit());
      copy.setStandardQuantity(item.getStandardQuantity());
      copy.setStandardUnit(item.getStandardUnit());
      copy.setConversionFactor(item.getConversionFactor());
      copy.setPriceBasisType(item.getPriceBasisType());
      copy.setReferenceUnitPrice(item.getReferenceUnitPrice());
      copy.setAmount(item.getAmount());
      copy.setSourceReferenceId(item.getSourceReferenceId());
      copy.setSourceReferenceVersion(item.getSourceReferenceVersion());
      copy.setSourceSnapshotJson(item.getSourceSnapshotJson());
      copy.setRemark(item.getRemark());
      return copy;
    }).toList();
  }

  static List<QuoteTechAuxItem> copyAuxiliaries(List<QuoteTechAuxItem> source) {
    return source.stream().map(item -> {
      QuoteTechAuxItem copy = new QuoteTechAuxItem();
      copy.setLineNo(item.getLineNo());
      copy.setSortSeq(item.getSortSeq());
      copy.setSubjectCode(item.getSubjectCode());
      copy.setSubjectName(item.getSubjectName());
      copy.setAuxiliaryMaterialNo(item.getAuxiliaryMaterialNo());
      copy.setAuxiliaryName(item.getAuxiliaryName());
      copy.setAuxiliarySpec(item.getAuxiliarySpec());
      copy.setPricingMethod(item.getPricingMethod());
      copy.setQuantity(item.getQuantity());
      copy.setOriginalUnit(item.getOriginalUnit());
      copy.setStandardQuantity(item.getStandardQuantity());
      copy.setStandardUnit(item.getStandardUnit());
      copy.setConversionFactor(item.getConversionFactor());
      copy.setReferenceUnitPrice(item.getReferenceUnitPrice());
      copy.setPriceUnit(item.getPriceUnit());
      copy.setLossRate(item.getLossRate());
      copy.setAmount(item.getAmount());
      copy.setSourceReferenceId(item.getSourceReferenceId());
      copy.setSourceReferenceVersion(item.getSourceReferenceVersion());
      copy.setSourceSnapshotJson(item.getSourceSnapshotJson());
      copy.setRemark(item.getRemark());
      return copy;
    }).toList();
  }

  static List<QuoteTechSalaryItem> copySalaries(List<QuoteTechSalaryItem> source) {
    return source.stream().map(item -> {
      QuoteTechSalaryItem copy = new QuoteTechSalaryItem();
      copy.setLineNo(item.getLineNo());
      copy.setSortSeq(item.getSortSeq());
      copy.setProcessCode(item.getProcessCode());
      copy.setProcessName(item.getProcessName());
      copy.setLaborType(item.getLaborType());
      copy.setWorkingHours(item.getWorkingHours());
      copy.setOriginalTimeUnit(item.getOriginalTimeUnit());
      copy.setStandardHours(item.getStandardHours());
      copy.setStandardTimeUnit(item.getStandardTimeUnit());
      copy.setConversionFactor(item.getConversionFactor());
      copy.setWageRate(item.getWageRate());
      copy.setRateUnit(item.getRateUnit());
      copy.setHourlyRate(item.getHourlyRate());
      copy.setPersonCoefficient(item.getPersonCoefficient());
      copy.setAmount(item.getAmount());
      copy.setSourceReferenceId(item.getSourceReferenceId());
      copy.setSourceReferenceVersion(item.getSourceReferenceVersion());
      copy.setSourceSnapshotJson(item.getSourceSnapshotJson());
      copy.setRemark(item.getRemark());
      return copy;
    }).toList();
  }

}
