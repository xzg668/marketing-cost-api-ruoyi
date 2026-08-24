package com.sanhua.marketingcost.service.effectivebom;

import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.hasher;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.crossOrganizationVariant;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.request;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.service;
import static com.sanhua.marketingcost.service.effectivebom.EffectiveBomPersistenceTestSupport.variant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import com.sanhua.marketingcost.mapper.QuoteEffectiveBomNodeMapper;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.BatchResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class QuoteEffectiveBomPersistenceTest {

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, QuoteEffectiveBomNode.class);
  }

  @Test
  void confirmationPersistsCompleteNodeSnapshot() {
    EffectiveBomPersistenceTestSupport.InMemoryRepository repository =
        new EffectiveBomPersistenceTestSupport.InMemoryRepository();

    QuoteEffectiveBomPersistenceResult result =
        service(repository).persistCurrentVariant(request(11L, variant()));

    assertThat(result.reused()).isFalse();
    assertThat(result.nodeCount()).isEqualTo(2);
    assertThat(result.variantHash()).hasSize(64);
    List<QuoteEffectiveBomNode> rows = repository.nodes(result.buildBatchId());
    assertThat(rows).hasSize(2).allSatisfy(row -> {
      assertThat(row.getBuildBatchId()).isEqualTo(result.buildBatchId());
      assertThat(row.getOriginMonthlySnapshotId()).isEqualTo(11L);
      assertThat(row.getEffectiveVariantHash()).isEqualTo(result.variantHash());
      assertThat(row.getTopProductCode()).isEqualTo("P");
      assertThat(row.getCostPeriodMonth()).isEqualTo("2026-08");
      assertThat(row.getPriceOrgCode()).isEqualTo("210");
      assertThat(row.getCreatedBy()).isEqualTo(9527L);
      assertThat(row.getCreatedAt()).isNotNull();
    });
  }

  @Test
  void persistsCommercialOrganizationOnlyForExpandedPlateNode() {
    EffectiveBomPersistenceTestSupport.InMemoryRepository repository =
        new EffectiveBomPersistenceTestSupport.InMemoryRepository();

    QuoteEffectiveBomPersistenceResult result =
        service(repository).persistCurrentVariant(request(11L, crossOrganizationVariant()));

    assertThat(repository.nodes(result.buildBatchId()))
        .extracting(QuoteEffectiveBomNode::getPriceOrgCode)
        .containsExactly("220", "210");
  }

  @Test
  void previewHashDoesNotWriteFormalNodes() {
    EffectiveBomPersistenceTestSupport.InMemoryRepository repository =
        new EffectiveBomPersistenceTestSupport.InMemoryRepository();

    assertThat(hasher().hash(variant())).hasSize(64);
    assertThat(repository.insertCalls()).isZero();
    assertThat(repository.buildCount()).isZero();
  }

  @Test
  void blockedCandidateIsRejectedBeforeRepositoryWrite() {
    EffectiveBomPersistenceTestSupport.InMemoryRepository repository =
        new EffectiveBomPersistenceTestSupport.InMemoryRepository();
    EffectiveBomVariantInput valid = variant();
    EffectiveBomVariantInput blocked =
        new EffectiveBomVariantInput(
            valid.costPeriodMonth(),
            valid.sourceBomBatchId(),
            valid.priceOrgCode(),
            valid.topProductCode(),
            valid.packageMethod(),
            valid.selectedMaterialCodeByGroupKey(),
            new EffectiveBomBuildResult(
                valid.buildResult().nodes(),
                valid.buildResult().exclusions(),
                List.of(
                    new EffectiveBomBlockIssue(
                        "BOM_GAP", "M", "/P/M/", "缺BOM")),
                List.of()));

    assertThatThrownBy(
            () -> service(repository).persistCurrentVariant(request(11L, blocked)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(repository.insertCalls()).isZero();
  }

  @Test
  void mapperRepositoryUsesOneBatchAndNeverHidesWriteFailure() {
    QuoteEffectiveBomNodeMapper mapper = mock(QuoteEffectiveBomNodeMapper.class);
    QuoteEffectiveBomRepositoryImpl repository =
        new QuoteEffectiveBomRepositoryImpl(mapper);
    QuoteEffectiveBomNode first = new QuoteEffectiveBomNode();
    first.setNodeKey("N1");
    QuoteEffectiveBomNode second = new QuoteEffectiveBomNode();
    second.setNodeKey("N2");
    BatchResult failedBatch = mock(BatchResult.class);
    when(failedBatch.getUpdateCounts())
        .thenReturn(new int[] {1, Statement.EXECUTE_FAILED});
    when(
            mapper.insert(
                org.mockito.ArgumentMatchers
                    .<Collection<QuoteEffectiveBomNode>>any()))
        .thenReturn(List.of(failedBatch));

    assertThatThrownBy(() -> repository.insertAll(List.of(first, second)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("批量写入失败");
    verify(mapper)
        .insert(
            org.mockito.ArgumentMatchers
                .<Collection<QuoteEffectiveBomNode>>any());
  }

  @Test
  void mapperRepositoryAcceptsCompleteBatchResult() {
    QuoteEffectiveBomNodeMapper mapper = mock(QuoteEffectiveBomNodeMapper.class);
    QuoteEffectiveBomRepositoryImpl repository =
        new QuoteEffectiveBomRepositoryImpl(mapper);
    QuoteEffectiveBomNode first = new QuoteEffectiveBomNode();
    first.setNodeKey("N1");
    QuoteEffectiveBomNode second = new QuoteEffectiveBomNode();
    second.setNodeKey("N2");
    BatchResult successfulBatch = mock(BatchResult.class);
    when(successfulBatch.getUpdateCounts()).thenReturn(new int[] {1, 1});
    when(
            mapper.insert(
                org.mockito.ArgumentMatchers
                    .<Collection<QuoteEffectiveBomNode>>any()))
        .thenReturn(List.of(successfulBatch));

    assertThatCode(() -> repository.insertAll(List.of(first, second)))
        .doesNotThrowAnyException();
    verify(mapper)
        .insert(
            org.mockito.ArgumentMatchers
                .<Collection<QuoteEffectiveBomNode>>any());
  }

}
