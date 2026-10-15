package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.mapper.PriceVariableBindingMapper;
import org.junit.jupiter.api.Test;

class PriceLinkedStandardBindingServiceImplTest {
  private final PriceVariableBindingMapper mapper = mock(PriceVariableBindingMapper.class);
  private final PriceLinkedStandardBindingServiceImpl service =
      new PriceLinkedStandardBindingServiceImpl(mapper);

  @Test
  void firstRuleCreatesOnlyItsActualBinding() {
    var request = request();
    when(mapper.findPreviousFactorIdentity(request, "材料含税价格")).thenReturn(null);
    assertThat(service.checkAgainstPreviousRule(request))
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.getAction()).isEqualTo("CREATE_BINDING");
              assertThat(value.getStandardBindingId()).isNull();
              assertThat(value.getNewFactorIdentityId()).isEqualTo(15L);
            });
    verify(mapper).findPreviousFactorIdentity(request, "材料含税价格");
    verifyNoMoreInteractions(mapper);
  }

  @Test
  void identicalPreviousBindingIsAcceptedWithoutWritingHistory() {
    var request = request();
    when(mapper.findPreviousFactorIdentity(request, "材料含税价格")).thenReturn(15L);
    assertThat(service.checkAgainstPreviousRule(request).getFirst().getAction())
        .isEqualTo("CONSISTENT");
    verify(mapper).findPreviousFactorIdentity(request, "材料含税价格");
    verifyNoMoreInteractions(mapper);
  }

  @Test
  void changedIdentityRequiresManualConfirmation() {
    var request = request();
    when(mapper.findPreviousFactorIdentity(request, "材料含税价格")).thenReturn(16L);
    var result = service.checkAgainstPreviousRule(request).getFirst();
    assertThat(result.getAction()).isEqualTo("CONFLICT");
    assertThat(result.getOldFactorIdentityId()).isEqualTo(16L);
    assertThat(result.getNewFactorIdentityId()).isEqualTo(15L);
  }

  @Test
  void missingFormulaNeverUsesPreviousBindingAsFallback() {
    var request = request();
    request.setFormulaAvailable(false);
    assertThat(service.checkAgainstPreviousRule(request).getFirst().getAction())
        .isEqualTo("FAILED");
    verifyNoInteractions(mapper);
  }

  @Test
  void incompleteScopeCannotSilentlySelectAnotherOrganization() {
    var request = request();
    request.setOrganizationCode(null);
    assertThat(service.checkAgainstPreviousRule(request).getFirst().getAction())
        .isEqualTo("FAILED");
    verifyNoInteractions(mapper);
  }

  @Test
  void invalidCandidateCannotWriteBinding() {
    var request = request();
    request.getCandidates().getFirst().setFactorIdentityId(null);
    assertThat(service.checkAgainstPreviousRule(request).getFirst().getAction())
        .isEqualTo("FAILED");
    verifyNoInteractions(mapper);
  }

  private StandardBindingCheckRequest request() {
    var request = new StandardBindingCheckRequest();
    request.setBusinessUnitType("COMMERCIAL");
    request.setOrganizationCode("210");
    request.setLinkedItemId(99L);
    request.setPricingMonth("2026-10");
    request.setMaterialCode("M001");
    request.setSupplierCode("S1");
    request.setUnit("KG");
    request.setFormulaAvailable(true);
    var candidate = new BindingCandidate();
    candidate.setMaterialCode("M001");
    candidate.setTokenName("材料含税价格");
    candidate.setFactorIdentityId(15L);
    request.getCandidates().add(candidate);
    return request;
  }
}
