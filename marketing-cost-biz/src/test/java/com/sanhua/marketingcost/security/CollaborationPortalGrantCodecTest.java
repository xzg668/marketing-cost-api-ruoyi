package com.sanhua.marketingcost.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CollaborationPortalGrantCodecTest {
  private final CollaborationPortalGrantCodec codec =
      new CollaborationPortalGrantCodec(new ObjectMapper());

  @Test
  void roundTripKeepsTaskAndAllBusinessModules() {
    String json = codec.encode(88L, Set.of(
        CollaborationPortalModule.BOM,
        CollaborationPortalModule.PRICE,
        CollaborationPortalModule.CMS));

    CollaborationPortalGrant grant = codec.decode(json);

    assertThat(grant.collaborationId()).isEqualTo(88L);
    assertThat(grant.modules()).containsExactlyInAnyOrder(
        CollaborationPortalModule.BOM,
        CollaborationPortalModule.PRICE,
        CollaborationPortalModule.CMS);
  }

  @Test
  void missingTaskOrModuleIsRejectedInsteadOfCreatingAnUnlimitedToken() {
    assertThatThrownBy(() -> codec.encode(null, Set.of(CollaborationPortalModule.BOM)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.encode(88L, Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
