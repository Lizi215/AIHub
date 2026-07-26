package com.aihub.asset.service.impl;

import com.aihub.api.dto.FileUploadResponse;
import com.aihub.asset.entity.AssetEntity;
import com.aihub.asset.entity.AssetFileEntity;
import com.aihub.asset.entity.AssetVersionEntity;
import com.aihub.asset.enums.AssetStatus;
import com.aihub.asset.enums.VersionPublishStatus;
import com.aihub.asset.mapper.AssetFileMapper;
import com.aihub.asset.mapper.AssetMapper;
import com.aihub.asset.mapper.AssetVersionMapper;
import com.aihub.asset.service.OutboxEventService;
import com.aihub.asset.vo.AssetVersionVo;
import com.aihub.common.mybatis.BaseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 版本落库的独立事务边界。
 *
 * <p>单独一个类是因为上传流程「先传文件、再落库」无法共用一个事务，落库失败时需要在事务**之外**
 * 做补偿（删除已上传对象），把事务收敛到这里，调用方在它抛异常后再补偿，语义清晰。
 * 把 {@code @Transactional} 标在同类私有方法上 Spring 代理不会生效，因此事务方法必须对外暴露。
 *
 * <p>自动发布（给已发布资源追加新版本）同样要发事件：op_version 必须递增并写 outbox，
 * 否则 ES 索引会一直停在旧版本内容。
 */
@Service
public class AssetVersionWriter {

    private static final Logger log = LoggerFactory.getLogger(AssetVersionWriter.class);

    private final AssetVersionMapper assetVersionMapper;
    private final AssetFileMapper assetFileMapper;
    private final AssetMapper assetMapper;
    private final OutboxEventService outboxEventService;

    public AssetVersionWriter(AssetVersionMapper assetVersionMapper,
                              AssetFileMapper assetFileMapper,
                              AssetMapper assetMapper,
                              OutboxEventService outboxEventService) {
        this.assetVersionMapper = assetVersionMapper;
        this.assetFileMapper = assetFileMapper;
        this.assetMapper = assetMapper;
        this.outboxEventService = outboxEventService;
    }

    /**
     * 在一个事务里写入版本、文件元数据，并推进资源的 latest_version。
     *
     * @param autoPublish 资源本身已是公开状态时，新版本直接发布（本项目不做先审后发，docs/ARCHITECTURE.md §9.4）
     */
    @Transactional
    public AssetVersionVo write(AssetEntity asset, String version, String changelog, String readme,
                                FileUploadResponse uploaded, boolean autoPublish) {
        AssetVersionEntity versionEntity = new AssetVersionEntity();
        versionEntity.setAssetId(asset.getId());
        versionEntity.setVersion(version);
        versionEntity.setChangelog(changelog);
        versionEntity.setReadme(readme);
        versionEntity.setPublishStatus(autoPublish
                ? VersionPublishStatus.PUBLISHED.name()
                : VersionPublishStatus.DRAFT.name());
        if (autoPublish) {
            versionEntity.setPublishedAt(LocalDateTime.now());
        }
        versionEntity.setDeleted(BaseEntity.NOT_DELETED);
        assetVersionMapper.insert(versionEntity);

        AssetFileEntity fileEntity = new AssetFileEntity();
        fileEntity.setAssetId(asset.getId());
        fileEntity.setVersionId(versionEntity.getId());
        fileEntity.setObjectKey(uploaded.objectKey());
        fileEntity.setSize(uploaded.size());
        fileEntity.setContentType(uploaded.contentType());
        fileEntity.setChecksum(uploaded.checksum());
        fileEntity.setDeleted(BaseEntity.NOT_DELETED);
        assetFileMapper.insert(fileEntity);

        AssetEntity update = new AssetEntity();
        update.setId(asset.getId());
        update.setLatestVersion(version);

        if (autoPublish) {
            // 与 AssetServiceImpl.publish 一致：opVersion 单调递增 + 同事务写 outbox，
            // 否则消费端无法防乱序，也不会知道内容变了
            long newOpVersion = (asset.getOpVersion() == null ? 0L : asset.getOpVersion()) + 1;
            update.setOpVersion(newOpVersion);
            assetMapper.updateById(update);

            asset.setLatestVersion(version);
            asset.setStatus(AssetStatus.PUBLISHED.name());
            asset.setOpVersion(newOpVersion);
            outboxEventService.appendAssetUploaded(asset, version, readme, newOpVersion);
            log.info("asset version auto published, assetId={}, version={}, opVersion={}",
                    asset.getId(), version, newOpVersion);
        } else {
            assetMapper.updateById(update);
        }

        return new AssetVersionVo(versionEntity.getId(), version, changelog, readme,
                versionEntity.getPublishStatus(), uploaded.checksum(), uploaded.size(), uploaded.contentType(),
                null);
    }
}
