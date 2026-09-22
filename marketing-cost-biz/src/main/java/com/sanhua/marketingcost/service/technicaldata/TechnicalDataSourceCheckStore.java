package com.sanhua.marketingcost.service.technicaldata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSourceCheckResponse;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 检查证据随现有核算工作区保存；OA 推送需求不会创建该检查或技术任务。 */
@Repository
public class TechnicalDataSourceCheckStore {
  private final JdbcTemplate jdbc;
  private final OaMessageCodec codec;
  private final ObjectMapper json;

  public TechnicalDataSourceCheckStore(JdbcTemplate jdbc, OaMessageCodec codec, ObjectMapper json) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.json = json;
  }

  public void save(Long workspaceId, TechnicalDataSourceCheckResponse check, Map<String, Object> evidence) {
    if (jdbc.update("""
        UPDATE lp_quote_costing_workspace SET technical_check_json=?,technical_check_fingerprint=? WHERE id=?
        """, codec.write(Map.of("check", check, "evidence", evidence)), check.fingerprint(), workspaceId) != 1) {
      throw new IllegalStateException("核算工作区已变化，请重新检查资料");
    }
  }

  public TechnicalDataSourceCheckResponse read(QuoteCostingWorkspace workspace) {
    if (workspace == null || workspace.getTechnicalCheckJson() == null) {
      throw new IllegalStateException("未找到一键核算保存的资料检查结果");
    }
    try {
      var root = json.readTree(workspace.getTechnicalCheckJson());
      var checkNode = root == null ? null : root.get("check");
      if (checkNode == null || checkNode.isNull()) {
        throw new IllegalStateException("一键核算保存的资料检查结果不完整");
      }
      var check = json.treeToValue(checkNode, TechnicalDataSourceCheckResponse.class);
      if (!Objects.equals(workspace.getTechnicalCheckFingerprint(), check.fingerprint())) {
        throw new IllegalStateException("一键核算保存的资料检查版本不一致");
      }
      return check;
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("一键核算保存的资料检查结果无法读取", exception);
    }
  }
}
