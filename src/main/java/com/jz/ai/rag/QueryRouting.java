// src/main/java/com/jz/ai/rag/QueryRouting.java
package com.jz.ai.rag;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class QueryRouting {
    public enum Intent { PRODUCT_INFO, NON_PRODUCT }
    private Intent intent;
    private Filters filters = new Filters();

    @Data
    public static class Filters {
        private String brand;
        private String category;
        private Boolean isActive;
        private Double priceMin;
        private Double priceMax;
        // 允许字段为 null；只 put 非空项，避免 NPE
        public Map<String,Object> toMap() {
            Map<String,Object> m = new HashMap<>();
            if (brand    != null) m.put("brand", brand);
            if (category != null) m.put("category", category);
            if (isActive != null) m.put("isActive", isActive);
            if (priceMin != null) m.put("priceMin", priceMin);
            if (priceMax != null) m.put("priceMax", priceMax);
            return m;
        }
    }
}
