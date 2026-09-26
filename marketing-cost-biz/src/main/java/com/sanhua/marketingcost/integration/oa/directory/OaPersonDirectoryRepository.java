package com.sanhua.marketingcost.integration.oa.directory;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 人员目录的唯一持久化边界；快照替换、系统身份关联和可搜索状态在同一事务内完成。 */
@Repository
public class OaPersonDirectoryRepository {
  private static final String MANAGED_USER_REMARK = "OA人员目录自动创建，仅用于技术协作身份";
  private static final String JOINER = "；";

  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwordEncoder;
  private final OaPersonDirectoryProperties properties;

  public OaPersonDirectoryRepository(
      JdbcTemplate jdbc,
      PasswordEncoder passwordEncoder,
      OaPersonDirectoryProperties properties) {
    this.jdbc = jdbc;
    this.passwordEncoder = passwordEncoder;
    this.properties = properties;
  }

  /**
   * 只有网关完整产生快照后才进入本事务。任何用户关联或目录写入失败都会整体回滚，旧批次继续可用。
   */
  @Transactional
  public SyncResult replace(OaDirectorySnapshot snapshot) {
    if (snapshot == null || snapshot.people() == null || snapshot.people().isEmpty()) {
      throw new OaPersonDirectoryException("OA 人员目录快照为空，拒绝覆盖上次数据");
    }
    String batchId = UUID.randomUUID().toString();
    LocalDateTime now = LocalDateTime.now();
    long collaboratorRoleId = collaboratorRoleId();
    int selectable = 0;
    for (OaDirectoryPerson person : snapshot.people()) {
      validatePerson(person);
      UserLink link = resolveSystemUser(person, collaboratorRoleId, now);
      boolean canSelect = "normal".equalsIgnoreCase(person.employmentStatus()) && link.active();
      upsert(person, link.userId(), canSelect, batchId, now);
      if (canSelect) {
        selectable++;
      }
    }
    jdbc.update("""
        UPDATE lp_oa_person_directory
           SET active_flag=0,selectable_flag=0,updated_at=?
         WHERE source_system=? AND environment=? AND sync_batch_id<>? AND active_flag=1
        """, Timestamp.valueOf(now), properties.getSourceSystem(), properties.getEnvironment(), batchId);
    disableStaleManagedUsers(now);
    return new SyncResult(batchId, snapshot.people().size(), selectable, snapshot.missingDepartments());
  }

  public List<OaPersonDirectoryOption> search(String keyword, int requestedLimit) {
    int limit = Math.max(1, Math.min(requestedLimit, 100));
    String normalized = keyword == null ? "" : keyword.trim();
    List<Object> parameters = new ArrayList<>();
    parameters.add(properties.getSourceSystem());
    parameters.add(properties.getEnvironment());
    String keywordClause = "";
    if (StringUtils.hasText(normalized)) {
      String pattern = "%" + escapeLike(normalized) + "%";
      keywordClause = """
          AND (d.employee_no LIKE ? ESCAPE '\\\\'
            OR d.person_name LIKE ? ESCAPE '\\\\'
            OR d.position_name LIKE ? ESCAPE '\\\\'
            OR d.actual_department_paths LIKE ? ESCAPE '\\\\'
            OR d.target_department_paths LIKE ? ESCAPE '\\\\')
          """;
      for (int index = 0; index < 5; index++) {
        parameters.add(pattern);
      }
    }
    parameters.add(limit);
    return jdbc.query("""
        SELECT d.system_user_id,d.employee_no,d.person_name,d.position_name,
               d.actual_department_paths,d.target_department_paths
          FROM lp_oa_person_directory d
          JOIN sys_user u ON u.user_id=d.system_user_id AND u.status='0' AND u.del_flag='0'
         WHERE d.source_system=? AND d.environment=?
           AND d.active_flag=1 AND d.selectable_flag=1
        """ + keywordClause + """
         ORDER BY d.target_department_paths,d.person_name,d.employee_no
         LIMIT ?
        """, (row, rowNum) -> new OaPersonDirectoryOption(
            row.getLong("system_user_id"),
            row.getString("employee_no"),
            row.getString("person_name"),
            row.getString("position_name"),
            row.getString("actual_department_paths"),
            row.getString("target_department_paths")), parameters.toArray());
  }

