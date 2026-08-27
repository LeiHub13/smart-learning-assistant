package com.example.learningassistant.infra.vector;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.GetCollectionStatsReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.UpsertReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Milvus 向量库实现：配置 app.infra.vector-mode=milvus 时启用（默认 memory）。
 *
 * 设计：
 * - 懒连接/懒建集合：首次写入时按向量实际维度自动创建集合（AUTOINDEX + COSINE），切换
 *   Embedding 模型（hash 128 维 / dashscope 1024 维）后维度不一致时自动重建集合；
 * - 集合持久化在 Milvus 中，应用重启不丢数据，无需像内存版一样从 t_chunk 重建索引；
 * - put 用 upsert 幂等写入；delete 按 chunkId 删除，与 KbService 的增删链路对齐。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.infra.vector-mode", havingValue = "milvus")
public class MilvusVectorStore implements VectorStore {

    private final String host;
    private final String port;
    private final String collection;

    private volatile MilvusClientV2 client;
    /** 当前已确保集合与该维度一致（-1 表示尚未校验），避免每次写入都发 RPC 校验 */
    private volatile int ensuredDim = -1;
    private final Object ensureLock = new Object();

    public MilvusVectorStore(@Value("${app.infra.milvus.host:localhost}") String host,
                             @Value("${app.infra.milvus.port:19530}") String port,
                             @Value("${app.infra.milvus.collection:learnassist_vectors}") String collection) {
        this.host = host;
        this.port = port;
        this.collection = collection;
        log.info("向量库使用 Milvus: {}:{}, 集合: {}（懒连接，首次读写时建立）", host, port, collection);
    }

    private MilvusClientV2 client() {
        MilvusClientV2 c = client;
        if (c == null) {
            synchronized (ensureLock) {
                c = client;
                if (c == null) {
                    try {
                        ConnectConfig config = ConnectConfig.builder()
                                .uri("http://" + host + ":" + port)
                                .build();
                        c = new MilvusClientV2(config);
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "无法连接 Milvus(" + host + ":" + port + ")：" + e.getMessage(), e);
                    }
                    client = c;
                    log.info("已连接 Milvus {}:{}", host, port);
                }
            }
        }
        return c;
    }

    private void ensureReady(int dim) {
        int ensured = ensuredDim;
        if (ensured == dim) {
            return;
        }
        synchronized (ensureLock) {
            ensured = ensuredDim;
            if (ensured == dim) {
                return;
            }
            MilvusClientV2 c = client();
            ensureCollection(c, dim);
            ensuredDim = dim;
        }
    }

    /** 集合不存在则按维度创建；存在但维度不符（如切换 Embedding 模型）则重建并给出明确警告 */
    private void ensureCollection(MilvusClientV2 c, int dim) {
        if (!exists(c)) {
            create(c, dim);
            log.info("Milvus 集合 {} 已创建（维度 {}）", collection, dim);
            return;
        }
        int existing = existingDim(c);
        if (existing > 0 && existing != dim) {
            c.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
            ensuredDim = -1;
            create(c, dim);
            log.warn("Milvus 集合维度({})与当前 Embedding 输出维度({})不一致，已重建集合。"
                    + "请调用 POST /api/kb/reindex 从 t_chunk 重建全部向量", existing, dim);
        }
    }

    private boolean exists(MilvusClientV2 c) {
        try {
            return Boolean.TRUE.equals(c.hasCollection(HasCollectionReq.builder()
                    .collectionName(collection).build()));
        } catch (Exception e) {
            throw new IllegalStateException("Milvus 查询集合状态失败: " + e.getMessage(), e);
        }
    }

    private void create(MilvusClientV2 c, int dim) {
        CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
        schema.addField(AddFieldReq.builder()
                .fieldName("id").dataType(DataType.Int64).isPrimaryKey(true).autoID(false).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("vector").dataType(DataType.FloatVector).dimension(dim).build());
        IndexParam indexParam = IndexParam.builder()
                .fieldName("vector")
                .indexType(IndexParam.IndexType.AUTOINDEX)
                .metricType(IndexParam.MetricType.COSINE)
                .build();
        c.createCollection(CreateCollectionReq.builder()
                .collectionName(collection)
                .description("learnassist RAG chunk vectors")
                .collectionSchema(schema)
                .indexParams(List.of(indexParam))
                .build());
        c.loadCollection(LoadCollectionReq.builder().collectionName(collection).build());
    }

    private int existingDim(MilvusClientV2 c) {
        DescribeCollectionResp desc = c.describeCollection(DescribeCollectionReq.builder()
                .collectionName(collection).build());
        if (desc.getCollectionSchema() == null || desc.getCollectionSchema().getFieldSchemaList() == null) {
            return -1;
        }
        for (CreateCollectionReq.FieldSchema f : desc.getCollectionSchema().getFieldSchemaList()) {
            if (f.getDataType() == DataType.FloatVector && f.getDimension() != null) {
                return f.getDimension();
            }
        }
        return -1;
    }

    @Override
    public void put(long id, float[] vector) {
        if (vector == null || vector.length == 0) {
            return;
        }
        try {
            ensureReady(vector.length);
            JsonArray vec = new JsonArray(vector.length);
            for (float v : vector) {
                vec.add(v);
            }
            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.add("vector", vec);
            client().upsert(UpsertReq.builder()
                    .collectionName(collection)
                    .data(List.of(row))
                    .build());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Milvus 写入失败(id=" + id + "): " + e.getMessage(), e);
        }
    }

    @Override
    public void remove(long id) {
        try {
            if (!exists(client())) {
                return;
            }
            client().delete(DeleteReq.builder()
                    .collectionName(collection)
                    .ids(Collections.singletonList((Object) id))
                    .build());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Milvus 删除失败(id=" + id + "): " + e.getMessage(), e);
        }
    }

    @Override
    public void clear() {
        try {
            MilvusClientV2 c = client();
            if (!exists(c)) {
                return;
            }
            c.dropCollection(DropCollectionReq.builder().collectionName(collection).build());
            ensuredDim = -1;
            log.info("Milvus 集合 {} 已清空(删除)", collection);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Milvus 清空失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<ScoredId> search(float[] query, int topK) {
        try {
            MilvusClientV2 c = client();
            if (query == null || query.length == 0 || !exists(c)) {
                return List.of();
            }
            SearchResp resp = c.search(SearchReq.builder()
                    .collectionName(collection)
                    .annsField("vector")
                    .data(Collections.singletonList(new FloatVec(query)))
                    .topK(topK)
                    .build());
            List<ScoredId> results = new ArrayList<>();
            List<List<SearchResp.SearchResult>> batches = resp.getSearchResults();
            if (batches != null && !batches.isEmpty()) {
                for (SearchResp.SearchResult r : batches.get(0)) {
                    Object raw = r.getId();
                    long id = raw instanceof Number n ? n.longValue() : -1L;
                    if (id >= 0) {
                        results.add(new ScoredId(id, r.getScore()));
                    }
                }
            }
            return results;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Milvus 检索失败: " + e.getMessage(), e);
        }
    }

    @Override
    public int size() {
        // 健康检查等展示场景容忍 Milvus 不可达：返回 0 并打日志，不让 /api/health 直接 500
        try {
            MilvusClientV2 c = client();
            if (!exists(c)) {
                return 0;
            }
            Long n = c.getCollectionStats(GetCollectionStatsReq.builder()
                    .collectionName(collection).build()).getNumOfEntities();
            return n == null ? 0 : Math.toIntExact(n);
        } catch (Exception e) {
            log.warn("获取 Milvus 向量数失败: {}", e.getMessage());
            return 0;
        }
    }
}
