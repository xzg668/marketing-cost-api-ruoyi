package com.sanhua.marketingcost.integration.oa.directory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.integration.oa.auth.OaAuthenticationException;
import org.junit.jupiter.api.Test;

class OaPersonDirectoryServiceTest {
  @Test
  void failedAuthenticationCannotReplaceExistingDirectory() {
    OaPersonDirectoryGateway gateway = mock(OaPersonDirectoryGateway.class);
    OaPersonDirectoryRepository repository = mock(OaPersonDirectoryRepository.class);
    when(gateway.load()).thenThrow(new OaAuthenticationException("JK-02 返回失败状态"));

    assertThatThrownBy(() -> new OaPersonDirectoryService(gateway, repository).synchronize())
        .isInstanceOf(OaAuthenticationException.class);
    verifyNoInteractions(repository);
  }
}
