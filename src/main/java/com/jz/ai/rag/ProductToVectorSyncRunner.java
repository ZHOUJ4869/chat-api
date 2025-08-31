// src/main/java/com/jz/ai/rag/ProductToVectorSyncRunner.java
package com.jz.ai.rag;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.jz.ai.domain.entity.Product;
import com.jz.ai.mapper.ProductMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class ProductToVectorSyncRunner implements CommandLineRunner {

    private final ProductMapper productMapper;
    private final VectorStore vectorStore;

    @Override
    public void run(String... args) {
        // 启动时同步少量数据打通链路（生产建议分页/增量）
        List<Product> list = productMapper.selectList(
                Wrappers.<Product>lambdaQuery().last("limit 50"));
        if (list == null || list.isEmpty()) return;

        List<Document> docs = new ArrayList<>();
        List<String> idsToDelete = new ArrayList<>();

        for (Product p : list) {
            String stableId = "product:" + p.getId(); // ★ 稳定主键（与Redis向量库键前缀拼接）

            // 文本内容：中文表述，利于语义检索
            String content = """
                    【商品】%s
                    【品牌】%s
                    【类目】%s
                    【价格】%s 元
                    【卖点】%s
                    """.formatted(
                    nvl(p.getTitle()),
                    nvl(p.getBrand()),
                    nvl(p.getCategory()),
                    p.getPrice() == null ? "-" : p.getPrice().toPlainString(),
                    nvl(p.getDescription())
            );

            // 元数据：后续可做过滤/直出
            Map<String, Object> meta = new HashMap<>();
            meta.put("productId", p.getId());
            meta.put("title",p.getTitle());
            meta.put("brand", p.getBrand());
            meta.put("stock",p.getStock());
            meta.put("category", p.getCategory());
            meta.put("price", p.getPrice() == null ? null : p.getPrice().doubleValue());
            meta.put("isActive", Boolean.TRUE.equals(p.getIsActive()));
            meta.put("url", p.getUrl());

            // ★ 用 Builder 指定 id（没有 setId）
            Document doc = Document.builder()
                    .id(stableId)         // 指定稳定ID
                    .text(content)        // 文本内容
                    .metadata(meta)       // 元数据
                    .build();

            docs.add(doc);
            idsToDelete.add(stableId);
        }

        // ★ 防重复：先删同ID再写入（部分 VectorStore 可能不支持 delete，容错处理）
        try {
            vectorStore.delete(idsToDelete);
        } catch (Throwable ignore) {
            // 某些实现可能没有 delete；忽略即可，依赖具体实现的 upsert/覆盖策略
        }

        // ★ 向量化并写入（由 Spring AI 自动做 embedding + 存储）
        vectorStore.add(docs);
    }

    private static String nvl(String s) { return s == null ? "" : s; }
}
