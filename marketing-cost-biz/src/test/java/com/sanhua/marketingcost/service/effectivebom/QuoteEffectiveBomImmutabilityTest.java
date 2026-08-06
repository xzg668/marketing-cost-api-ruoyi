package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.request;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.service;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.variant;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.math.BigDecimal;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class QuoteEffectiveBomImmutabilityTest {

  @Test
  void changedConfirmationCreatesNewBuildWithoutUpdatingOldNodes() {
    EffectiveBomPersistenceTestSupport.InMemoryRepository repository =
        new EffectiveBomPersistenceTestSupport.InMemoryRepository();
    QuoteEffectiveBomPersistenceServiceImpl service = service(repository);
    QuoteEffectiveBomPersistenceResult first =
        service.persistConfirmed(request(11L, variant()));
    String originalChildQty =
        repository.nodes(first.buildBatchId()).get(1).getQtyPerTop().toPlainString();

    QuoteEffectiveBomPersistenceResult changed =
        service.persistConfirmed(
            request(
                12L,
                variant(
                    "STANDARD-MATERIAL",
                    "BOX",
                    new BigDecimal("3.000"),
                    QuoteMaterialShape.OUTSOURCE,
                    "POLICY-FP-1",
                    "SUP-EXT",
                    new BigDecimal("0.6000"),
                    false)));

    assertThat(changed.buildBatchId()).isNotEqualTo(first.buildBatchId());
    assertThat(repository.nodes(first.buildBatchId()).get(1).getQtyPerTop())
        .hasToString(originalChildQty);
    assertThat(repository.buildCount()).isEqualTo(2);
  }

  @Test
  void repositoryContractOnlyAllowsDeletingUnreferencedProvisionalBuilds() {
    assertThat(
            Arrays.stream(QuoteEffectiveBomRepository.class.getMethods())
                .map(method -> method.getName().toLowerCase())
                .filter(
                    name ->
                        name.startsWith("update")
                            || name.startsWith("replace")
                            || (name.startsWith("delete")
                                && !"deleteunreferencedbyoriginmonthlysnapshotid"
                                    .equals(name))))
        .isEmpty();
  }
}
