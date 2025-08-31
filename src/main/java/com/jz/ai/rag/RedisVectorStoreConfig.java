// src/main/java/com/jz/ai/rag/RedisVectorStoreConfig.java
package com.jz.ai.rag;

import com.jz.ai.config.RagRedisProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import redis.clients.jedis.JedisPooled;

@Configuration
@EnableConfigurationProperties(RagRedisProperties.class)
public class RedisVectorStoreConfig {
    @Bean
    @Primary // 覆盖自动注入的 VectorStore
    public RedisVectorStore productVectorStore(JedisPooled jedis,
                                               EmbeddingModel embeddingModel,
                                               RagRedisProperties p) {
        return RedisVectorStore.builder(jedis, embeddingModel)
                .indexName(p.getIndex())
                .prefix(p.getPrefix())
                .contentFieldName("content")
                .embeddingFieldName("embedding")
                .initializeSchema(p.isInitializeSchema())
                .metadataFields( // 把结构化字段写进 schema，检索时才会回填到 Document.metadata
                        RedisVectorStore.MetadataField.tag("brand"),
                        RedisVectorStore.MetadataField.tag("title"),
                        RedisVectorStore.MetadataField.tag("category"),
                        RedisVectorStore.MetadataField.numeric("price"),
                        RedisVectorStore.MetadataField.numeric("stock"),
                        RedisVectorStore.MetadataField.tag("isActive"),
                        RedisVectorStore.MetadataField.numeric("productId"),
                        RedisVectorStore.MetadataField.text("url")
                )
                .build();
    }
}
