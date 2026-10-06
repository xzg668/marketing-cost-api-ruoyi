package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.entity.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TechnicalDataParticipantVersionsTest {
  private final QuoteTechnicalDataRepository repository = mock(QuoteTechnicalDataRepository.class);
  private final TechnicalDataVersionContentCodec codec = mock(TechnicalDataVersionContentCodec.class);
  private final TechnicalDataDependencies dependencies = mock(TechnicalDataDependencies.class);
  private final TechnicalDataParticipantVersions service = new TechnicalDataParticipantVersions(
      repository, codec, dependencies, null, null, null, null, null, null, null, null);

  @Test void oneCompleteSubmissionIsItsOwnEffectiveVersionWithoutAnotherCopy() {
    var product = product();
    var submitted = submitted();
    when(repository.lockModules(10L)).thenReturn(List.of(module("SALARY", "SUBMITTED"), module("PACKAGE", "SUBMITTED")));
    when(repository.lockVersion(44L)).thenReturn(Optional.of(submitted));
    when(codec.fingerprint(eq(submitted), any(), any(), any(), any())).thenReturn("verified");
    when(repository.updateProductPointers(eq(product), eq(0), any())).thenReturn(1);

    assertThat(service.activate(product, 101L)).isSameAs(submitted);
    assertThat(product.getEffectiveVersionId()).isEqualTo(44L);
    assertThat(product.getEffectiveReviewRound()).isEqualTo(2);
    assertThat(product.getProductStatus()).isEqualTo("SUBMITTED");
    verify(repository, never()).insertVersion(any());
    verify(repository, never()).lockVersion(11L);
    verify(repository, never()).transitionVersion(any(), any(), any(), anyInt(), any(), any(), anyLong(), any());
  }

  @Test void incompleteOtherModuleCannotMakeTheFirstSubmissionEffective() {
    var product = product();
    when(repository.lockModules(10L)).thenReturn(List.of(module("SALARY", "SUBMITTED"), module("PACKAGE", "PENDING")));
    assertThat(service.activate(product, 101L)).isNull();
    assertThat(product.getEffectiveVersionId()).isNull();
    verify(repository, never()).insertVersion(any());
  }

  @Test void changedFrozenContentCannotBeReused() {
    var product = product();
    var submitted = submitted();
    when(repository.lockModules(10L)).thenReturn(List.of(module("SALARY", "SUBMITTED")));
    when(repository.lockVersion(44L)).thenReturn(Optional.of(submitted));
    when(codec.fingerprint(eq(submitted), any(), any(), any(), any())).thenReturn("changed");
    assertThatThrownBy(() -> service.activate(product, 101L)).isInstanceOf(TechnicalDataTaskException.class)
        .hasMessageContaining("指纹");
    verify(repository, never()).updateProductPointers(any(), anyInt(), any());
  }

  private QuoteTechProduct product() {
    var product = new QuoteTechProduct();
    product.setId(10L); product.setRowVersion(0); product.setActiveFlag(1); product.setCurrentEditVersionId(11L);
    return product;
  }

  private QuoteTechDataVersion submitted() {
    var version = new QuoteTechDataVersion();
    version.setId(44L); version.setProductId(10L); version.setVersionNo(2);
    version.setVersionStatus("SUBMITTED"); version.setContentFingerprint("verified");
    return version;
  }

  private QuoteTechModule module(String type, String status) {
    var module = new QuoteTechModule();
    module.setModuleType(type); module.setRequiredFlag(1); module.setModuleStatus(status);
    module.setCurrentVersionId(44L); module.setSourceAvailability("MISSING");
    return module;
  }
}
