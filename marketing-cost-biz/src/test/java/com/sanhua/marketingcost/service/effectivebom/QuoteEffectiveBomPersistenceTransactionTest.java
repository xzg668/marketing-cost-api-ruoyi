package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.request;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.service;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.variant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class QuoteEffectiveBomPersistenceTransactionTest {

  @Test
  void confirmedPersistenceDeclaresRequiredRollbackForAnyException()
      throws Exception {
    Method method =
        QuoteEffectiveBomPersistenceServiceImpl.class.getMethod(
            "persistConfirmed", QuoteEffectiveBomPersistenceRequest.class);
    Transactional transactional = method.getAnnotation(Transactional.class);

    assertThat(transactional).isNotNull();
    assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
    assertThat(transactional.rollbackFor()).contains(Exception.class);
  }

  @Test
  void insertionFailurePropagatesToTransactionBoundary() {
    QuoteEffectiveBomRepository repository =
        mock(QuoteEffectiveBomRepository.class);
    when(repository.findBuildBatchIdsByVariantHash(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of());
    when(repository.existsBuildBatchId(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(false);
    org.mockito.Mockito.doThrow(new IllegalStateException("第二个节点写入失败"))
        .when(repository)
        .insertAll(anyList());

    assertThatThrownBy(
            () -> service(repository, ignored -> "a".repeat(64), () -> "BUILD-X")
                .persistConfirmed(request(11L, variant())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("写入失败");

    verify(repository).insertAll(anyList());
  }

  @Test
  void generatedBuildIdCollisionIsRejectedWithoutOverwrite() {
    QuoteEffectiveBomRepository repository =
        mock(QuoteEffectiveBomRepository.class);
    when(repository.findBuildBatchIdsByVariantHash(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(List.of());
    when(repository.existsBuildBatchId("BUILD-X")).thenReturn(true);

    assertThatThrownBy(
            () -> service(repository, ignored -> "a".repeat(64), () -> "BUILD-X")
                .persistConfirmed(request(11L, variant())))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("拒绝覆盖");

    verify(repository, never()).insertAll(anyList());
  }
}
