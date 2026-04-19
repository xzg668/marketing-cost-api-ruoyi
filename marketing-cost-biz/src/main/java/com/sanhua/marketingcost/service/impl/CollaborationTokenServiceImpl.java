package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.system.LpCollaborationToken;
import com.sanhua.marketingcost.mapper.LpCollaborationTokenMapper;
import com.sanhua.marketingcost.service.CollaborationTokenService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 协作者 Token 管理 Service 实现类
 */
@Service
public class CollaborationTokenServiceImpl implements CollaborationTokenService {

    /** Token 状态：有效 */
    private static final String STATUS_VALID = "0";
    /** Token 状态：已使用 */
    private static final String STATUS_USED = "1";
    /** Token 状态：已过期 */
    private static final String STATUS_EXPIRED = "2";

    private final LpCollaborationTokenMapper tokenMapper;

    public CollaborationTokenServiceImpl(LpCollaborationTokenMapper tokenMapper) {
        this.tokenMapper = tokenMapper;
    }

    @Override
    @Transactional
    public LpCollaborationToken generateToken(Long userId, String tokenType, String remark, int expireHours) {
        LpCollaborationToken record = new LpCollaborationToken();
        // 生成唯一 Token（UUID 去掉连字符）
        record.setToken(UUID.randomUUID().toString().replace("-", ""));
        record.setUserId(userId);
        record.setTokenType(tokenType);
        record.setRemark(remark);
        record.setExpireTime(LocalDateTime.now().plusHours(expireHours));
        record.setStatus(STATUS_VALID);
        tokenMapper.insert(record);
        return record;
    }

    @Override
    public LpCollaborationToken validateToken(String token) {
        // 按 token 值查询记录
        LpCollaborationToken record = tokenMapper.selectOne(
                Wrappers.lambdaQuery(LpCollaborationToken.class)
                        .eq(LpCollaborationToken::getToken, token)
        );
        if (record == null) {
            return null;
        }
        // 检查是否已使用
        if (STATUS_USED.equals(record.getStatus())) {
            return null;
        }
        // 检查是否已过期
        if (record.getExpireTime() != null && record.getExpireTime().isBefore(LocalDateTime.now())) {
            // 自动更新过期状态
            if (STATUS_VALID.equals(record.getStatus())) {
                record.setStatus(STATUS_EXPIRED);
                tokenMapper.updateById(record);
            }
            return null;
        }
        // 检查状态是否有效
        if (!STATUS_VALID.equals(record.getStatus())) {
            return null;
        }
        return record;
    }

    @Override
    @Transactional
    public void markUsed(Long tokenId) {
        LpCollaborationToken record = new LpCollaborationToken();
        record.setTokenId(tokenId);
        record.setStatus(STATUS_USED);
        tokenMapper.updateById(record);
    }
}
