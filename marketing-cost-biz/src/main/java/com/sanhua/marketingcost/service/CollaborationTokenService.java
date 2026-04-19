package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.system.LpCollaborationToken;

/**
 * 协作者 Token 管理 Service 接口
 * <p>
 * 用于 OA 协作者的一次性 Token 生成、校验和标记已使用。
 * 协作者不走标准登录流程，通过 Token 访问受限页面。
 */
public interface CollaborationTokenService {

    /**
     * 生成协作 Token
     *
     * @param userId          关联用户ID（发起协作的用户）
     * @param tokenType       令牌类型（如 bom-supplement、price-supplement）
     * @param remark          备注（通常存储 oaNo 等业务信息）
     * @param expireHours     过期时长（小时）
     * @return 生成的 Token 记录
     */
    LpCollaborationToken generateToken(Long userId, String tokenType, String remark, int expireHours);

    /**
     * 校验 Token 有效性
     * <p>
     * 检查：存在性、是否过期、是否已使用。
     * 自动将已过期但状态未更新的 Token 标记为过期。
     *
     * @param token 令牌值
     * @return 有效则返回 Token 记录，无效返回 null
     */
    LpCollaborationToken validateToken(String token);

    /**
     * 标记 Token 为已使用
     *
     * @param tokenId 令牌ID
     */
    void markUsed(Long tokenId);
}
