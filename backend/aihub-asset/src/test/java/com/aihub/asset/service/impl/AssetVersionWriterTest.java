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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 版本落库 + 自动发布的事件语义。
 *
 * <p>锁住一个容易回归的点：给已发布资源追加新版本时必须递增 op_version 并写 outbox 事件，
 * 否则 ES 索引会停在旧版本内容且没有任何报错。
 */
@ExtendWith(MockitoExtension.class)
class AssetVersionWriterTest {

    private static final long ASSET_ID = 100L;

    @Mock
    private AssetVersionMapper assetVersionMapper;
    @Mock
    private AssetFileMapper assetFileMapper;
    @Mock
    private AssetMapper assetMapper;
    @Mock
    private OutboxEventService outboxEventService;

    private AssetVersionWriter writer;

    @BeforeEach
    void setUp() {
        writer = new AssetVersionWriter(assetVersionMapper, assetFileMapper, assetMapper, outboxEventService);
    }

    @Test
    @DisplayName("自动发布：递增 op_version 并写 outbox 事件（否则索引不会更新）")
    void shouldAppendEventAndBumpOpVersionWhenAutoPublished() {
        AssetEntity asset = asset(AssetStatus.PUBLISHED, 3L);

        writer.write(asset, "1.0.1", "第二版", "新 README", upload(), true);

        ArgumentCaptor<AssetEntity> updateCaptor = ArgumentCaptor.forClass(AssetEntity.class);
        verify(assetMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getLatestVersion()).isEqualTo("1.0.1");
        assertThat(updateCaptor.getValue().getOpVersion()).isEqualTo(4L);

        // 事件里的状态必须是"变更后"的 PUBLISHED，否则消费端会把已发布资源当草稿剔除
        verify(outboxEventService).appendAssetUploaded(
                eq(asset), eq("1.0.1"), eq("新 README"), eq(4L));
        assertThat(asset.getStatus()).isEqualTo(AssetStatus.PUBLISHED.name());
    }

    @Test
    @DisplayName("草稿态上传：不写事件、不动 op_version（资源本身还不该进索引）")
    void shouldNotAppendEventWhenDraft() {
        AssetEntity asset = asset(AssetStatus.DRAFT, 0L);

        writer.write(asset, "1.0.0", "首个版本", "README", upload(), false);

        ArgumentCaptor<AssetEntity> updateCaptor = ArgumentCaptor.forClass(AssetEntity.class);
        verify(assetMapper).updateById(updateCaptor.capture());
        assertThat(updateCaptor.getValue().getOpVersion()).isNull();

        verify(outboxEventService, never()).appendAssetUploaded(any(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("版本与文件元数据都要落库，且草稿版本的 publish_status 为 DRAFT")
    void shouldPersistVersionAndFile() {
        AssetEntity asset = asset(AssetStatus.DRAFT, 0L);

        writer.write(asset, "1.0.0", null, null, upload(), false);

        ArgumentCaptor<AssetVersionEntity> versionCaptor = ArgumentCaptor.forClass(AssetVersionEntity.class);
        verify(assetVersionMapper).insert(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getPublishStatus()).isEqualTo(VersionPublishStatus.DRAFT.name());

        ArgumentCaptor<AssetFileEntity> fileCaptor = ArgumentCaptor.forClass(AssetFileEntity.class);
        verify(assetFileMapper).insert(fileCaptor.capture());
        assertThat(fileCaptor.getValue().getChecksum()).isEqualTo("checksum-abc");
        assertThat(fileCaptor.getValue().getSize()).isEqualTo(308L);
    }

    private AssetEntity asset(AssetStatus status, Long opVersion) {
        AssetEntity asset = new AssetEntity();
        asset.setId(ASSET_ID);
        asset.setOwnerId(1L);
        asset.setName("Spring Cloud 配置技能");
        asset.setStatus(status.name());
        asset.setOpVersion(opVersion);
        return asset;
    }

    private FileUploadResponse upload() {
        return new FileUploadResponse("assets/100/1.0.1/package.zip", 308L, "application/zip", "checksum-abc");
    }
}
