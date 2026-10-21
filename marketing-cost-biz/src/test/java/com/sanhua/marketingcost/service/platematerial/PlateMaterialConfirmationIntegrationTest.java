package com.sanhua.marketingcost.service.platematerial;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Tag("integration")
class PlateMaterialConfirmationIntegrationTest extends BomMapperTestBase {
  @Autowired PlateMaterialMappingService mappings;
  @Autowired PlatformTransactionManager transactions;

  @Test void confirmedSelectionCreatesOneReusableMappingAndSameValueIsUnchanged() {
    String model = "PMM-CONFIRM-" + UUID.randomUUID();
    var first = mappings.registerConfirmedSelection("COMMERCIAL", " " + model + " ", "水水316L", 101);
    assertThat(first.getNormalizedModel()).isEqualTo(model);
    assertThat(first.getPlateMaterial()).isEqualTo("水水316L");
    assertThat(first.getCreatedBy()).isEqualTo(101);
    assertThat(first.getSourceType()).isEqualTo("MANUAL");
    assertThat(first.getRowVersion()).isZero();
    var repeated = mappings.registerConfirmedSelection("COMMERCIAL", model, "水水316L", 102);
    assertThat(repeated).usingRecursiveComparison().isEqualTo(first);
    assertThat(mappings.list("COMMERCIAL", model, null, 1, 20).records()).hasSize(1);
  }

  @Test void existingDifferentMaterialIsNeverOverwritten() {
    String model = "PMM-CONFLICT-" + UUID.randomUUID();
    var original = mappings.create("COMMERCIAL", model, "水氟SUS304", 1);
    assertThatThrownBy(() -> mappings.registerConfirmedSelection("COMMERCIAL", model, "氟氟316L", 101))
        .isInstanceOf(ConcurrencyFailureException.class).hasMessageContaining("不一致");
    assertThat(mappings.list("COMMERCIAL", model, null, 1, 20).records().getFirst())
        .usingRecursiveComparison().isEqualTo(original);
  }

  @Test void businessUnitAndCompleteCaseSensitiveModelRemainIndependent() {
    String model = "PMM-IDENTITY-" + UUID.randomUUID();
    mappings.registerConfirmedSelection("COMMERCIAL", model, "水氟SUS304", 101);
    mappings.registerConfirmedSelection("HOUSEHOLD", model, "氟氟316L", 102);
    mappings.registerConfirmedSelection("COMMERCIAL", model.toLowerCase(), "水水316L", 103);
    assertThat(mappings.list("COMMERCIAL", model, null, 1, 20).records().getFirst().getPlateMaterial())
        .isEqualTo("水氟SUS304");
    assertThat(mappings.list("HOUSEHOLD", model, null, 1, 20).records().getFirst().getPlateMaterial())
        .isEqualTo("氟氟316L");
    assertThat(mappings.list("COMMERCIAL", model.toLowerCase(), null, 1, 20).records().getFirst().getPlateMaterial())
        .isEqualTo("水水316L");
    assertThat(mappings.list("COMMERCIAL", model + "-L", null, 1, 20).records()).isEmpty();
  }

  @Test void enclosingWorkflowRollbackAlsoRollsBackNewMapping() {
    String model = "PMM-ROLLBACK-" + UUID.randomUUID();
    var transaction = new TransactionTemplate(transactions);
    assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
      mappings.registerConfirmedSelection("COMMERCIAL", model, "水水316L", 101);
      throw new IllegalStateException("workflow failed after registration");
    })).hasMessage("workflow failed after registration");
    assertThat(mappings.list("COMMERCIAL", model, null, 1, 20).records()).isEmpty();
  }

  @Test void concurrentDifferentConfirmationsCreateOneRowAndReportTheConflict() throws Exception {
    String model = "PMM-RACE-" + UUID.randomUUID();
    var barrier = new CyclicBarrier(2);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var attempts = List.of("水氟SUS304", "氟氟316L").stream().map(material -> executor.submit(() -> {
        barrier.await(10, TimeUnit.SECONDS);
        try {
          return mappings.registerConfirmedSelection("COMMERCIAL", model, material, 101).getPlateMaterial();
        } catch (ConcurrencyFailureException conflict) {
          return "CONFLICT";
        }
      })).toList();
      String first = attempts.get(0).get(15, TimeUnit.SECONDS);
      String second = attempts.get(1).get(15, TimeUnit.SECONDS);
      var rows = mappings.list("COMMERCIAL", model, null, 1, 20).records();
      assertThat(rows).hasSize(1);
      assertThat(List.of(first, second)).containsExactlyInAnyOrder("CONFLICT", rows.getFirst().getPlateMaterial());
      assertThat(rows.getFirst().getRowVersion()).isZero();
    }
  }

  @Test void invalidMaterialIdentityOrActorCannotCreateMapping() {
    String model = "PMM-INVALID-" + UUID.randomUUID();
    assertThatThrownBy(() -> mappings.registerConfirmedSelection("COMMERCIAL", model, "水水316", 101))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> mappings.registerConfirmedSelection("OTHER", model, "水水316L", 101))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> mappings.registerConfirmedSelection("COMMERCIAL", " ", "水水316L", 101))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> mappings.registerConfirmedSelection("COMMERCIAL", model, "水水316L", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(mappings.list("COMMERCIAL", model, null, 1, 20).records()).isEmpty();
  }
}
