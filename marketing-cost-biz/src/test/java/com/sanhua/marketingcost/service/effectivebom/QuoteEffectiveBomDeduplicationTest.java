package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.request;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.service;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.variant;
import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class QuoteEffectiveBomDeduplicationTest {

  @Test
  void twoIdenticalResultsReuseOneBuild() {
    EffectiveBomPersistenceTestSupport.InMemoryRepository repository =
        new EffectiveBomPersistenceTestSupport.InMemoryRepository();
    QuoteEffectiveBomPersistenceServiceImpl service = service(repository);

    QuoteEffectiveBomPersistenceResult first =
        service.persistCurrentVariant(request(11L, variant()));
    QuoteEffectiveBomPersistenceResult second =
        service.persistCurrentVariant(request(12L, variant()));

    assertThat(second.reused()).isTrue();
    assertThat(second.buildBatchId()).isEqualTo(first.buildBatchId());
    assertThat(repository.buildCount()).isEqualTo(1);
    assertThat(repository.insertCalls()).isEqualTo(1);
  }

  @Test
  void oneHundredCustomerSnapshotsShareOneIdenticalBuild() {
    EffectiveBomPersistenceTestSupport.InMemoryRepository repository =
        new EffectiveBomPersistenceTestSupport.InMemoryRepository();
    QuoteEffectiveBomPersistenceServiceImpl service = service(repository);

    for (long snapshotId = 1; snapshotId <= 100; snapshotId++) {
      service.persistCurrentVariant(request(snapshotId, variant()));
    }

    assertThat(repository.buildCount()).isEqualTo(1);
    assertThat(repository.insertCalls()).isEqualTo(1);
  }

  @Test
  void standardAndAlternativeResultsCreateTwoBuilds() {
    EffectiveBomPersistenceTestSupport.InMemoryRepository repository =
        new EffectiveBomPersistenceTestSupport.InMemoryRepository();
    QuoteEffectiveBomPersistenceServiceImpl service = service(repository);

    QuoteEffectiveBomPersistenceResult standard =
        service.persistCurrentVariant(request(11L, variant()));
    QuoteEffectiveBomPersistenceResult alternative =
        service.persistCurrentVariant(
            request(
                12L,
                variant(
                    "ALT-MATERIAL",
                    "BOX",
                    new BigDecimal("2.500"),
                    QuoteMaterialShape.OUTSOURCE,
                    "POLICY-FP-1",
                    "SUP-EXT",
                    new BigDecimal("0.6000"),
                    false)));

    assertThat(alternative.buildBatchId())
        .isNotEqualTo(standard.buildBatchId());
    assertThat(repository.buildCount()).isEqualTo(2);
  }

  @Test
  void simulatedHashCollisionDoesNotReuseDifferentCanonicalNodes() {
    EffectiveBomPersistenceTestSupport.InMemoryRepository repository =
        new EffectiveBomPersistenceTestSupport.InMemoryRepository();
    EffectiveBomVariantHasher collidingHasher = ignored -> "f".repeat(64);
    AtomicInteger sequence = new AtomicInteger();
    QuoteEffectiveBomPersistenceServiceImpl service =
        service(
            repository,
            collidingHasher,
            () -> "COLLISION-BUILD-" + sequence.incrementAndGet());

    QuoteEffectiveBomPersistenceResult first =
        service.persistCurrentVariant(request(11L, variant()));
    QuoteEffectiveBomPersistenceResult changed =
        service.persistCurrentVariant(
            request(
                12L,
                variant(
                    "STANDARD-MATERIAL",
                    "BOX",
                    new BigDecimal("9.999"),
                    QuoteMaterialShape.OUTSOURCE,
                    "POLICY-FP-1",
                    "SUP-EXT",
                    new BigDecimal("0.6000"),
                    false)));

    assertThat(changed.reused()).isFalse();
    assertThat(changed.buildBatchId()).isNotEqualTo(first.buildBatchId());
    assertThat(repository.buildCount()).isEqualTo(2);
  }
}
