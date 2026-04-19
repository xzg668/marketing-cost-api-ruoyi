-- 用户表
CREATE TABLE sys_user (
  user_id bigint NOT NULL AUTO_INCREMENT,
  user_name varchar(64) NOT NULL COMMENT '登录账号',
  password varchar(256) NOT NULL COMMENT '密码',
  nick_name varchar(64) DEFAULT NULL COMMENT '用户昵称',
  status char(1) NOT NULL DEFAULT '0' COMMENT '帐号状态（0正常 1停用）',
  del_flag char(1) DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  create_by varchar(64) DEFAULT '' COMMENT '创建者',
  create_time datetime NOT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT '' COMMENT '更新者',
  update_time datetime NOT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (user_id),
  UNIQUE KEY uk_user_name (user_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '用户信息表';

-- 角色表
CREATE TABLE sys_role (
  role_id bigint NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  role_name varchar(64) NOT NULL COMMENT '角色名称',
  role_key varchar(100) NOT NULL COMMENT '角色权限字符串',
  role_sort int(4) NOT NULL COMMENT '显示顺序',
  status char(1) NOT NULL DEFAULT '0' COMMENT '角色状态（0正常 1停用）',
  del_flag char(1) DEFAULT '0' COMMENT '删除标志（0代表存在 2代表删除）',
  create_by varchar(64) DEFAULT '' COMMENT '创建者',
  create_time datetime NOT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT '' COMMENT '更新者',
  update_time datetime NOT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (role_id),
  UNIQUE KEY uk_role_name (role_name),
  UNIQUE KEY uk_role_key (role_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '角色信息表';

-- 菜单权限表
CREATE TABLE sys_menu (
  menu_id bigint NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  menu_name varchar(50) NOT NULL COMMENT '菜单名称',
  parent_id bigint DEFAULT 0 COMMENT '父菜单ID',
  order_num int(4) DEFAULT 0 COMMENT '显示顺序',
  path varchar(200) DEFAULT '' COMMENT '路由地址',
  component varchar(255) DEFAULT NULL COMMENT '组件路径',
  is_frame varchar(1) DEFAULT '1' COMMENT '是否为外链（0是 1否）',
  is_cache varchar(1) DEFAULT '0' COMMENT '是否缓存（0缓存 1不缓存）',
  menu_type char(1) DEFAULT '' COMMENT '菜单类型（M目录 C菜单 F按钮）',
  visible char(1) DEFAULT '0' COMMENT '菜单状态（0显示 1隐藏）',
  status char(1) DEFAULT '0' COMMENT '菜单状态（0正常 1停用）',
  perms varchar(100) DEFAULT NULL COMMENT '权限标识',
  icon varchar(100) DEFAULT '#' COMMENT '菜单图标',
  create_by varchar(64) DEFAULT '' COMMENT '创建者',
  create_time datetime DEFAULT NULL COMMENT '创建时间',
  update_by varchar(64) DEFAULT '' COMMENT '更新者',
  update_time datetime DEFAULT NULL COMMENT '更新时间',
  remark varchar(500) DEFAULT '' COMMENT '备注',
  PRIMARY KEY (menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '菜单权限表';

-- 用户角色关联表
CREATE TABLE sys_user_role (
  user_id bigint NOT NULL COMMENT '用户ID',
  role_id bigint NOT NULL COMMENT '角色ID',
  PRIMARY KEY (user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '用户和角色关联表';

-- 角色菜单关联表
CREATE TABLE sys_role_menu (
  role_id bigint NOT NULL COMMENT '角色ID',
  menu_id bigint NOT NULL COMMENT '菜单ID',
  PRIMARY KEY (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT = '角色和菜单关联表';

-- 种子数据：admin / admin123
INSERT INTO sys_role VALUES (1, '超级管理员', 'admin', 1, '0', '0', 'admin', NOW(), '', NOW(), '超级管理员');
INSERT INTO sys_user (user_id, user_name, password, nick_name, status, create_time, update_time)
VALUES (1, 'admin', '$2a$10$GbHUEQG3Z.HxChIvMTwSq.c1et5tTdFDx4Q31wMh1ThNbXtdJMRme', '管理员', '0', NOW(), NOW());
INSERT INTO sys_user_role VALUES (1, 1);
