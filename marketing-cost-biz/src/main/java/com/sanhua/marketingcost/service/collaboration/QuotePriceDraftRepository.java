package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.entity.QuotePriceDraft;
import com.sanhua.marketingcost.entity.QuotePriceDraftField;
import java.util.List;
import java.util.Optional;

public interface QuotePriceDraftRepository {

  QuotePriceDraft saveDraft(QuotePriceDraft draft);

  List<QuotePriceDraftField> saveFields(List<QuotePriceDraftField> fields);

  Optional<QuotePriceDraft> findById(Long id, CollaborationScope scope);

  Optional<QuotePriceDraft> findByNo(String draftNo, CollaborationScope scope);

  List<QuotePriceDraft> findByProductTask(Long productTaskId, CollaborationScope scope);

  List<QuotePriceDraftField> findFields(Long priceDraftId, CollaborationScope scope);

  List<QuotePriceDraft> findByPublishedSource(
      String sourceTable, Long sourceId, CollaborationScope scope);

  QuotePriceDraft transitionStatus(
      Long id,
      Integer expectedVersion,
      String expectedStatus,
      String nextStatus,
      CollaborationScope scope,
      CollaborationActor actor);

  QuotePriceDraft updateEditable(
      QuotePriceDraft draft, Integer expectedVersion, CollaborationScope scope);

  QuotePriceDraft changeReference(
      QuotePriceDraft draft, Integer expectedVersion, CollaborationScope scope);

  QuotePriceDraft updateValidation(
      Long id, Integer expectedVersion, String validationStatus, String validationMessage,
      CollaborationScope scope, CollaborationActor actor);

  List<QuotePriceDraftField> replaceEditableFields(
      Long priceDraftId, List<QuotePriceDraftField> fields, CollaborationScope scope);
}
