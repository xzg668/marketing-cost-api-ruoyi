package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPackageReferenceResponse.*;
import com.sanhua.marketingcost.entity.QuoteTechPackageItem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 将客户端仍持有的已保存节点映射到已核实的当前节点，不信任客户端提供的业务来源。 */
record TechnicalDataPackageReferenceBinding(Source source, Long inputParentId, String inputFingerprint,
                                          Map<Long, Child> children) {
  static TechnicalDataPackageReferenceBinding current(Source source) {
    var children = new HashMap<Long, Child>();
    source.children().forEach(child -> children.put(child.sourceNodeId(), child));
    return new TechnicalDataPackageReferenceBinding(source, source.evidence().parentNodeId(),
        source.evidence().fingerprint(), Map.copyOf(children));
  }

  static TechnicalDataPackageReferenceBinding saved(Evidence saved, Source current,
      List<QuoteTechPackageItem> items, TechnicalDataVersionContentCodec codec) {
    var children = new HashMap<Long, Child>();
    for (var item : items) {
      var evidence = codec.packageEvidence(item);
      if (evidence == null || !"U9_BOM".equals(evidence.kind()) || evidence.sourceNodeId() == null
          || !Objects.equals(evidence.parentNodeId(), saved.parentNodeId())
          || !Objects.equals(evidence.sourceFingerprint(), saved.fingerprint())
          || !Objects.equals(evidence.sourceTopProductCode(), saved.topProductCode())
          || !Objects.equals(evidence.sourceVersion(), saved.buildBatchId())) throw changed();
      var matches = current.children().stream().filter(child ->
          Objects.equals(child.path(), evidence.sourcePath())
              && Objects.equals(child.materialNo(), item.getComponentMaterialNo())
              && Objects.equals(child.model(), evidence.model())
              && Objects.equals(child.name(), item.getComponentName())
              && Objects.equals(child.specification(), item.getComponentSpec())
              && Objects.equals(child.unit(), item.getOriginalUnit())
              && Objects.equals(child.unit(), evidence.sourceUnit())
              && child.quantity() != null && evidence.sourceQuantity() != null
              && child.quantity().compareTo(evidence.sourceQuantity()) == 0).toList();
      if (matches.size() != 1 || children.put(evidence.sourceNodeId(), matches.getFirst()) != null) throw changed();
    }
    return new TechnicalDataPackageReferenceBinding(current, saved.parentNodeId(), saved.fingerprint(), Map.copyOf(children));
  }

  Child requireChild(Long parentId, Long childId, String fingerprint) {
    if (!Objects.equals(parentId, inputParentId) || !Objects.equals(fingerprint, inputFingerprint)
        || !children.containsKey(childId)) throw changed();
    return children.get(childId);
  }

  private static TechnicalDataTaskException changed() {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT,
        "包装子件来源已变化或与已保存资料不一致，请重新选择");
  }
}
