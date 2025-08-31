// src/main/java/com/jz/ai/rag/RagQueryPipeline.java
package com.jz.ai.rag;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.jz.ai.config.RagQueryProperties;
import com.jz.ai.service.AllowedDictService;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.preretrieval.query.transformation.RewriteQueryTransformer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 预检索查询管道：
 * 1) 上下文压缩（把“这款/那个”指代变成明确实体）
 * 2) 多查询扩展（多个角度的等价查询）
 * 3) 查询重写（更适配向量库的检索用词）
 * 4) 相似检索（可带结构化过滤）
 * 5) 本地兜底过滤 + 去重 + 排序 + 取前K
 */
@Component
@RequiredArgsConstructor
public class RagQueryPipeline {

    private final VectorStore vectorStore;
    private final ChatClient.Builder chatClientBuilder; // 用同一套 ChatClient 做 query 级增强
    private final RagQueryProperties ragQueryProperties;
    // ⭐ 新增：从 DB+Redis 取允许集合；同义词仅写死示例
    private final AllowedDictService allowedDictService;
    /**
     * 主流程
     *
     * @param userQuery       用户原始问题
     * @param shortHistory    近几条对话（从 ConversationMemoryPort 取），建议只放 user/assistant 两类消息
     */
    public List<Document> searchWithAugmenters(
            String userQuery,
            List<Message> shortHistory
    ) {
//     * @param expandNum       多查询扩展个数（例如 3）
//     * @param perQueryTopK    每个子查询返回的相似文档数（例如 4）
//     * @param simThreshold    相似度阈值（0.0~1.0；越高越严格，例：0.5）
//     * @param finalTopK       合并去重后的最终 TopK（例如 6）
        // === 调用处（searchWithAugmenters 开头）===
        QueryRouting routing = routeAndExtract(userQuery, shortHistory);
        if (routing.getIntent() == QueryRouting.Intent.NON_PRODUCT) {
            return List.of(); // 不走 RAG
        }
        // 合并过滤器（前端 > 抽取；显式传入优先）
        Map<String,Object> mergedFilters = new HashMap<>(routing.getFilters().toMap());
        mergedFilters=normalizeFilters(mergedFilters);//兜底过滤下
        int expandNum= ragQueryProperties.getExpandNum();
        int perQueryTopK= ragQueryProperties.getPerQueryTopk();
        double simThreshold= ragQueryProperties.getSimThreshold();
        int finalTopK=ragQueryProperties.getFinalTopk();
        // 0) 先把原始 userQuery + history 封装成 Spring AI 的 Query（必须）
        Query base = Query.builder()
                .text(userQuery)
                .history(filterUserAssistantHistory(shortHistory)) // 只保留 USER/ASSISTANT，两类够用
                .context(Map.of())                                 // 需要传额外上下文时可放入这里
                .build();

        // 1) 上下文压缩（CompressionQueryTransformer 接收/返回的就是 Query）
        var compressor = CompressionQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder)
                .build();
        Query compressed = compressor.transform(base);

        // 2) 多查询扩展（得到多个 Query 变体；可 include 原始/压缩）（可以不用）
        var expander = MultiQueryExpander.builder()
                .chatClientBuilder(chatClientBuilder)
                .numberOfQueries(expandNum)
                .includeOriginal(true) // 把已经压缩过的 compressed 也作为一个候选
                .build();
        List<Query> expanded = expander.expand(compressed);

//        // 3) 查询重写（逐个 Query -> Query），使之更适配“向量库”检索
//        var rewriter = RewriteQueryTransformer.builder()
//                .chatClientBuilder(chatClientBuilder)
//                .build();
//        List<Query> rewritten = expanded.stream()
//                .map(rewriter::transform)
//                // 以文本去重，避免同义重复；保留第一条
//                .collect(Collectors.collectingAndThen(
//                        Collectors.toMap(Query::text, q -> q, (a, b) -> a, LinkedHashMap::new),
//                        m -> new ArrayList<>(m.values())
//                ));

