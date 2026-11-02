package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.function.BiPredicate;

/** 显式比较客户端可编辑字段和来源选择；不使用客户端“未修改”标记，也不接受未知字段。 */
final class TechnicalDataSavedModuleInput {
  private TechnicalDataSavedModuleInput() {}

  static boolean matches(Object request, QuoteTechDataVersion version, QuoteTechnicalDataRepository repository,
      TechnicalDataVersionContentCodec codec) {
    return switch (request) {
      case TechnicalDataSolderSaveRequest input -> solder(input, codec.solder(version));
      case TechnicalDataPackageSaveRequest input -> packaging(input, version, repository, codec);
      case TechnicalDataAuxiliarySaveRequest input -> auxiliary(input, version, repository, codec);
      case TechnicalDataSalarySaveRequest input -> salary(input, version, repository, codec);
      case TechnicalDataNetLossSaveRequest input -> netLoss(input, codec.netLoss(version));
      case TechnicalDataPriceSaveRequest input -> prices(input, codec.prices(version), codec);
      case TechnicalDataManufacturingSaveRequest input -> manufacturing(input, codec.manufacturing(version));
      case TechnicalDataProfileUpdateRequest input -> profile(input, version, codec);
      default -> false;
    };
  }

  private static boolean solder(TechnicalDataSolderSaveRequest input, TechnicalDataSupplementContent.Solder saved) {
    if (saved == null || !input.getUnknownFields().isEmpty() || !Objects.equals(input.getEntryMode(), saved.entryMode())) return false;
    var source = saved.reference();
    if (!Objects.equals(input.getReferenceMaterialNo(), source == null ? null : source.materialNo())
        || !Objects.equals(input.getReferenceFingerprint(), source == null ? null : source.fingerprint())) return false;
    return rows(input.getItems(), saved.items(), (row, old) -> row.getUnknownFields().isEmpty()
        && number(row.getQuantityPerProduct(), old.quantityPerProduct())
        && ("REFERENCE".equals(saved.entryMode())
            ? Objects.equals(row.getItemKey(), old.itemKey()) && row.getMaterialNo() == null && row.getMaterialFingerprint() == null
            : row.getItemKey() == null && text(row.getMaterialNo(), old.materialNo()) && old.evidence() != null
                && Objects.equals(row.getMaterialFingerprint(), old.evidence().material().fingerprint())));
  }

  private static boolean packaging(TechnicalDataPackageSaveRequest input, QuoteTechDataVersion version,
      QuoteTechnicalDataRepository repository, TechnicalDataVersionContentCodec codec) {
    var saved = codec.packaging(version);
    if (saved == null || !input.getUnknownFields().isEmpty() || !Objects.equals(input.getEntryMode(), saved.entryMode())
        || !number(input.getParentBaseQuantity(), saved.parentBaseQuantity())) return false;
    var source = saved.source();
    if (!Objects.equals(input.getReferenceParentNodeId(), source == null ? null : source.parentNodeId())
        || !Objects.equals(input.getReferenceFingerprint(), source == null ? null : source.fingerprint())) return false;
    return rows(input.getItems(), repository.findPackageItems(version.getId()), (row, old) -> {
      var evidence = codec.packageEvidence(old);
      return evidence != null && row.getUnknownFields().isEmpty() && number(row.getQuantity(), old.getQuantity())
          && text(row.getRemark(), old.getRemark()) && Objects.equals(row.getSourceNodeId(), evidence.sourceNodeId())
          && Objects.equals(row.getSourceParentNodeId(), evidence.parentNodeId())
          && Objects.equals(row.getSourceFingerprint(), evidence.sourceFingerprint())
          && (row.getSourceNodeId() != null || text(row.getComponentModel(), evidence.model())
              && text(row.getComponentName(), old.getComponentName()) && text(row.getComponentSpec(), old.getComponentSpec())
              && text(row.getUnit(), old.getOriginalUnit()));
    });
  }

  private static boolean auxiliary(TechnicalDataAuxiliarySaveRequest input, QuoteTechDataVersion version,
      QuoteTechnicalDataRepository repository, TechnicalDataVersionContentCodec codec) {
    var saved = repository.findAuxItems(version.getId());
    if (saved.isEmpty() || !input.getUnknownFields().isEmpty()) return false;
    var origin = codec.auxiliaryEvidence(saved.getFirst());
    if (origin == null || !Objects.equals(input.getEntryMode(), origin.cms() == null ? "UPLOAD" : "REFERENCE")
        || !Objects.equals(input.getReferenceMaterialNo(), origin.cms() == null ? null : origin.cms().materialNo())
        || !Objects.equals(input.getReferenceFingerprint(), origin.cms() == null ? null : origin.cms().fingerprint())
        || !Objects.equals(input.getFileSha256(), origin.upload() == null ? null : origin.upload().fileSha256())) return false;
    return rows(input.getItems(), saved, (row, old) -> row.getUnknownFields().isEmpty()
        && codec.auxiliaryEvidence(old) != null && Objects.equals(row.getItemKey(), codec.auxiliaryEvidence(old).itemKey())
        && number(row.getAmount(), old.getAmount()));
  }

