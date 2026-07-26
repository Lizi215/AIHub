package com.aihub.asset.service;

import com.aihub.asset.dto.CreateAssetRequest;
import com.aihub.asset.dto.UpdateAssetRequest;
import com.aihub.asset.vo.AssetDetailVo;
import com.aihub.asset.vo.AssetDownload;
import com.aihub.asset.vo.AssetVersionVo;
import com.aihub.asset.vo.AssetVo;
import com.aihub.common.result.PageQuery;
import com.aihub.common.result.PageResult;

import java.util.List;

/**
 * 资源核心业务。Asset Service 是上传流程的编排者（docs/ARCHITECTURE.md §8.1）。
 */
public interface AssetService {

    /** 上传阶段①：创建草稿，返回 assetId。 */
    Long createDraft(CreateAssetRequest request, Long ownerId);

    /**
     * 上传阶段②：上传文件并生成版本。
     *
     * @param currentUserId 当前登录用户，用于归属校验
     */
    AssetVersionVo uploadVersion(Long assetId, Long currentUserId, byte[] content, String contentType,
                                 String version, String changelog, String readme);

    /** 上传阶段③：发布。 */
    void publish(Long assetId, Long currentUserId);

    /**
     * 编辑资源元数据（名称 / 简介 / 分类），owner only。
     *
     * <p>三个字段都进 ES 索引，因此与标签变更同理：
     * 必须 bump opVersion 并写 outbox，否则搜索里的名称/简介会一直停在旧值。
     */
    void update(Long assetId, Long currentUserId, UpdateAssetRequest request);

    /**
     * 删除资源（软删资源与其版本/文件元数据），owner only。
     *
     * <p>删除后资源不再出现在任何列表与详情里，并按事件把 ES 文档移除。
     */
    void delete(Long assetId, Long currentUserId);

    /**
     * 删除指定版本（owner only，可一次传多个版本号，不存在的版本幂等跳过）。
     *
     * <p>软删版本与文件元数据（对象存储文件保留，与整资源删除现状一致），重算
     * {@code latest_version} 为剩余版本中语义版本号最大者。全部删光时 DRAFT 回到无版本草稿态
     * （latest 置空），PUBLISHED 直接拒绝——"已发布且无版本"是非法态，应改用 {@link #delete}。
     *
     * @return 重算后的 latest_version（可能为 null）
     */
    String deleteVersions(Long assetId, List<String> versions, Long currentUserId);

    /**
     * 下架（Admin only，docs/ARCHITECTURE.md §9.4）：PUBLISHED → SUSPENDED，
     * 公开不可见，并经事件移除 ES 索引。
     */
    void suspend(Long assetId, Long operatorId);

    /**
     * 分页列表。草稿仅 owner 可见。
     *
     * @param onlyMine true 时只看当前用户自己名下的资源（含草稿）；
     *                 匿名调用（currentUserId 为 null）时返回空列表——
     *                 「我的」在未登录语义下不成立
     */
    PageResult<AssetVo> list(PageQuery query, String category, Long currentUserId, Boolean onlyMine);

    /** 详情。非公开状态仅 owner / Maintainer / Admin 可见。 */
    AssetDetailVo detail(Long assetId, Long currentUserId);

    /**
     * 查当前用户名下与 {@code name} 完全同名的资源（含草稿），没有则返回 null。
     *
     * <p>手动上传时用来判断"追加新版本"还是"新建资源"，避免用户重复创建同名资源。
     */
    com.aihub.asset.vo.OwnAssetLookupVo lookupOwnByName(Long currentUserId, String name);

    /** 版本列表（按创建时间倒序）。 */
    List<AssetVersionVo> listVersions(Long assetId, Long currentUserId);

    /** 下载最新版本。归属、草稿可见性与防刷量都由 Asset Service 负责。 */
    AssetDownload download(Long assetId, Long currentUserId);

    /** 下载指定版本。 */
    AssetDownload downloadVersion(Long assetId, String version, Long currentUserId);

    /**
     * 下载指定版本的 README（以 .md 文件形式）。
     *
     * <p>README 是文本，发布时存在 asset_version 里，用户需要一个能直接打开的 .md 文件，
     * 且每个版本对应自己那份 README（老版本的也能下）。可见性与 {@link #downloadVersion} 一致。
     */
    AssetDownload downloadReadme(Long assetId, String version, Long currentUserId);
}