  public OaPersonDirectoryIdentity findActiveBySystemUserId(long userId) {
    return findIdentity("d.system_user_id=?", userId);
  }

  public OaPersonDirectoryIdentity findActiveByEmployeeNo(String employeeNo) {
    return findIdentity("d.employee_no=?", employeeNo);
  }

  private OaPersonDirectoryIdentity findIdentity(String predicate, Object value) {
    var rows = jdbc.query("""
        SELECT d.system_user_id,d.employee_no
          FROM lp_oa_person_directory d
          JOIN sys_user u ON u.user_id=d.system_user_id AND u.status='0' AND u.del_flag='0'
         WHERE d.source_system=? AND d.environment=? AND d.active_flag=1 AND d.selectable_flag=1
           AND
        """ + predicate + " LIMIT 2",
        (row, rowNum) -> new OaPersonDirectoryIdentity(
            row.getLong("system_user_id"), row.getString("employee_no")),
        properties.getSourceSystem(), properties.getEnvironment(), value);
    if (rows.size() > 1) {
      throw new OaPersonDirectoryException("OA 人员目录身份不唯一");
    }
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private long collaboratorRoleId() {
    List<Long> roleIds = jdbc.query("""
        SELECT role_id FROM sys_role
         WHERE LOWER(role_key)='technical_collaborator' AND status='0' AND del_flag='0'
         ORDER BY role_id
        """, (row, rowNum) -> row.getLong(1));
    if (roleIds.size() != 1) {
      throw new OaPersonDirectoryException("系统中必须存在且仅存在一个有效的技术协作者角色");
    }
    return roleIds.getFirst();
  }

  private UserLink resolveSystemUser(
      OaDirectoryPerson person, long collaboratorRoleId, LocalDateTime now) {
    Long linkedUserId = linkedUserId(person.oaUserId());
    if (linkedUserId != null) {
      UserLink linked = inspectUser(linkedUserId);
      if (linked != null && "COMMERCIAL".equals(linked.businessUnitType())
          && (linked.managed() || linked.active() && hasTechnicalPermission(linked.userId()))) {
        if (linked.managed()) {
          reactivateManagedUser(linked.userId(), now);
          ensureRole(linked.userId(), collaboratorRoleId);
        }
        updateManagedUser(linked, person, now);
        return new UserLink(linked.userId(), linked.businessUnitType(), true, linked.managed());
      }
    }

    UserLink existing = inspectUserByName(person.employeeNo());
    if (existing != null && existing.active() && "COMMERCIAL".equals(existing.businessUnitType())
        && hasTechnicalPermission(existing.userId())) {
      updateManagedUser(existing, person, now);
      return existing;
    }

    String managedName = managedUsername(person.employeeNo(), person.oaUserId());
    UserLink managed = inspectUserByName(managedName);
    if (managed == null) {
      managed = createManagedUser(managedName, person, now);
    } else if (!managed.managed()) {
      throw new OaPersonDirectoryException("OA 目录系统账号命名冲突：" + managedName);
    } else if (!"COMMERCIAL".equals(managed.businessUnitType())) {
      throw new OaPersonDirectoryException("OA 目录系统账号事业部不正确：" + managedName);
    } else if (!managed.active()) {
      reactivateManagedUser(managed.userId(), now);
      managed = new UserLink(managed.userId(), managed.businessUnitType(), true, true);
    }
    ensureRole(managed.userId(), collaboratorRoleId);
    updateManagedUser(managed, person, now);
    return managed;
  }

  private Long linkedUserId(String oaUserId) {
    var rows = jdbc.query("""
        SELECT system_user_id FROM lp_oa_person_directory
         WHERE source_system=? AND environment=? AND oa_user_id=? LIMIT 1
        """, (row, rowNum) -> row.getLong(1),
        properties.getSourceSystem(), properties.getEnvironment(), oaUserId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private UserLink inspectUserByName(String username) {
    var rows = jdbc.query("""
        SELECT user_id,business_unit_type,status,del_flag,remark
          FROM sys_user WHERE user_name=? LIMIT 1
        """, (row, rowNum) -> userLink(row.getLong("user_id"),
            row.getString("business_unit_type"), row.getString("status"),
            row.getString("del_flag"), row.getString("remark")), username);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private UserLink inspectUser(long userId) {
    var rows = jdbc.query("""
        SELECT user_id,business_unit_type,status,del_flag,remark
          FROM sys_user WHERE user_id=? LIMIT 1
        """, (row, rowNum) -> userLink(row.getLong("user_id"),
            row.getString("business_unit_type"), row.getString("status"),
            row.getString("del_flag"), row.getString("remark")), userId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private UserLink userLink(
      long userId, String businessUnitType,
      String status, String delFlag, String remark) {
    return new UserLink(userId, businessUnitType,
        "0".equals(status) && "0".equals(delFlag), MANAGED_USER_REMARK.equals(remark));
  }

  private UserLink createManagedUser(
      String username, OaDirectoryPerson person, LocalDateTime now) {
    GeneratedKeyHolder keys = new GeneratedKeyHolder();
    jdbc.update(connection -> {
      PreparedStatement statement = connection.prepareStatement("""
          INSERT INTO sys_user(
            user_name,password,nick_name,business_unit_type,sex,avatar,status,del_flag,
            create_by,create_time,update_by,update_time,remark)
          VALUES(?,?,?,?,?,'','0','0','oa-directory',?,'oa-directory',?,?)
          """, Statement.RETURN_GENERATED_KEYS);
      statement.setString(1, username);
      statement.setString(2, passwordEncoder.encode(UUID.randomUUID().toString()));
      statement.setString(3, truncate(person.name(), 64));
      statement.setString(4, "COMMERCIAL");
      statement.setString(5, "0");
      statement.setTimestamp(6, Timestamp.valueOf(now));
      statement.setTimestamp(7, Timestamp.valueOf(now));
      statement.setString(8, MANAGED_USER_REMARK);
      return statement;
    }, keys);
    Number key = keys.getKey();
    if (key == null) {
      throw new OaPersonDirectoryException("创建 OA 人员系统身份后未返回主键");
    }
    return new UserLink(key.longValue(), "COMMERCIAL", true, true);
  }

  private boolean hasTechnicalPermission(long userId) {
    Integer count = jdbc.queryForObject("""
        SELECT COUNT(*)
          FROM sys_user_role ur
          JOIN sys_role r ON r.role_id=ur.role_id AND r.status='0' AND r.del_flag='0'
          LEFT JOIN sys_role_menu rm ON rm.role_id=r.role_id
          LEFT JOIN sys_menu m ON m.menu_id=rm.menu_id AND m.status='0'
         WHERE ur.user_id=?
           AND (LOWER(r.role_key)='admin'
             OR m.perms IN ('technical:data:task:edit','technical:data:admin:operate'))
        """, Integer.class, userId);
    return count != null && count > 0;
  }

  private void reactivateManagedUser(long userId, LocalDateTime now) {
    jdbc.update("""
        UPDATE sys_user
           SET status='0',del_flag='0',update_by='oa-directory',update_time=?
         WHERE user_id=? AND remark=?
        """, Timestamp.valueOf(now), userId, MANAGED_USER_REMARK);
  }

  private void disableStaleManagedUsers(LocalDateTime now) {
    jdbc.update("""
        UPDATE sys_user u
        JOIN lp_oa_person_directory d ON d.system_user_id=u.user_id
           SET u.status='1',u.update_by='oa-directory',u.update_time=?
         WHERE d.source_system=? AND d.environment=? AND d.active_flag=0
           AND u.remark=?
           AND NOT EXISTS (
             SELECT 1 FROM lp_oa_person_directory active_person
              WHERE active_person.system_user_id=u.user_id AND active_person.active_flag=1)
        """, Timestamp.valueOf(now), properties.getSourceSystem(), properties.getEnvironment(),
        MANAGED_USER_REMARK);
  }

  private void updateManagedUser(UserLink user, OaDirectoryPerson person, LocalDateTime now) {
    if (!user.managed()) {
      return;
    }
    jdbc.update("""
        UPDATE sys_user SET nick_name=?,update_by='oa-directory',update_time=? WHERE user_id=?
        """, truncate(person.name(), 64), Timestamp.valueOf(now), user.userId());
  }

  private void ensureRole(long userId, long roleId) {
    jdbc.update("INSERT IGNORE INTO sys_user_role(user_id,role_id) VALUES(?,?)", userId, roleId);
  }

  private void upsert(
      OaDirectoryPerson person,
      long systemUserId,
      boolean selectable,
      String batchId,
      LocalDateTime now) {
    jdbc.update("""
        INSERT INTO lp_oa_person_directory(
          source_system,environment,oa_user_id,employee_no,person_name,position_name,position_id,
          employment_status,target_department_paths,actual_department_paths,oa_department_ids,
          target_department_ids,match_types,system_user_id,selectable_flag,active_flag,sync_batch_id,
          last_seen_at,created_at,updated_at)
        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,?,?)
        ON DUPLICATE KEY UPDATE
          oa_user_id=VALUES(oa_user_id),employee_no=VALUES(employee_no),person_name=VALUES(person_name),
          position_name=VALUES(position_name),position_id=VALUES(position_id),
          employment_status=VALUES(employment_status),target_department_paths=VALUES(target_department_paths),
          actual_department_paths=VALUES(actual_department_paths),oa_department_ids=VALUES(oa_department_ids),
          target_department_ids=VALUES(target_department_ids),match_types=VALUES(match_types),
          system_user_id=VALUES(system_user_id),selectable_flag=VALUES(selectable_flag),active_flag=1,
          sync_batch_id=VALUES(sync_batch_id),last_seen_at=VALUES(last_seen_at),updated_at=VALUES(updated_at)
        """,
        properties.getSourceSystem(), properties.getEnvironment(), person.oaUserId(),
        person.employeeNo(), person.name(), emptyToNull(person.positionName()),
        emptyToNull(person.positionId()), emptyToNull(person.employmentStatus()),
        join(person.targetDepartmentPaths()), join(person.actualDepartmentPaths()),
        join(person.oaDepartmentIds()), join(person.targetDepartmentIds()), join(person.matchTypes()),
        systemUserId, selectable ? 1 : 0, batchId, Timestamp.valueOf(now),
        Timestamp.valueOf(now), Timestamp.valueOf(now));
  }

  private void validatePerson(OaDirectoryPerson person) {
    if (person == null || !StringUtils.hasText(person.oaUserId())
        || !StringUtils.hasText(person.employeeNo()) || !StringUtils.hasText(person.name())
        || person.targetDepartmentPaths() == null || person.targetDepartmentPaths().isEmpty()
        || person.actualDepartmentPaths() == null || person.actualDepartmentPaths().isEmpty()) {
      throw new OaPersonDirectoryException("OA 人员目录存在身份或部门信息不完整的人员");
    }
    requireLength("OA人员ID", person.oaUserId(), 64);
    requireLength("工号", person.employeeNo(), 64);
    requireLength("姓名", person.name(), 128);
    requireLength("岗位", person.positionName(), 128);
    requireLength("岗位ID", person.positionId(), 64);
    requireLength("人员状态", person.employmentStatus(), 32);
    requireLength("目标大部门", join(person.targetDepartmentPaths()), 1000);
    requireLength("实际部门", join(person.actualDepartmentPaths()), 2000);
    requireLength("OA部门ID", join(person.oaDepartmentIds()), 1000);
    requireLength("目标部门ID", join(person.targetDepartmentIds()), 1000);
    requireLength("匹配方式", join(person.matchTypes()), 255);
  }

  private static void requireLength(String field, String value, int maximum) {
    if (value != null && value.length() > maximum) {
      throw new OaPersonDirectoryException(field + "超过数据库长度限制");
    }
  }

  private static String managedUsername(String employeeNo, String oaUserId) {
    String candidate = "oa_" + employeeNo;
    if (candidate.length() <= 64) {
      return candidate;
    }
    String suffix = Integer.toUnsignedString(oaUserId.hashCode(), 36).toLowerCase(Locale.ROOT);
    return candidate.substring(0, 63 - suffix.length()) + "_" + suffix;
  }

  private static String truncate(String value, int maximum) {
    return value.length() <= maximum ? value : value.substring(0, maximum);
  }

  private static String join(List<String> values) {
    return values == null ? "" : String.join(JOINER, values);
  }

  private static String emptyToNull(String value) {
    return StringUtils.hasText(value) ? value : null;
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  public record SyncResult(
      String batchId, int totalPeople, int selectablePeople, List<String> missingDepartments) {}

  private record UserLink(long userId, String businessUnitType, boolean active, boolean managed) {}
}
