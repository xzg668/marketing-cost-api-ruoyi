package com.sanhua.marketingcost.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;

/**
 * 路由树节点 VO（/api/v1/auth/routers 响应体）。
 * <p>
 * 结构对齐若依前端的路由约定：
 * <pre>
 * {
 *   "name": "System",
 *   "path": "/system",
 *   "component": "Layout",
 *   "meta": { "title": "系统管理", "icon": "system" },
 *   "children": [...]
 * }
 * </pre>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RouterVO {
    /** 菜单ID（前端可用于 key） */
    private Long menuId;
    /** 路由名称（英文标识） */
    private String name;
    /** 路由路径 */
    private String path;
    /** 组件路径（目录为 null 或 Layout） */
    private String component;
    /** 路由元信息 */
    private Meta meta;
    /** 子路由列表；无子项时为 null（@JsonInclude 过滤） */
    private List<RouterVO> children;

    /**
     * 路由 meta —— 前端 layout 展示用。
     */
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Meta {
        /** 标题（中文菜单名） */
        private String title;
        /** 图标 */
        private String icon;
        /** 是否隐藏（0 显示、1 隐藏） */
        private Boolean hidden;
    }
}