        // 4) 针对每个重写后的查询做向量检索，聚合所有候选文档
        List<Document> all = new ArrayList<>();
        var serverExpr = buildFilterExpr(mergedFilters);
        for (Query q : expanded) {

            SearchRequest req = SearchRequest.builder()
                    .query(q.text())
                    .topK(perQueryTopK)
                    .similarityThreshold(simThreshold)
                    .filterExpression(serverExpr)
                    .build();

            // 4.1 如需“向量库侧过滤”，这里依据你的 Spring AI 版本可使用 withFilterExpression(...)
            //     为了兼容性，这里还是采用“取回后本地兜底过滤”的办法：
            List<Document> docs = vectorStore.similaritySearch(req);
            // 分数兜底 + 本地兜底过滤
            if (docs != null) {
                docs = docs.stream()
                        .filter(d -> Optional.ofNullable(d.getScore()).orElse(0.0) >= simThreshold)
                        .toList();
            }
            // 4.2 本地过滤（brand/category/isActive/priceMin/priceMax）
            docs = applyLocalFilters(docs, mergedFilters);

            all.addAll(docs);
        }

        // 5) 合并去重 + 简单排序 + 截断 TopK
        return dedupAndRank(all, finalTopK);
    }

    /**
     * 只保留 USER/ASSISTANT 两类消息（避免系统/工具消息污染文本）
     */
    private List<Message> filterUserAssistantHistory(List<Message> history) {
        if (history == null || history.isEmpty()) return List.of();
        return history.stream()
                .filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
                .map(m -> {
                    // 统一成 UserMessage/AssistantMessage（有些 Message 实现可能自定义）
                    if (m.getMessageType() == MessageType.USER) {
                        return new UserMessage(m.getText());
                    } else {
                        return new AssistantMessage(m.getText());
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * 从 Map 构造服务端过滤表达式：
     * 支持的键：brand, category, isActive, priceMin, priceMax
     * 约定：brand/category 建成 TAG 字段，price 建成 NUMERIC 字段
     */
    @Nullable
    private Filter.Expression buildFilterExpr(Map<String, Object> f) {
        if (f == null || f.isEmpty()) return null;

        var b = new FilterExpressionBuilder();
        List<Filter.Expression> parts = new ArrayList<>();

        // -------- brand（支持单值或集合 -> in）--------
        Object brandObj = f.get("brand");
        if (brandObj != null) {
            if (brandObj instanceof Collection<?> col && !col.isEmpty()) {
                List<Object> norms = Collections.singletonList(col.stream().map(this::normTag).filter(Objects::nonNull).toList());
                if (!norms.isEmpty()) parts.add(b.in("brand", norms).build());
            } else {
                String brand = normTag(brandObj);
                if (brand != null && !brand.isBlank()) parts.add(b.eq("brand", brand).build());
            }
        }

        // -------- category（支持单值或集合 -> in）--------
        Object catObj = f.get("category");
        if (catObj != null) {
            if (catObj instanceof Collection<?> col && !col.isEmpty()) {
                List<Object> norms = Collections.singletonList(col.stream().map(this::normTag).filter(Objects::nonNull).toList());
                if (!norms.isEmpty()) parts.add(b.in("category", norms).build());
            } else {
                String category = normTag(catObj);
                if (category != null && !category.isBlank()) parts.add(b.eq("category", category).build());
            }
        }

        // -------- isActive（布尔）--------
        Object activeObj = f.get("isActive");
        if (activeObj instanceof Boolean bool) {
            parts.add(b.eq("isActive", bool).build());
        } else if (activeObj != null) {
            // 字符串/数字也尝试解析
            Boolean bool = parseBoolean(activeObj);
            if (bool != null) parts.add(b.eq("isActive", bool).build());
        }

        // -------- price（数值区间）--------
        Double min = asDouble(f.get("priceMin"));
        Double max = asDouble(f.get("priceMax"));
        if (min != null && max != null) {
            if (min > max) { // 纠正反转
                double tmp = min; min = max; max = tmp;
            }
            parts.add(b.and(b.gte("price", min), b.lte("price", max)).build());
        } else if (min != null) {
            parts.add(b.gte("price", min).build());
        } else if (max != null) {
            parts.add(b.lte("price", max).build());
        }

        if (parts.isEmpty()) return null;
        return andAll(b, parts); // 把多个条件 AND 起来
    }

    /** 把多个 Expression 用 AND 连接成一个 Expression（注意 Op 包装） */
    @Nullable
    private Filter.Expression andAll(FilterExpressionBuilder b, List<Filter.Expression> parts) {
        if (parts == null || parts.isEmpty()) return null;
        if (parts.size() == 1) return parts.get(0);

        FilterExpressionBuilder.Op op = new FilterExpressionBuilder.Op(parts.get(0));
        for (int i = 1; i < parts.size(); i++) {
            op = b.and(op, new FilterExpressionBuilder.Op(parts.get(i)));
        }
        return op.build();
    }

    /** 归一化 TAG 值（去空白 + 小写）。TAG 一般大小写敏感，建议统一到小写入库与查询。*/
    @Nullable
    private String normTag(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        return s.toLowerCase(Locale.ROOT);
    }

    /** 宽松解析 Double（Number / String 均可） */
    @Nullable
    private Double asDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try {
            String s = String.valueOf(o).trim()
                    .replace("k", "000")
                    .replace("K", "000")
                    .replaceAll("[^0-9.\\-]", ""); // 去掉非数字符号（¥、元、空格等）
            if (s.isEmpty()) return null;
            return Double.parseDouble(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** 宽松解析 Boolean（true/false/1/0/yes/no/on/off） */
    @Nullable
    private Boolean parseBoolean(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        String s = String.valueOf(o).trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "true", "1", "yes", "y", "on" -> Boolean.TRUE;
            case "false", "0", "no", "n", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    /**
     * 结构化本地过滤（兜底）：brand/category/isActive/priceMin/priceMax
     */
    private List<Document> applyLocalFilters(List<Document> docs, Map<String, Object> filters) {
        if (docs == null || docs.isEmpty()) return docs;
        if (filters == null || filters.isEmpty()) return docs;

        String brand = (String) filters.get("brand");
        String category = (String) filters.get("category");
        Boolean isActive = (Boolean) filters.get("isActive");
        Double priceMin = castDouble(filters.get("priceMin"));
        Double priceMax = castDouble(filters.get("priceMax"));

        return docs.stream().filter(d -> {
            Map<String, Object> m = d.getMetadata() == null ? Map.of() : d.getMetadata();

            if (brand != null && !brand.equalsIgnoreCase(String.valueOf(m.get("brand")))) return false;
            if (category != null && !category.equalsIgnoreCase(String.valueOf(m.get("category")))) return false;

            if (isActive != null) {
                Object v = m.get("isActive");
                if (v instanceof Boolean b) {
                    if (!b.equals(isActive)) return false;
                }
            }

            if (priceMin != null || priceMax != null) {
                Double price = castDouble(m.get("price"));
                if (price != null) {
                    if (priceMin != null && price < priceMin) return false;
                    if (priceMax != null && price > priceMax) return false;
                }
            }
            return true;
        }).collect(Collectors.toList());
    }

    /**
     * 合并去重（按 productId；没有就按文本 hash）
     * 按 score(若有) 降序；没有 score 就保持合并顺序
     */
    private List<Document> dedupAndRank(List<Document> all, int finalTopK) {
        if (all == null || all.isEmpty()) return List.of();

        // 5.1 去重：优先产出靠前的
        LinkedHashMap<Object, Document> map = new LinkedHashMap<>();
        for (Document d : all) {
            Object pid = d.getMetadata() == null ? null : d.getMetadata().get("productId");
            Object key = (pid != null) ? pid : ((d.getText() == null) ? UUID.randomUUID() : d.getText().hashCode());
            map.putIfAbsent(key, d);
        }
        List<Document> merged = new ArrayList<>(map.values());

        // 5.2 排序：优先用 Document.score（不同 VectorStore 是否回填 score 取决于实现）
        try {
            merged.sort(Comparator.comparing((Document d) -> {
                try { return Optional.ofNullable(d.getScore()).orElse(0.0); } catch (Throwable e) { return 0.0; }
            }).reversed());
        } catch (Throwable ignore) {}

        // 5.3 截断
        if (finalTopK > 0 && merged.size() > finalTopK) {
            return merged.subList(0, finalTopK);
        }
        return merged;
    }

    private Double castDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return null; }
    }


    private QueryRouting routeAndExtract(String userQuery, List<Message> shortHistory) {
        String historyBlock = buildCompactHistory(shortHistory, 1200); // 限长，避免赘述

        String sys = buildRoutingSystemPrompt();


        String prompt = """
      最近对话（已脱敏、截断）：
      %s

      当前用户问题：
      %s
    """.formatted(historyBlock, userQuery);

        var client = chatClientBuilder.build();
        String resp = client.prompt()
                .system(sys)
                .user(prompt)
                .call()
                .content(); // 期望纯 JSON

        try {
            var mapper = JsonMapper.builder().build();
            var node = mapper.readTree(resp);

            var r = new QueryRouting();
            r.setIntent("PRODUCT_INFO".equalsIgnoreCase(node.path("intent").asText())
                    ? QueryRouting.Intent.PRODUCT_INFO : QueryRouting.Intent.NON_PRODUCT);

            var f = new QueryRouting.Filters();
            var filters = node.path("filters");
            if (!filters.isMissingNode()) {
                f.setBrand(asNullOrText(filters, "brand"));
                f.setCategory(asNullOrText(filters, "category"));
                if (filters.hasNonNull("isActive")) f.setIsActive(filters.get("isActive").asBoolean());
                if (filters.hasNonNull("priceMin")) f.setPriceMin(filters.get("priceMin").asDouble());
                if (filters.hasNonNull("priceMax")) f.setPriceMax(filters.get("priceMax").asDouble());
            }
            r.setFilters(f);
            return r;
        } catch (Exception e) {
            // 解析失败：保守降级
            var r = new QueryRouting();
            r.setIntent(QueryRouting.Intent.NON_PRODUCT);
            r.setFilters(new QueryRouting.Filters());
            return r;
        }
    }

    private static String buildCompactHistory(List<Message> history, int maxChars) {
        if (history == null || history.isEmpty()) return "(无)";
        // 只保留 USER/ASSISTANT，并做轻量格式化；从后往前拼，直至达到上限
        var filtered = history.stream()
                .filter(m -> m.getMessageType() == MessageType.USER || m.getMessageType() == MessageType.ASSISTANT)
                .map(m -> (m.getMessageType() == MessageType.USER ? "U: " : "A: ") + safeLine(m.getText()))
                .toList();

        StringBuilder sb = new StringBuilder();
        for (int i = filtered.size() - 1; i >= 0; i--) {
            String line = filtered.get(i);
            if (sb.length() + line.length() + 1 > maxChars) break;
            sb.insert(0, line + "\n");
        }
        return sb.length() == 0 ? "(无)" : sb.toString().trim();
    }

    private static String safeLine(String s) {
        if (s == null) return "";
        // 去掉可能干扰 JSON 的围栏/代码块提示，减少模型跑偏
        s = s.replace("```", "``` ");
        // 简单折行与裁剪
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > 300) s = s.substring(0, 300) + "…";
        return s;
    }

    private static String asNullOrText(com.fasterxml.jackson.databind.JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    // 规范化：把 brand/category 映射到规范值；不在允许集合则置 null
    private Map<String,Object> normalizeFilters(Map<String,Object> f) {
        if (f == null) return Map.of();
        Map<String,Object> out = new HashMap<>(f);

        // brand
        Object brandObj = f.get("brand");
        if (brandObj != null) {
            String canon = canonBrand(String.valueOf(brandObj));
            out.put("brand", canon);
        }

        // category
        Object catObj = f.get("category");
        if (catObj != null) {
            String canon = canonCategory(String.valueOf(catObj));
            out.put("category", canon);
        }

        // 价格纠偏（min>max 交换）
        Double min = asDouble(f.get("priceMin"));
        Double max = asDouble(f.get("priceMax"));
        if (min != null || max != null) {
            if (min != null && max != null && min > max) {
                double t = min; min = max; max = t;
            }
            out.put("priceMin", min);
            out.put("priceMax", max);
        }
        return out;
    }

    private String canonBrand(String raw) {
        return allowedDictService.canonBrand(raw);
    }

    private String canonCategory(String raw) {
        return allowedDictService.canonCategory(raw);
    }
    // —— 动态构建路由 System Prompt —— //
    private String buildRoutingSystemPrompt() {
        String brandAllowedJson = toJsonArray(allowedDictService.allowedBrands());
        String catAllowedJson   = toJsonArray(allowedDictService.allowedCategories());
        String brandSynJson     = toGroupedSynonymJson(allowedDictService.allowedBrands(),
                allowedDictService.brandAliasToCanon());
        String catSynJson       = toGroupedSynonymJson(allowedDictService.allowedCategories(),
                allowedDictService.categoryAliasToCanon());

        return """
        你是“查询路由 + 过滤器抽取器”。结合“最近对话”判断当前问题是否与【商品信息/价格/品牌/类目/推荐/比较/库存/购买】相关，
        并抽取过滤器。只输出严格 JSON（UTF-8，无注释、无多余文本、不要```）。
        
        【输出 Schema】
        {
          "intent": "PRODUCT_INFO" | "NON_PRODUCT",
          "filters": {
            "brand": string|null,
            "category": string|null,
            "isActive": boolean|null,
            "priceMin": number|null,
            "priceMax": number|null
          }
        }
        
        【允许值（规范值，必须原样输出）】
        brand_allowed = %s
        category_allowed = %s
        
        【同义词→规范值（仅示例；若遇到未列但能判断归属，也要输出规范值；否则 null）】
        brand_synonyms = %s
        category_synonyms = %s
        
        【规则】
        1) 若用户使用“这个/那款/它”等指代，请结合“最近对话”还原语境再判断。
        2) 价格表述（如“2k-4k”“不到3000”“三千左右”“2~4千”）要归一化到 priceMin/priceMax（单位=人民币，k=1000）。
        3) 只允许输出 brand_allowed 与 category_allowed 中的规范值；若无法确定，置为 null。
        4) 不确定是否与商品相关，则 intent=NON_PRODUCT，filters 全为 null。
        5) 只输出 JSON，不要多余文字。
        
        【示例】
        Q: 我想看华子手机两千到四千的
        → {"intent":"PRODUCT_INFO","filters":{"brand":"huawei","category":"phone","isActive":null,"priceMin":2000,"priceMax":4000}}
        
        Q: 索尼降噪耳机 1k 以下有吗
        → {"intent":"PRODUCT_INFO","filters":{"brand":"sony","category":"headphone","isActive":null,"priceMin":null,"priceMax":1000}}
        
        Q: 最近的新闻是什么
        → {"intent":"NON_PRODUCT","filters":{"brand":null,"category":null,"isActive":null,"priceMin":null,"priceMax":null}}
        """.formatted(brandAllowedJson, catAllowedJson, brandSynJson, catSynJson);
    }

    private static String toJsonArray(Collection<String> items) {
        return items.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> "\"" + s.toLowerCase(Locale.ROOT) + "\"")
                .collect(Collectors.joining(",", "[", "]"));
    }

    /** 将 alias->canon 转为 canon->[alias...]（包含 canon 本身） */
    private static String toGroupedSynonymJson(Set<String> allowed, Map<String,String> alias2canon) {
        Map<String, LinkedHashSet<String>> group = new LinkedHashMap<>();
        for (String canon : allowed) {
            group.put(canon, new LinkedHashSet<>(List.of(canon)));
        }
        alias2canon.forEach((alias, canon) -> {
            if (canon == null) return;
            String c = canon.toLowerCase(Locale.ROOT).trim();
            if (allowed.contains(c)) {
                group.computeIfAbsent(c, k -> new LinkedHashSet<>())
                        .add(alias.trim().toLowerCase(Locale.ROOT));
            }
        });
        return group.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + toJsonArray(e.getValue()))
                .collect(Collectors.joining(",", "{", "}"));
    }
// 你的 asDouble 可以沿用；记得把 “k/K” 处理也考虑进去（你之前已实现）

}
