package com.aihub.search.index;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch._types.VersionType;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.json.JsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ErrorCode;
import com.aihub.search.config.ElasticsearchProperties;
import jakarta.json.Json;
import jakarta.json.stream.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.Map;

/**
 * 索引管理：建索引（含 mapping/alias）、文档读写、索引删除。
 *
 * <p>读写一律走别名，物理索引名只出现在建/删索引的方法里。
 * 索引缺失时幂等创建，bootstrap 与对账任务都会调用，这是重建零停机的前提。
 * ES 的失败统一转成 {@link ErrorCode#SEARCH_ENGINE_UNAVAILABLE} 或
 * {@link ErrorCode#SEARCH_INDEX_OPERATION_FAILED}，不让底层异常冒到接口层。
 */
@Component
public class IndexManager {

    private static final Logger log = LoggerFactory.getLogger(IndexManager.class);

    /** 文档里承载业务对象版本的字段名，消费端据此防乱序。 */
    public static final String FIELD_OP_VERSION = "opVersion";

    private final ElasticsearchClient client;
    private final ElasticsearchTransport transport;
    private final IndexDefinitions definitions;
    private final ElasticsearchProperties properties;

    public IndexManager(ElasticsearchClient client,
                        ElasticsearchTransport transport,
                        IndexDefinitions definitions,
                        ElasticsearchProperties properties) {
        this.client = client;
        this.transport = transport;
        this.definitions = definitions;
        this.properties = properties;
    }

    /** 幂等创建缺失的索引（settings + mappings + 读写别名）。 */
    public void ensureIndexes() {
        for (SearchDocType docType : SearchDocType.values()) {
            if (indexExists(docType)) {
                log.info("search index already exists, index={}", docType.physicalIndex());
                continue;
            }
            String body = definitions.createBody(docType);
            try (JsonParser parser = Json.createParser(new StringReader(body))) {
                JsonpMapper mapper = transport.jsonpMapper();
                client.indices().create(request -> request
                        .index(docType.physicalIndex())
                        .withJson(parser, mapper));
                log.info("search index created, index={}, writeAlias={}, readAlias={}",
                        docType.physicalIndex(), docType.writeAlias(), docType.readAlias());
            } catch (IOException e) {
                throw new BizException(ErrorCode.SEARCH_INDEX_OPERATION_FAILED,
                        "failed to create index " + docType.physicalIndex() + ": " + e.getMessage());
            }
        }
    }

    public boolean indexExists(SearchDocType docType) {
        try {
            return client.indices().exists(request -> request.index(docType.physicalIndex())).value();
        } catch (IOException e) {
            throw new BizException(ErrorCode.SEARCH_ENGINE_UNAVAILABLE, "failed to check index existence");
        }
    }

