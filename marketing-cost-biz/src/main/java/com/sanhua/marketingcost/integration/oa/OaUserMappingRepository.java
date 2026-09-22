package com.sanhua.marketingcost.integration.oa;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OaUserMappingRepository {
  public record Mapping(String externalUserId, Long userId) {}
  private final JdbcTemplate jdbc;

  public OaUserMappingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  public List<Mapping> list(OaPeer peer) {
    return jdbc.query("""
        SELECT external_user_id,user_id FROM lp_oa_user_mapping
        WHERE source_system=? AND environment=? ORDER BY external_user_id
        """, (row, index) -> new Mapping(row.getString(1), row.getLong(2)),
        peer.sourceSystem(), peer.environment());
  }

  public Mapping findExternal(OaPeer peer, String externalId) {
    var rows = jdbc.query("""
        SELECT external_user_id,user_id FROM lp_oa_user_mapping
        WHERE source_system=? AND environment=? AND external_user_id=?
        """, (row, index) -> new Mapping(row.getString(1), row.getLong(2)),
        peer.sourceSystem(), peer.environment(), externalId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public Mapping findInternal(OaPeer peer, long userId) {
    var rows = jdbc.query("""
        SELECT external_user_id,user_id FROM lp_oa_user_mapping
        WHERE source_system=? AND environment=? AND user_id=?
        """, (row, index) -> new Mapping(row.getString(1), row.getLong(2)),
        peer.sourceSystem(), peer.environment(), userId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  public void insert(OaPeer peer, String externalId, long userId, long actorId) {
    jdbc.update("""
        INSERT INTO lp_oa_user_mapping(source_system,environment,external_user_id,user_id,updated_by)
        VALUES(?,?,?,?,?)
        """, peer.sourceSystem(), peer.environment(), externalId, userId, actorId);
  }
}