  private static boolean salary(TechnicalDataSalarySaveRequest input, QuoteTechDataVersion version,
      QuoteTechnicalDataRepository repository, TechnicalDataVersionContentCodec codec) {
    var saved = repository.findSalaryItems(version.getId());
    if (saved.size() != 2 || !input.getUnknownFields().isEmpty()) return false;
    var direct = saved.stream().filter(row -> "DIRECT".equals(row.getLaborType())).map(codec::salaryEvidence).filter(Objects::nonNull).findFirst().orElse(null);
    var indirect = saved.stream().filter(row -> "INDIRECT".equals(row.getLaborType())).map(codec::salaryEvidence).filter(Objects::nonNull).findFirst().orElse(null);
    return direct != null && indirect != null && indirect.reference() != null
        && Objects.equals(input.getEntryMode(), direct.upload() == null ? "REFERENCE" : "UPLOAD")
        && Objects.equals(input.getReferenceMaterialNo(), indirect.reference().materialNo())
        && Objects.equals(input.getReferenceFingerprint(), indirect.reference().fingerprint())
        && Objects.equals(input.getFileSha256(), direct.upload() == null ? null : direct.upload().fileSha256());
  }

  private static boolean netLoss(TechnicalDataNetLossSaveRequest input, TechnicalDataSupplementContent.NetLoss saved) {
    if (saved == null || !input.getUnknownFields().isEmpty() || !Objects.equals(input.getEntryMode(), saved.entryMode())) return false;
    if ("REFERENCE".equals(saved.entryMode())) return saved.reference() != null && input.getPercent() == null
        && Objects.equals(input.getReferenceMaterialNo(), saved.reference().source().materialNo())
        && Objects.equals(input.getReferenceFingerprint(), saved.reference().fingerprint());
    return input.getReferenceMaterialNo() == null && input.getReferenceFingerprint() == null
        && number(TechnicalDataNetLossRules.ratio(input.getPercent()), saved.rate());
  }

  private static boolean prices(TechnicalDataPriceSaveRequest input, TechnicalDataSupplementContent.Prices saved,
      TechnicalDataVersionContentCodec codec) {
    // requirementsFingerprint 是页面重新查询的缺口标识，不是用户填写值；依赖一致性由复用策略另行核验。
    return saved != null && input.getUnknownFields().isEmpty() && rows(input.getItems(), saved.items(), (row, old) ->
        row.getUnknownFields().isEmpty() && Objects.equals(row.getItemKey(), old.itemKey())
            && Objects.equals(row.getEntryMode(), old.entryMode()) && number(row.getUnitPrice(), old.unitPrice())
            && text(row.getNotes(), old.notes()) && codec.sameSourceEvidence(row.getParameters(), old.parameters())
            && ("REFERENCE".equals(old.entryMode()) ? old.reference() != null
                && Objects.equals(row.getReferenceId(), old.reference().id())
                && Objects.equals(row.getReferenceFingerprint(), old.reference().fingerprint())
                && (row.getFormula() == null || text(row.getFormula(), old.formula()))
                : row.getReferenceId() == null && row.getReferenceFingerprint() == null && text(row.getFormula(), old.formula())));
  }

  private static boolean manufacturing(TechnicalDataManufacturingSaveRequest input, TechnicalDataSupplementContent.Manufacturing saved) {
    return saved != null && saved.evidence() != null && input.getUnknownFields().isEmpty()
        && Objects.equals(input.getSourceVersionId(), saved.evidence().drawingSourceVersionId())
        && Objects.equals(input.getSourceFingerprint(), saved.evidence().sourceFingerprint())
        && rows(input.getItems(), saved.items(), (row, old) -> row.getUnknownFields().isEmpty()
            && Objects.equals(Objects.toString(row.getParentSourceNodeId(), null), old.parentSourceNodeId())
            && text(row.getRawMaterialNo(), old.rawMaterialNo()) && number(row.getNetLengthMm(), old.netLengthMm())
            && number(row.getGrossWeightKg(), old.grossWeightKg()));
  }

  private static boolean profile(TechnicalDataProfileUpdateRequest input, QuoteTechDataVersion version,
      TechnicalDataVersionContentCodec codec) {
    var material = codec.plateMaterial(version);
    return input.getUnknownFields().isEmpty() && Objects.equals(input.getProductProperty(), version.getProductProperty())
        && codec.sameSourceEvidence(TechnicalDataProductFeeRules.parse(input.getHasAdditionalFees(), input.getUnitToolingFee(),
            input.getUnitMouldFee(), input.getUnitCertificationFee(), input.getUnitDepreciationFee()), codec.productFees(version))
        && text(input.getPlateMaterial(), material == null || material.material() == null ? null : material.material().value());
  }

  private static boolean number(BigDecimal left, BigDecimal right) { return left == null ? right == null : right != null && left.compareTo(right) == 0; }
  private static boolean text(String left, String right) { return Objects.equals(blank(left), blank(right)); }
  private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
  private static <A,B> boolean rows(List<A> inputs, List<B> saved, BiPredicate<A,B> match) {
    if (inputs == null || saved == null || inputs.size() != saved.size()) return false;
    for (int i = 0; i < inputs.size(); i++) if (inputs.get(i) == null || !match.test(inputs.get(i), saved.get(i))) return false;
    return true;
  }
}
