package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataPlateMaterialResponse;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import com.sanhua.marketingcost.service.platematerial.PlateMaterial;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialMode;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialResolver;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialResolver.Context;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialResolver.Decision;
import com.sanhua.marketingcost.service.platematerial.PlateMaterialSnapshot;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Coordinates PROFILE snapshots with the shared resolver; never writes public configuration. */
@Component
public class TechnicalDataPlateMaterialProfile {
  private final PlateMaterialResolver resolver;
  private final TechnicalDataVersionContentCodec codec;
  private final ObjectMapper json;

  public TechnicalDataPlateMaterialProfile(PlateMaterialResolver resolver,
      TechnicalDataVersionContentCodec codec, ObjectMapper json) {
    this.resolver = resolver;
    this.codec = codec;
    this.json = json;
  }

  public Map<Long, Decision> current(List<QuoteTechProduct> products) {
    Map<Long, Context> contexts = new LinkedHashMap<>();
    products.forEach(product -> contexts.put(product.getId(), context(product)));
    var decisions = resolver.resolveAll(List.copyOf(contexts.values()));
    Map<Long, Decision> results = new LinkedHashMap<>();
    contexts.forEach((id, context) -> results.put(id, decisions.get(context)));
    return results;
  }

  public PlateMaterialSnapshot save(QuoteTechProduct product, String value, String expectedRevision) {
    if (expectedRevision == null || expectedRevision.isBlank()) {
      throw invalid("请先读取板片材质判定，再保存产品信息");
    }
    Decision current = resolver.resolve(context(product));
    if (!current.revision().equals(expectedRevision)) throw changed();
    return selected(current, value);
  }

  /** Frozen/submitted history is validated by its immutable codec/fingerprint, never today's configuration. */
  public List<String> validateCurrent(QuoteTechProduct product, QuoteTechDataVersion draft) {
    if (draft == null || !"DRAFT".equals(draft.getVersionStatus()) || codec.schemaVersion(draft) != 2) return List.of();
    PlateMaterialSnapshot saved = codec.plateMaterial(draft);
    if (saved == null) return List.of("板片材质尚未确认，请重新保存产品信息");
    Decision current = resolver.resolve(context(product));
    if (!current.revision().equals(saved.revision())) return List.of(changed().getMessage());
    try {
      var expected = selected(current, saved.material() == null ? null : saved.material().value());
      return expected.equals(saved) ? List.of() : List.of("板片材质或来源与当前判定不一致，请重新保存产品信息");
    } catch (TechnicalDataTaskException failure) {
      return List.of(failure.getMessage());
    }
  }

  public TechnicalDataPlateMaterialResponse view(QuoteTechDataVersion version, Decision current) {
    PlateMaterialSnapshot saved = codec.plateMaterial(version);
    if ((version != null && !"DRAFT".equals(version.getVersionStatus())) || current == null) {
      return saved == null ? null : new TechnicalDataPlateMaterialResponse(saved.mode(), saved.material(),
          saved.source(), saved.revision(), saved, false, true);
    }
    PlateMaterial value = switch (current.mode()) {
      case CONFIG_LOCKED -> current.value();
      case MANUAL_REQUIRED -> saved == null ? null : saved.material();
      case HIDDEN -> null;
    };
    return new TechnicalDataPlateMaterialResponse(current.mode(), value, current.source(), current.revision(),
        saved, saved != null && !Objects.equals(saved.revision(), current.revision()), false);
  }

  public TechnicalDataPlateMaterialResponse savedView(QuoteTechDataVersion version) {
    PlateMaterialSnapshot saved = codec.plateMaterial(version);
    return saved == null ? null : new TechnicalDataPlateMaterialResponse(saved.mode(), saved.material(),
        saved.source(), saved.revision(), saved, false, !"DRAFT".equals(version.getVersionStatus()));
  }

  private PlateMaterialSnapshot selected(Decision current, String requested) {
    PlateMaterial material;
    switch (current.mode()) {
      case CONFIG_LOCKED -> {
        if (requested != null && !current.value().value().equals(requested)) {
          throw invalid("板片材质由型号对照表自动带出，不允许修改");
        }
        material = current.value();
      }
      case MANUAL_REQUIRED -> {
        try { material = PlateMaterial.parse(requested); }
        catch (IllegalArgumentException failure) { throw invalid("请选择六种合法板片材质之一（必填）"); }
      }
      case HIDDEN -> {
        if (requested != null && !requested.isBlank()) throw invalid("当前产品不适用板片材质，不能填写此项");
        material = null;
      }
      default -> throw new IllegalStateException("未知板片材质判定");
    }
    return new PlateMaterialSnapshot(current.mode(), material, current.source(), current.revision());
  }

  private Context context(QuoteTechProduct product) {
    if (!Integer.valueOf(2).equals(product.getContentSchemaVersion())) {
      throw invalid("历史产品不重新判定板片材质");
    }
    try {
      JsonNode source = json.readTree(product.getSourceSnapshotJson());
      return new Context(text(source, "businessUnitType"), product.getSourceModel(),
          text(source, "materialOrganizationCode"));
    } catch (JsonProcessingException | IllegalArgumentException failure) {
      throw invalid("产品来源上下文无效，请先复查：" + failure.getMessage());
    }
  }

  private static String text(JsonNode node, String field) {
    return node != null && node.hasNonNull(field) ? node.path(field).asText() : null;
  }

  private static TechnicalDataTaskException changed() {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT,
        "板片材质来源已变化，请刷新判定后重新保存产品信息");
  }

  private static TechnicalDataTaskException invalid(String message) {
    return new TechnicalDataTaskException(TechnicalDataTaskErrorCode.INVALID_REQUEST, message);
  }
}
