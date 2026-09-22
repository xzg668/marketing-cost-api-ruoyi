package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** I01 正式需求仅保存一次；原文、报价数据及回执在同一事务提交，超时重发不重复建单。 */
@Service
public class OaQuotationService {
  private final OaMessageRepository messages;
  private final OaMessageCodec codec;
  private final OaQuotationRequestMapper mapper;
  private final OaQuoteIngestHandler handler;

  public OaQuotationService(
      OaMessageRepository messages,
      OaMessageCodec codec,
      OaQuotationRequestMapper mapper,
      OaQuoteIngestHandler handler) {
    this.messages = messages;
    this.codec = codec;
    this.mapper = mapper;
    this.handler = handler;
  }

  @Transactional
  public JsonNode receive(OaPeer peer, String raw) {
    JsonNode root = codec.readQuotation(raw);
    var mapped = mapper.map(root, peer);
    var envelope =
        new OaMessageCodec.Envelope(
            3,
            mapped.requestId(),
            OffsetDateTime.now().toString(),
            root,
            raw,
            codec.canonicalHash(root));
    OaMessageRepository.Message message;
    try {
      message = messages.receive(peer, OaMessageCodec.InterfaceType.QUOTE_REQUEST, envelope);
    } catch (OaIntegrationException ex) {
      if ("REQUEST_CONTENT_CONFLICT".equals(ex.code())) {
        throw OaIntegrationException.conflict("IDEMPOTENCY_CONFLICT", "该OA单据已接收，重发内容与原需求不同，不能覆盖；请核对requestId和原报文");
      }
      throw ex;
    }
    if (message.schemaVersion() != 3 || !"QUOTE_REQUEST".equals(message.interfaceType()))
      throw OaIntegrationException.conflict("IDEMPOTENCY_CONFLICT", "requestId已用于其他接口");
    if (message.resultJson() != null && "PROCESSED".equals(message.status()))
      return codec.read(message.resultJson());
    // receive 的唯一键写入已持有行锁；事务提交前后台接收箱看不到尚未完成的新记录。
    var result = handler.handle(message, mapped.quote());
    Map<String, String> ids =
        result.items().stream()
            .collect(
                Collectors.toMap(
                    item -> item.get("externalLineId").toString(),
                    item -> item.get("oaFormItemId").toString()));
    List<OaQuotationResponse.ItemMapping> mappings = new ArrayList<>();
    for (var line : mapped.lines())
      mappings.add(
          new OaQuotationResponse.ItemMapping(
              line.tableKey(), line.rowId(), ids.get(line.externalLineId())));
    boolean blocked = !mapped.issues().isEmpty();
    var response =
        new OaQuotationResponse(
            "0",
            blocked ? "需求已保存，存在核算输入缺口" : "需求已保存，待执行核算资料检查",
            new OaQuotationResponse.Data(
                "SUCCEEDED",
                Long.toString(result.oaFormId()),
                "/ingest/quote-requests/"
                    + URLEncoder.encode(result.oaNo(), StandardCharsets.UTF_8).replace("+", "%20"),
                mappings,
                blocked ? "BLOCKED" : "PENDING",
                mapped.issues(),
                null));
    String json = codec.write(response);
    messages.completeQuotation(message.id(), json);
    return codec.read(json);
  }
}
