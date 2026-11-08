package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.integration.oa.*;
import com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectoryProperties;
import com.sanhua.marketingcost.integration.oa.directory.OaPersonDirectoryRepository;
import com.sanhua.marketingcost.integration.oa.workflow.OaTechnicalBatchRepository;
import com.sanhua.marketingcost.service.SysUserService;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 原单据、当前人员和固定页面地址的可信来源；客户端不能指定 OA 操作人。 */
@Component
public class TechnicalDataOaContext {

  public record Document(long formId, String requestId, String processCode, OaPeer peer, String rejectToNodeId) {}

  private final JdbcTemplate jdbc;
  private final SysUserService users;
  private final OaPersonDirectoryRepository directory;
  private final OaPersonDirectoryProperties directoryProperties;
  private final OaIntegrationProperties properties;
  private final OaMessageRepository messages;
  private final OaMessageCodec codec;
  private final OaTechnicalBatchRepository batches;

  public TechnicalDataOaContext(
    JdbcTemplate jdbc,
    SysUserService users,
    OaPersonDirectoryRepository directory,
    OaPersonDirectoryProperties directoryProperties,
    OaIntegrationProperties properties,
    OaMessageRepository messages,
    OaMessageCodec codec,
    OaTechnicalBatchRepository batches
  ) {
    this.jdbc = jdbc;
    this.users = users;
    this.directory = directory;
    this.directoryProperties = directoryProperties;
    this.properties = properties;
    this.messages = messages;
    this.codec = codec;
    this.batches = batches;
  }

  public void lockActor(TechnicalDataActor actor) {
    if (actor == null || actor.userId() == null) throw invalid(
      "当前会话不能办理单据提交"
    );
    jdbc.queryForObject(
      "SELECT user_id FROM sys_user WHERE user_id=? FOR UPDATE",
      Long.class,
      actor.userId()
    );
  }

  public Document document(long formId) {
    var rows = jdbc.queryForList(
      """
      SELECT d.external_document_id,d.source_system,d.environment,d.reject_to_node_id,f.process_code,f.business_unit_type
      FROM oa_form f JOIN lp_oa_quote_document d ON d.oa_form_id=f.id
      WHERE f.id=? AND f.deleted=0 FOR UPDATE
      """,
      formId
    );
    if (rows.size() != 1) throw invalid("此需求未关联 OA 推送的 requestId，不能向 OA 分派或提交");
    var row = rows.getFirst();
    String source = (String) row.get("source_system"),
      environment = (String) row.get("environment");
    if (
      !directoryProperties.getSourceSystem().equals(source) ||
      !directoryProperties.getEnvironment().equals(environment)
    ) {
      throw invalid("原单据的 OA 来源或环境与当前人员目录不一致");
    }
    return new Document(
      formId,
      (String) row.get("external_document_id"),
      (String) row.get("process_code"),
      new OaPeer(source, environment, Set.of((String) row.get("business_unit_type"))),
      (String) row.get("reject_to_node_id")
    );
  }

  public String operatorEmployeeNo(long userId) {
    var user = users.findIdentityById(userId);
    if (user == null || !"0".equals(user.getStatus()) || !"0".equals(user.getDelFlag())) throw invalid(
      "当前账号不存在或已停用"
    );
    String employee = user.getEmployeeNo();
    if (employee == null || employee.isBlank()) throw invalid("当前账号未维护工号，请先完善");
    return employee.trim();
  }

  /** FI-SR-005 按原单业务类型退回；已接收但未传节点的历史需求同样适用。 */
  public String rejectionNode(Document document) {
    if (!"FI-SR-005".equals(document.processCode())) return document.rejectToNodeId();
    var types = jdbc.queryForList(
        """
        SELECT e.field_value FROM lp_oa_form_header_extra_field e
        JOIN oa_form f ON f.id=e.oa_form_id AND f.business_unit_type=e.business_unit_type
        WHERE e.oa_form_id=? AND e.field_code='ywlx'
        """, String.class, document.formId());
    if (types.size() != 1 || types.getFirst() == null || types.getFirst().isBlank()) {
      throw invalid("FI-SR-005 缺少原报价需求单的业务类型，不能确定退回节点");
    }
    return switch (types.getFirst().trim()) {
      case "批量品", "衍生品" -> "1289678984441668287";
      case "新品" -> "1334220389403738114";
      default -> throw invalid("FI-SR-005 业务类型须为新品、衍生品或批量品，不能确定退回节点");
    };
  }

  public String technicianEmployeeNo(long userId) {
    var identity = directory.findActiveBySystemUserId(userId);
    if (identity == null) throw invalid("所选技术员未关联有效 OA 人员工号：" + userId);
    return identity.employeeNo();
  }

  public String workbenchUrl(long formId) {
    String base = properties.getOutbound().getFrontendBaseUrl();
    try {
      URI uri = URI.create(base);
      if (
        !Set.of("http", "https").contains(uri.getScheme()) ||
        uri.getHost() == null ||
        uri.getUserInfo() != null ||
        uri.getQuery() != null ||
        uri.getFragment() != null
      ) throw new IllegalArgumentException();
    } catch (RuntimeException exception) {
      throw invalid("请配置报价系统的访问地址 integration.oa.outbound.frontend-base-url");
    }
    return base.replaceAll("/+$", "") + "/collaboration/technical-data/forms/" + formId;
  }

  /** 子消息仅作产品/人员关联，原生报文只保存在批次中且只发送一次。 */
  public long linkMessage(
    Document document,
    String batchId,
    OaMessageCodec.InterfaceType type,
    String childKey,
    Map<String, Object> scope
  ) {
    String raw = codec.write(
      Map.of(
        "schemaVersion",
        1,
        "sourceSystem",
        document.peer().sourceSystem(),
        "environment",
        document.peer().environment(),
        "requestId",
        batchId + ":" + childKey,
        "occurredAt",
        OffsetDateTime.now().toString(),
        "payload",
        scope
      )
    );
    long id = messages.enqueue(document.peer(), type, codec.decode(raw, document.peer(), type)).id();
    batches.linkMessage(batchId, id);
    return id;
  }

  private IllegalArgumentException invalid(String message) {
    return new IllegalArgumentException(message);
  }
}
