package com.sanhua.marketingcost.integration.oa;

import com.sanhua.marketingcost.service.ingest.QuoteIngestService;
import com.sanhua.marketingcost.service.ingest.QuoteNormalizeService;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 锁定来源单据，只创建正式需求；已接收的需求不可通过 I01 覆盖。 */
@Service
public class OaQuoteIngestHandler {
  private record Binding(long id, Long oaFormId, String canonicalHash) {}
  public record Result(long oaFormId, String oaNo, List<Map<String, Object>> items) {}
  private final JdbcTemplate jdbc;
  private final QuoteIngestService ingest;
  private final QuoteNormalizeService normalize;
  private final OaMessageCodec codec;

  public OaQuoteIngestHandler(JdbcTemplate jdbc, QuoteIngestService ingest,
      QuoteNormalizeService normalize, OaMessageCodec codec) {
    this.jdbc = jdbc; this.ingest = ingest; this.normalize = normalize; this.codec = codec;
  }

  public Result handle(OaMessageRepository.Message message, OaQuoteRequest quote) {
    var request = quote.request();
    var normalized = normalize.normalize(request);
    if (!normalized.getErrors().isEmpty()) {
      var error = normalized.getErrors().getFirst();
      throw OaIntegrationException.invalid("QUOTE_VALIDATION_FAILED", error.getFieldPath() + ": " + error.getMessage());
    }
    if (!quote.businessUnit().equals(normalized.getHeader().getBusinessUnitType())) {
      throw OaIntegrationException.invalid("CLASSIFICATION_CONFLICT", "来源业务单元与流程分类不一致或分类尚未确认");
    }
    String hash = codec.canonicalHash(quote);
    Binding binding = lockBinding(message.peer(), quote.documentId());
    if (binding.oaFormId() != null) {
      if (!hash.equals(binding.canonicalHash())) {
        throw OaIntegrationException.conflict("QUOTE_ALREADY_RECEIVED", "该OA单据已接收，不能覆盖原报价需求");
      }
      return result(binding.oaFormId(), quote);
    }
    List<Long> sameNumber = jdbc.queryForList("SELECT id FROM oa_form WHERE oa_no=? FOR UPDATE",
        Long.class, request.getOaNo());
    if (!sameNumber.isEmpty()) {
      throw OaIntegrationException.conflict("QUOTE_NUMBER_OWNED", "该流程编号已存在，不能关联其他OA单据或覆盖原报价需求");
    }
    // 接入日志的唯一键用内部报文 ID，避免不同 OA/环境使用相同短请求号而冲突。
    request.setRequestId("OA_MESSAGE:" + message.id());
    request.setIdempotencyKey("OA_MESSAGE:" + message.id());
    request.setRawPayload(Map.of("integrationMessageId", message.id()));
    var response = ingest.ingestFromOa(request);
    if (!response.isAccepted() || response.getOaFormId() == null) {
      throw OaIntegrationException.invalid("QUOTE_INGEST_REJECTED", "报价接入未通过业务校验，请按请求编号查看处理记录");
    }
    jdbc.update("""
        UPDATE lp_oa_quote_document SET oa_form_id=?,source_version=1,canonical_hash=?,latest_message_id=?,updated_at=NOW(3)
        WHERE id=?
        """, response.getOaFormId(), hash, message.id(), binding.id());
    // source_version=1 是既有核算/审批关联所需的内部需求基线，不接受外部更新。
    return result(response.getOaFormId(), quote);
  }

  private Binding lockBinding(OaPeer peer, String documentId) {
    jdbc.update("""
        INSERT INTO lp_oa_quote_document(source_system,environment,external_document_id)
        VALUES(?,?,?) ON DUPLICATE KEY UPDATE id=id
        """, peer.sourceSystem(), peer.environment(), documentId);
    return jdbc.queryForObject("""
        SELECT id,oa_form_id,canonical_hash FROM lp_oa_quote_document
        WHERE source_system=? AND environment=? AND external_document_id=? FOR UPDATE
        """, (row, n) -> new Binding(row.getLong("id"), row.getObject("oa_form_id", Long.class),
        row.getString("canonical_hash")), peer.sourceSystem(), peer.environment(), documentId);
  }

  private Result result(Long formId, OaQuoteRequest quote) {
    if (formId == null) throw OaIntegrationException.conflict("SOURCE_BINDING_MISSING", "来源单据缺少内部关联");
    if (!Integer.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM oa_form WHERE id=? AND deleted=0", Integer.class, formId))) {
      throw OaIntegrationException.conflict("SOURCE_BINDING_MISSING", "来源绑定的报价单已不可用");
    }
    List<Map<String, Object>> items = jdbc.queryForList("""
        SELECT id AS oaFormItemId,external_line_id AS externalLineId,material_no AS materialNo
        FROM oa_form_item WHERE oa_form_id=? AND COALESCE(deleted,0)=0 ORDER BY seq,id
        """, formId);
    if (items.isEmpty()) throw OaIntegrationException.conflict("SOURCE_BINDING_MISSING", "来源单据产品行关联已失效");
    return new Result(formId, quote.request().getOaNo(), items);
  }
}