    /** 删除物理索引及其别名。只给运维/对账重建与测试用，业务代码不要调用。 */
    public void deleteIndex(SearchDocType docType) {
        try {
            client.indices().delete(request -> request.index(docType.physicalIndex()));
            log.warn("search index deleted, index={}", docType.physicalIndex());
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                log.warn("search index already absent, index={}", docType.physicalIndex());
                return;
            }
            throw new BizException(ErrorCode.SEARCH_INDEX_OPERATION_FAILED,
                    "failed to delete index " + docType.physicalIndex());
        } catch (IOException e) {
            throw new BizException(ErrorCode.SEARCH_ENGINE_UNAVAILABLE, "failed to delete index");
        }
    }

    /**
     * 写入 / 覆盖一个文档。
     *
     * <p>用 ES 外部版本号（external versioning）防乱序，而不是先读再写：
     * MQ 并发消费时，同一资源的事件可能被不同线程同时处理，读-判断-写之间有窗口，
     * 旧事件能在窗口内后写成功、把索引回退。交给 ES 判定是原子的。
     *
     * <p>文档一旦用外部版本号写入，后续写入必须继续用外部版本号且严格递增，索引重建后同理。
     *
     * @return {@code true} 表示本次写入生效；{@code false} 表示版本不新，已被 ES 拒绝
     */
    public boolean upsert(SearchDocType docType, String id, Map<String, Object> document, long opVersion) {
        try {
            client.index(request -> request
                    .index(docType.writeAlias())
                    .id(id)
                    .document(document)
                    .version(opVersion)
                    .versionType(VersionType.External)
                    .refresh(refreshPolicy()));
            return true;
        } catch (ElasticsearchException e) {
            if (e.status() == 409) {
                // 版本冲突 = 乱序到达的旧事件，属于预期情况
                log.info("stale document rejected by external versioning, doc={}/{}, incomingOpVersion={}",
                        docType.code(), id, opVersion);
                return false;
            }
            throw new BizException(ErrorCode.SEARCH_INDEX_OPERATION_FAILED,
                    "failed to index document " + docType.code() + "/" + id + ": " + e.getMessage());
        } catch (IOException e) {
            throw new BizException(ErrorCode.SEARCH_INDEX_OPERATION_FAILED,
                    "failed to index document " + docType.code() + "/" + id + ": " + e.getMessage());
        }
    }

    /**
     * 删除文档。文档本就不存在时视为成功（幂等）。
     *
     * <p>判据是响应里的 {@code result}，不用 catch 404：ES 对删除不存在的文档返回
     * {@code result=not_found} 的正常响应，只靠 catch 404 会把本来就不存在也当成删除成功。
     *
     * @return {@code true} 表示确实删掉了一份文档；{@code false} 表示它本就不在索引里
     */
    public boolean deleteDocument(SearchDocType docType, String id) {
        try {
            DeleteResponse response = client.delete(request -> request
                    .index(docType.writeAlias())
                    .id(id)
                    .refresh(refreshPolicy()));
            return response.result() == Result.Deleted;
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                // 索引本身不存在时也会走到这里（别名未指向任何索引）
                log.debug("search index or document absent, {}/{}", docType.code(), id);
                return false;
            }
            throw new BizException(ErrorCode.SEARCH_INDEX_OPERATION_FAILED,
                    "failed to delete document " + docType.code() + "/" + id);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SEARCH_ENGINE_UNAVAILABLE, "failed to delete document");
        }
    }

    /**
     * 读取文档当前的 {@code opVersion}，用于防乱序比对。
     *
     * <p>从索引读而不是从 Redis：索引里的值才真正生效，Redis 只是加速，
     * 丢了大不了多写一次（写入按 id 幂等）。
     *
     * @return 文档不存在时返回 {@code null}（视为首次处理）
     */
    public Long readOpVersion(SearchDocType docType, String id) {
        try {
            GetResponse<Map> response = client.get(request -> request
                    .index(docType.readAlias())
                    .id(id), Map.class);
            if (!response.found() || response.source() == null) {
                return null;
            }
            Object value = response.source().get(FIELD_OP_VERSION);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value instanceof String text && !text.isBlank()) {
                return Long.parseLong(text.trim());
            }
            return null;
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return null;
            }
            throw new BizException(ErrorCode.SEARCH_ENGINE_UNAVAILABLE, "failed to read document version");
        } catch (IOException e) {
            throw new BizException(ErrorCode.SEARCH_ENGINE_UNAVAILABLE, "failed to read document version");
        }
    }

    /** 统计某索引当前文档数，用于对账与验收（`_count`）。 */
    public long count(SearchDocType docType) {
        try {
            return client.count(request -> request.index(docType.readAlias())).count();
        } catch (IOException e) {
            throw new BizException(ErrorCode.SEARCH_ENGINE_UNAVAILABLE, "failed to count documents");
        }
    }

    /**
     * 写入后的刷新策略。
     *
     * <p>默认 {@code wait_for}：写完立即可搜，便于验收与本地演示。
     * 生产应改为 {@code false}，让 ES 按 refresh_interval 批量刷新以降低段合并开销。
     */
    private Refresh refreshPolicy() {
        return properties.isWaitForRefresh() ? Refresh.WaitFor : Refresh.False;
    }
}
