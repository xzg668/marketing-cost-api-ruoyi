package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.dto.ingest.QuoteIngestResponse;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.service.ingest.QuoteIngestService;
import com.sanhua.marketingcost.service.ingest.QuoteNormalizeService;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** I01 以原流程绑定报价单，在同一事务内保存当前需求和原文指针。 */
@Service
public class OaQuoteIngestHandler {
  record Binding(long oaFormId, long latestMessageId) {}
  public record Result(long oaFormId, String oaNo, List<Map<String, Object>> items) {}
  private final JdbcTemplate jdbc;
  private final QuoteIngestService ingest;
  private final QuoteNormalizeService normalize;
  private final OaMessageCodec codec;
  private final OaFormMapper forms;

  public OaQuoteIngestHandler(JdbcTemplate jdbc, QuoteIngestService ingest,
      QuoteNormalizeService normalize, OaMessageCodec codec, OaFormMapper forms) {
    this.jdbc = jdbc; this.ingest = ingest; this.normalize = normalize; this.codec = codec;
    this.forms = forms;
  }

  Binding binding(OaPeer peer, String documentId) {
    return jdbc.query("""
        SELECT oa_form_id,latest_message_id FROM lp_oa_quote_document
        WHERE source_system=? AND environment=? AND external_document_id=? AND oa_form_id IS NOT NULL
        """, (row, n) -> new Binding(row.getLong("oa_form_id"), row.getLong("latest_message_id")),
        peer.sourceSystem(), peer.environment(), documentId).stream().findFirst().orElse(null);
  }

  Result handle(OaMessageRepository.Message message, OaQuotationRequestMapper.Mapped mapped,
      Binding binding, JsonNode previous, JsonNode current) {
    var quote = mapped.quote();
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
    // 接入日志的唯一键用内部报文 ID，避免不同 OA/环境使用相同短请求号而冲突。
    request.setRequestId("OA_MESSAGE:" + message.id());
    request.setIdempotencyKey("OA_MESSAGE:" + message.id());
    request.setRawPayload(Map.of("integrationMessageId", message.id()));
    if (binding != null) return update(message, mapped, binding, previous, current, hash);

    if (!jdbc.queryForList("SELECT id FROM oa_form WHERE oa_no=?", Long.class, request.getOaNo()).isEmpty())
      throw quotationNumberOwned();
    QuoteIngestResponse response;
    try {
      response = ingest.ingestFromOa(request);
    } catch (DuplicateKeyException failure) {
      // 另一个来源刚抢先提交同一编号，仍返回业务冲突；其他唯一约束错误不能冒充占号。
      for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
        if (cause instanceof SQLException sql && sql.getErrorCode() == 1062
            && sql.getMessage() != null && sql.getMessage().contains("uk_oa_form_oa_no")) {
          throw quotationNumberOwned();
        }
      }
      throw failure;
    }
    if (!response.isAccepted() || response.getOaFormId() == null) {
      throw OaIntegrationException.invalid("QUOTE_INGEST_REJECTED", "报价接入未通过业务校验，请按请求编号查看处理记录");
    }
    jdbc.update("""
        INSERT INTO lp_oa_quote_document(source_system,environment,external_document_id,
          oa_form_id,source_version,canonical_hash,latest_message_id,reject_to_node_id)
        VALUES (?,?,?,?,1,?,?,?)
        ON DUPLICATE KEY UPDATE oa_form_id=VALUES(oa_form_id),source_version=1,
          canonical_hash=VALUES(canonical_hash),latest_message_id=VALUES(latest_message_id),
          reject_to_node_id=VALUES(reject_to_node_id),updated_at=NOW(3)
        """, message.peer().sourceSystem(), message.peer().environment(), quote.documentId(),
        response.getOaFormId(), hash, message.id(), quote.rejectToNodeId());
    return result(response.getOaFormId(), quote);
  }

  private Result update(OaMessageRepository.Message message, OaQuotationRequestMapper.Mapped mapped,
      Binding binding, JsonNode previous, JsonNode current, String hash) {
    long formId = binding.oaFormId();
    jdbc.queryForList("SELECT id FROM oa_form WHERE id=? FOR UPDATE", Long.class, formId);
    OaForm form = forms.selectById(formId);
    if (form == null)
      throw OaIntegrationException.conflict("SOURCE_BINDING_MISSING", "来源绑定的报价单已不可用");
    var quote = mapped.quote();
    if (!form.getOaNo().equals(quote.request().getOaNo())
        || !form.getProcessCode().equals(current.path("processCode").asText())) {
      throw OaIntegrationException.conflict("QUOTE_IDENTITY_CONFLICT", "原OA流程的单号或表单模板与已接收需求不一致");
    }

    boolean headerChanged = !previous.path("mainData").equals(current.path("mainData"));
    if (headerChanged || !previous.path("detailData").equals(current.path("detailData"))) {
      var priorRows = new HashMap<String, JsonNode>();
      previous.path("detailData").forEach(row -> priorRows.put(row.path("rowId").asText(), fields(row)));
      var currentRows = new HashMap<String, JsonNode>();
      current.path("detailData").forEach(row -> currentRows.put(row.path("rowId").asText(), fields(row)));
      var changedLines = new HashSet<String>();
      for (var line : mapped.lines()) {
        if (!currentRows.get(line.rowId()).equals(priorRows.get(line.rowId()))) {
          changedLines.add(line.externalLineId());
        }
      }
      var response = ingest.updateFromOa(quote.request(), form, headerChanged, changedLines);
      if (!response.isAccepted())
        throw OaIntegrationException.invalid("QUOTE_INGEST_REJECTED", "报价需求修订未通过业务校验");
    }
    // 工作流仍是同一轮 OA 流程。需求原文的修订身份由最新消息和接入流水保存。
    jdbc.update("""
        UPDATE lp_oa_quote_document SET canonical_hash=?,latest_message_id=?,
          reject_to_node_id=COALESCE(?,reject_to_node_id),updated_at=NOW(3)
        WHERE oa_form_id=?
        """, hash, message.id(), quote.rejectToNodeId(), formId);
    return result(formId, quote);
  }

  private JsonNode fields(JsonNode row) {
    return row.has("fields") ? row.path("fields") : row.path("plpmxb");
  }

  private OaIntegrationException quotationNumberOwned() {
    return OaIntegrationException.conflict("QUOTE_NUMBER_OWNED", "该流程编号已存在，不能关联其他OA单据或覆盖原报价需求");
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
