package com.aihub.asset.service;

import com.aihub.asset.entity.AssetEntity;
import com.aihub.asset.enums.AssetStatus;
import com.aihub.asset.mapper.AssetMapper;
import com.aihub.common.auth.Role;
import com.aihub.common.exception.BizException;
import com.aihub.common.mybatis.BaseEntity;
import com.aihub.common.result.ErrorCode;
import com.aihub.common.trace.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

/**
 * 资源访问守卫。
 *
 * <p>权限判定是安全关键逻辑，只应有**一处**实现，否则各服务各写一份迟早会漂移出越权漏洞。
 * 「归属」与「角色」两条依据都集中在这里（docs/ARCHITECTURE.md §9.2）：
 *
 * <pre>
 * 允许 = 角色满足  OR  归属匹配（owner_id == 当前 userId）
 * </pre>
 *
 * <p>管理自己名下的资源只看归属，任何 User 都具备；处理他人资源才需要 Maintainer 及以上。
 */
@Component
public class AssetAccessGuard {

    private final AssetMapper assetMapper;

    public AssetAccessGuard(AssetMapper assetMapper) {
        this.assetMapper = assetMapper;
    }

    /**
     * 要求当前用户是该资源的 owner（写操作：上传、发布、改依赖）。
     */
    public AssetEntity requireOwned(Long assetId, Long currentUserId) {
        AssetEntity asset = findActive(assetId);
        if (!asset.getOwnerId().equals(currentUserId)) {
            throw new BizException(ErrorCode.ASSET_ACCESS_DENIED);
        }
        return asset;
    }

    /**
     * 要求该资源对当前用户可见（读操作）。
     *
     * <p>已发布对所有人可见；其余状态（草稿、已下架）仅 owner / Maintainer / Admin 可见。
     */
    public AssetEntity requireVisible(Long assetId, Long currentUserId) {
        AssetEntity asset = findActive(assetId);
        if (AssetStatus.PUBLISHED.name().equals(asset.getStatus())) {
            return asset;
        }
        if (isOwnerOrPrivileged(asset, currentUserId)) {
            return asset;
        }
        throw new BizException(ErrorCode.ASSET_ACCESS_DENIED);
    }

    /**
     * 读取未软删除的资源，不存在则 20001。
     */
    public AssetEntity findActive(Long assetId) {
        AssetEntity asset = assetMapper.selectOne(new LambdaQueryWrapper<AssetEntity>()
                .eq(AssetEntity::getId, assetId)
                .eq(AssetEntity::getDeleted, BaseEntity.NOT_DELETED));
        if (asset == null) {
            throw new BizException(ErrorCode.ASSET_NOT_FOUND);
        }
        return asset;
    }

    private boolean isOwnerOrPrivileged(AssetEntity asset, Long currentUserId) {
        if (currentUserId != null && currentUserId.equals(asset.getOwnerId())) {
            return true;
        }
        return Role.isPrivileged(UserContext.getRole());
    }
}
