package com.sanhua.marketingcost.service.platematerial;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.service.technicaldata.TechnicalDataVersionContentCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlateMaterialContractTest {
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
  private final TechnicalDataVersionContentCodec codec = new TechnicalDataVersionContentCodec(mapper);

  @Test void sixConfirmedLabelsRetainApplicationAndSteelGradeInOrder() throws Exception {
    var labels = List.of("水氟SUS304", "水氟316L", "氟氟SUS304", "氟氟316L", "水水SUS304", "水水316L");
    assertThat(PlateMaterial.options()).containsExactlyElementsOf(labels);
    for (String label : labels) {
      assertThat(mapper.writeValueAsString(PlateMaterial.parse(label))).isEqualTo("\"" + label + "\"");
      assertThat(mapper.readValue("\"" + label + "\"", PlateMaterial.class).value()).isEqualTo(label);
    }
    for (String invalid : List.of("", "水水316", "SUS304", "水水sus304", "水水316L ")) {
      assertThatThrownBy(() -> PlateMaterial.parse(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test void normalizationOnlyRemovesBoundarySpacesAndNeverCollapsesModelDifferences() {
    assertThat(PlateMaterialModel.normalize(" B12-8H-V20 ")).isEqualTo("B12-8H-V20");
    assertThat(PlateMaterialModel.normalize("b12-8H-V20")).isEqualTo("b12-8H-V20");
    assertThat(PlateMaterialModel.normalize("B12 -8H-V20")).isEqualTo("B12 -8H-V20");
    assertThat(PlateMaterialModel.normalize("B12-8H-V20-L")).isEqualTo("B12-8H-V20-L");
    assertThat(PlateMaterialModel.normalize("\tB12-8H-V20")).startsWith("\t");
    assertThat(PlateMaterialModel.normalize("   ")).isNull();
    assertThat(PlateMaterialModel.normalize(null)).isNull();
  }

  @Test void snapshotRoundTripsAndDistinguishesNotCapturedFromHidden() {
    var version = version(2);
    assertThat(codec.plateMaterial(version)).isNull();
    assertThat(codec.plateMaterialJson(null)).isNull();
    var hidden = new PlateMaterialSnapshot(PlateMaterialMode.HIDDEN, null,
        new PlateMaterialSnapshot.Source("COMMERCIAL", null, "COMMERCIAL", null, null, List.of()), "hidden-revision");
    version.setPlateMaterialJson(codec.plateMaterialJson(hidden));
    assertThat(codec.plateMaterial(version)).isEqualTo(hidden);
    assertThat(codec.supplementContent(version).plateMaterial()).isEqualTo(hidden);
    var locked = locked();
    version.setPlateMaterialJson(codec.plateMaterialJson(locked));
    assertThat(codec.plateMaterial(version)).isEqualTo(locked);
    assertThat(codec.versionContentJson(version, List.of(), List.of(), List.of(), List.of()))
        .contains("\"plateMaterial\"", "水水316L", "B12-8H-V20");
  }

  @Test void savedApplicableValuesRequireMaterialAndTypedProvenance() {
    var source = new PlateMaterialSnapshot.Source("COMMERCIAL", "MODEL", "PLATE", null, null, List.of(7L));
    assertThatThrownBy(() -> new PlateMaterialSnapshot(PlateMaterialMode.MANUAL_REQUIRED, null, source, "r"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PlateMaterialSnapshot(PlateMaterialMode.HIDDEN, PlateMaterial.WATER_WATER_316L, source, "r"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PlateMaterialSnapshot(PlateMaterialMode.CONFIG_LOCKED, PlateMaterial.WATER_WATER_316L, source, "r"))
        .isInstanceOf(IllegalArgumentException.class);
    var manual = new PlateMaterialSnapshot(PlateMaterialMode.MANUAL_REQUIRED,
        PlateMaterial.WATER_WATER_316L, source, "r");
    var version = version(2); version.setPlateMaterialJson(codec.plateMaterialJson(manual));
    assertThat(codec.plateMaterial(version)).isEqualTo(manual);
    version.setContentSchemaVersion(1);
    assertThatThrownBy(() -> codec.versionContentJson(version, List.of(), List.of(), List.of(), List.of()))
        .hasMessageContaining("旧版本结构");
  }

  @Test void historicalSchemaOneAndTwoKeepExactlyThePreChangeJsonAndFingerprint() throws Exception {
    try (var stream = getClass().getResourceAsStream("/fixtures/plate-material-legacy-codec.json")) {
      assertThat(stream).isNotNull();
      for (var baseline : mapper.readTree(stream).path("cases")) {
        var version = version(baseline.path("schema").asInt());
        assertThat(codec.versionContentJson(version, List.of(), List.of(), List.of(), List.of()))
            .isEqualTo(baseline.path("json").asText());
        assertThat(codec.fingerprint(version, List.of(), List.of(), List.of(), List.of()))
            .isEqualTo(baseline.path("sha256").asText());
      }
    }
  }

  private QuoteTechDataVersion version(int schema) {
    var version = new QuoteTechDataVersion();
    version.setContentSchemaVersion(schema);
    version.setProductModel("HISTORICAL-MODEL");
    version.setProductProperty("标准品");
    version.setNewProductFlag(0);
    if (schema == 2) version.setProductFeesJson("""
        {"includesNewToolingMouldCertificationFee":false,"unitToolingFee":0.25,
         "unitMouldFee":null,"unitCertificationFee":null,"currency":"CNY"}
        """);
    return version;
  }

  static PlateMaterialSnapshot locked() {
    return new PlateMaterialSnapshot(PlateMaterialMode.CONFIG_LOCKED, PlateMaterial.WATER_WATER_316L,
        new PlateMaterialSnapshot.Source("COMMERCIAL", "B12-8H-V20", "COMMERCIAL", 10L, 0, List.of()), "config-revision");
  }
}
