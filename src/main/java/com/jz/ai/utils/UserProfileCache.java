package com.jz.ai.utils;



import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jz.ai.config.ProfileProperties;
import com.jz.ai.domain.entity.UserProfile;
import com.jz.ai.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserProfileCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper; //jackson的核心包
    private final UserProfileService userProfileService;
    private final ProfileProperties props;

    private String k(Long userId) {
        return props.getRedisKeyPrefix() + userId;
    }

    /** 读取画像（优先 Redis；miss 则 DB -> Redis） */
    public Map<String, Object> getOrLoad(Long userId) {
        try {
            String key = k(userId);
            String js = redis.opsForValue().get(key);
            if (js != null) {
                return mapper.readValue(js, new TypeReference<>() {});
            }
            UserProfile up = userProfileService.getById(userId);
            Map<String, Object> m = (up == null) ? new HashMap<>() : toPortraitJson(up);
            put(userId, m);
            return m;
        } catch (Exception e) {
            log.warn("profile getOrLoad failed: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /** 写回 Redis（带 TTL 或不过期） */
    public void put(Long userId, Map<String, Object> json) {
        try {
            String key = k(userId);
            String js = mapper.writeValueAsString(json);
            Duration ttl = props.getCacheTtl();
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                redis.opsForValue().set(key, js);
            } else {
                redis.opsForValue().set(key, js, ttl);
            }
        } catch (Exception e) {
            log.warn("profile put failed: {}", e.getMessage());
        }
    }

    /** 合并（current + delta）产生 merged */
    public Map<String, Object> merge(Map<String, Object> current, Map<String, Object> delta) {
//        合并两个map
        Map<String, Object> out = new LinkedHashMap<>();
        if (current != null) out.putAll(current);
        if (delta == null) return out;

        // 简单规则：标量覆盖，数组去重合并，map 递归合并
        delta.forEach((k, v) -> {
            Object old = out.get(k);
            if (v == null) return;
//            将v分成了三种情况1、列表 2、map 3、标量
            if (v instanceof List<?> nl) {
                Set<Object> set = new LinkedHashSet<>();
                if (old instanceof List<?> ol) set.addAll(ol);
                set.addAll(nl);
                out.put(k, new ArrayList<>(set));
            } else if (v instanceof Map<?, ?> nm && old instanceof Map<?, ?> om) {
                Map<String, Object> m = new LinkedHashMap<>();
                om.forEach((ok, ov) -> m.put(String.valueOf(ok), ov));
                nm.forEach((nk, nv) -> m.put(String.valueOf(nk), nv));
                out.put(k, m);
            } else {
                out.put(k, v); // 标量覆盖
            }
        });
        return out;
    }

    /** 将实体转“画像 JSON”（供注入/提示使用） */
    // 1) 画像 JSON -> 实体（持久化用）
    public UserProfile toEntity(Long userId, Map<String, Object> p) {
        UserProfile up = Optional.ofNullable(userProfileService.getById(userId))
                .orElseGet(() -> { UserProfile u = new UserProfile(); u.setUserId(userId); return u; });

        up.setName((String) p.getOrDefault("姓名", up.getName()));
        up.setGender((String) p.getOrDefault("性别", up.getGender()));

        // 生日：支持 YYYY-MM-DD / ISO8601
        LocalDate bd = parseLocalDate(p.get("生日"));
        if (bd != null) up.setBirthday(bd);

        up.setCity((String) p.getOrDefault("城市", up.getCity()));
        up.setDistrict((String) p.getOrDefault("区域", up.getDistrict()));
        up.setAddress((String) p.getOrDefault("地址", up.getAddress()));

        // 电话/邮箱：仅当模型提取到时覆盖且做规范化
        String phone = normalizePhone((String) p.get("电话"));
        if (phone != null) up.setPhone(phone);
        String email = normalizeEmail((String) p.get("邮箱"));
        if (email != null) up.setEmail(email);

        up.setBudgetRange((String) p.getOrDefault("预算", up.getBudgetRange()));
        up.setPreferences(castList(p.get("偏好"), up.getPreferences()));
        up.setDislikes(castList(p.get("忌避"), up.getDislikes()));
        up.setFamilyInfo(castMap(p.get("家庭"), up.getFamilyInfo()));
        up.setNote((String) p.getOrDefault("备注", up.getNote()));

        // toEntity 中新增/修改
        up.setAgeYears(parseInt(p.get("年龄"), up.getAgeYears()));
        up.setMaritalStatus(normalizeMarital((String) p.get("婚姻"))); // single/married/divorced/widowed/unknown
        Boolean hasKids = parseBool(p.get("是否有儿童"));
        if (hasKids != null) up.setHasChildren(hasKids);
        up.setChildrenCount(parseInt(p.get("子女数"), up.getChildrenCount()));

        up.setOccupation((String) p.getOrDefault("职业", up.getOccupation()));
        up.setIndustry((String) p.getOrDefault("行业", up.getIndustry()));
        up.setEmployer((String) p.getOrDefault("公司", up.getEmployer()));
        up.setEducationLevel(normalizeEdu((String) p.get("学历")));
        up.setIncomeRange((String) p.getOrDefault("收入", up.getIncomeRange()));
        up.setLivingStatus((String) p.getOrDefault("居住状况", up.getLivingStatus()));
        up.setResidenceType((String) p.getOrDefault("居住类型", up.getResidenceType()));
        up.setHomeAreaSqm(parseInt(p.get("房屋面积"), up.getHomeAreaSqm()));

        Boolean pet = parseBool(p.get("是否养宠"));
        if (pet != null) up.setPetOwner(pet);
        Boolean smart = parseBool(p.get("智能家居兴趣"));
        if (smart != null) up.setSmartHomeIntent(smart);

        up.setPreferredBrands(castList(p.get("品牌偏好"), up.getPreferredBrands()));
        up.setPreferredCates(castList(p.get("品类偏好"), up.getPreferredCates()));
        up.setAllergies(castList(p.get("过敏源"), up.getAllergies()));
        // 行为标签（可选）
        List<String> newTags = castList(p.get("行为标签"), null);
        if (newTags != null && !newTags.isEmpty()) {
            List<String> merged = mergeListNoDup(up.getBehaviorTags(), newTags);
            up.setBehaviorTags(merged);
        }
        return up;
    }
    private List<String> mergeListNoDup(List<String> oldL, List<String> addL) {
        LinkedHashSet<String> s = new LinkedHashSet<>();
        if (oldL != null) s.addAll(oldL);
        if (addL != null) for (Object o : addL) if (o != null) s.add(String.valueOf(o));
        return new ArrayList<>(s);
    }
    // 2) 实体 -> 画像 JSON（用于注入或调试查看）
    public Map<String, Object> toPortraitJson(UserProfile up) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (up.getName() != null) m.put("姓名", up.getName());
        if (up.getGender() != null) m.put("性别", up.getGender());
        if (up.getBirthday() != null) m.put("生日", up.getBirthday().toString());
        if (up.getCity() != null) m.put("城市", up.getCity());
        if (up.getDistrict() != null) m.put("区域", up.getDistrict());
        if (up.getAddress() != null) m.put("地址", up.getAddress());
        if (up.getPhone() != null) m.put("电话", up.getPhone());
        if (up.getEmail() != null) m.put("邮箱", up.getEmail());
        if (up.getBudgetRange() != null) m.put("预算", up.getBudgetRange());
        if (up.getPreferences() != null) m.put("偏好", up.getPreferences());
        if (up.getDislikes() != null) m.put("忌避", up.getDislikes());
        if (up.getFamilyInfo() != null) m.put("家庭", up.getFamilyInfo());
        if (up.getNote() != null) m.put("备注", up.getNote());
        // toPortraitJson 中新增（用于调试或安全注入视图的基础）
        if (up.getAgeYears() != null) m.put("年龄", up.getAgeYears());
        if (up.getMaritalStatus() != null) m.put("婚姻", up.getMaritalStatus());
        if (up.getHasChildren() != null) m.put("是否有儿童", up.getHasChildren());
        if (up.getChildrenCount() != null) m.put("子女数", up.getChildrenCount());
        if (up.getOccupation() != null) m.put("职业", up.getOccupation());
        if (up.getIndustry() != null) m.put("行业", up.getIndustry());
        if (up.getEmployer() != null) m.put("公司", up.getEmployer());
        if (up.getEducationLevel() != null) m.put("学历", up.getEducationLevel());
        if (up.getIncomeRange() != null) m.put("收入", up.getIncomeRange());
        if (up.getLivingStatus() != null) m.put("居住状况", up.getLivingStatus());
        if (up.getResidenceType() != null) m.put("居住类型", up.getResidenceType());
        if (up.getHomeAreaSqm() != null) m.put("房屋面积", up.getHomeAreaSqm());
        if (up.getPetOwner() != null) m.put("是否养宠", up.getPetOwner());
        if (up.getSmartHomeIntent() != null) m.put("智能家居兴趣", up.getSmartHomeIntent());
        if (up.getPreferredBrands() != null) m.put("品牌偏好", up.getPreferredBrands());
        if (up.getPreferredCates() != null) m.put("品类偏好", up.getPreferredCates());
        if (up.getAllergies() != null) m.put("过敏源", up.getAllergies());
        if (up.getBehaviorTags()!=null) m.put("行为标签",up.getBehaviorTags());
        return m;
    }

    // === 规范化辅助 ===
    private LocalDate parseLocalDate(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return null;
        try { return LocalDate.parse(s); } catch (Exception ignore) {}
        // ISO8601 日期时间 -> 取日期部分
        int t = s.indexOf('T');
        if (t > 0) {
            try { return LocalDate.parse(s.substring(0, t)); } catch (Exception ignore) {}
        }
        return null;
    }

    private String normalizePhone(String s) {
        if (s == null) return null;
        String digits = s.replaceAll("\\D+", "");
        return digits.isEmpty() ? null : digits; // 只保留数字
    }

    private String normalizeEmail(String s) {
        if (s == null) return null;
        s = s.trim().toLowerCase();
        return s.contains("@") ? s : null;
    }
    // 规范化/解析工具（加到 UserProfileCache 里）
    private Integer parseInt(Object v, Integer fallback) {
        if (v == null) return fallback;
        try { return Integer.parseInt(String.valueOf(v).replaceAll("[^0-9]", "")); }
        catch (Exception e) { return fallback; }
    }

    private Boolean parseBool(Object v) {
        if (v == null) return null;
        String s = String.valueOf(v).trim().toLowerCase();
        if (s.matches("^(1|true|是|有|y|yes)$")) return true;
        if (s.matches("^(0|false|否|无|n|no)$")) return false;
        return null;
    }

    private String normalizeMarital(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.matches("(?i)^(单身|未婚|single)$")) return "single";
        if (s.matches("(?i)^(已婚|married)$")) return "married";
        if (s.matches("(?i)^(离异|离婚|divorced)$")) return "divorced";
        if (s.matches("(?i)^(丧偶|widowed)$")) return "widowed";
        return "unknown";
    }

    private String normalizeEdu(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.matches("(?i)^(高中|high school)$")) return "高中";
        if (s.matches("(?i)^(大专|college)$")) return "大专";
        if (s.matches("(?i)^(本科|bachelor)$")) return "本科";
        if (s.matches("(?i)^(研究生|硕士|master)$")) return "研究生";
        if (s.matches("(?i)^(博士|phd|doctor)$")) return "博士";
        return "其他";
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object v, List<String> fallback) {
        if (v instanceof List<?> l) {
            List<String> r = new ArrayList<>();
            for (Object o : l) if (o != null) r.add(String.valueOf(o));
            return r;
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object v, Map<String, Object> fb) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> r = new LinkedHashMap<>();
            m.forEach((k, val) -> r.put(String.valueOf(k), val));
            return r;
        }
        return fb;
    }

    // （可选）把行为标签加进注入视图
    public Map<String, Object> buildInjectView(Map<String, Object> full) {
        Map<String, Object> v = new LinkedHashMap<>();
        if (full == null) return v;
        copyIfPresent(full, v, "预算", "偏好", "忌避", "家庭", "婚姻", "是否有儿童", "子女数",
                "职业", "行业", "学历", "收入",
                "居住状况", "居住类型", "房屋面积",
                "是否养宠", "智能家居兴趣",
                "品牌偏好", "品类偏好",
                "城市", "区域",
                "行为标签"); // <- 可选
        if (full.get("备注") != null) v.put("备注", full.get("备注"));
        return v;
    }
    // UserProfileCache.java
    public void putRaw(String key, String json, Duration ttl) {
        if (json == null) return;
        try {
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                redis.opsForValue().set(key, json);
            } else {
                redis.opsForValue().set(key, json, ttl);
            }
        } catch (Exception e) {
            log.warn("profile putRaw failed: {}", e.getMessage());
        }
    }
    public Optional<String> getRaw(String key) {
        try {
            return Optional.ofNullable(redis.opsForValue().get(key));
        } catch (Exception e) {
            log.warn("profile getRaw failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // 便捷：从 DB 刷新缓存
    public void refreshFromDb(Long userId) {
        try {
            UserProfile up = userProfileService.getById(userId);
            Map<String, Object> m = (up == null) ? new HashMap<>() : toPortraitJson(up);
            put(userId, m);
        } catch (Exception e) {
            log.warn("profile refreshFromDb failed: {}", e.getMessage());
        }
    }

    private void copyIfPresent(Map<String,Object> src, Map<String,Object> dst, String... keys) {
        for (String k : keys) if (src.containsKey(k)) dst.put(k, src.get(k));
    }

}
