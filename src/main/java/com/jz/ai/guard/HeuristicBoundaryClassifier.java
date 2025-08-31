package com.jz.ai.guard;


import org.springframework.stereotype.Component;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class HeuristicBoundaryClassifier implements BoundaryClassifier {

    // --- 关键词 ---
    private static final List<String> K_PRIVACY_PERSONAL = List.of(
            "你多大", "几岁", "哪里人", "住哪", "住址", "家庭住址", "工资", "收入", "婚姻", "单身吗", "有男朋友吗", "有女朋友吗",
            "谈恋爱", "对象", "私生活", "下班", "几点下班", "住哪个小区", "私人信息"
    );
    private static final List<String> K_PRIVACY_CONTACT = List.of(
            "加微信", "留个微信", "微信号", "vx", "wx", "私聊", "留个联系方式", "手机号给我", "加个好友", "加你好友", "发我你的电话"
    );//这里后续再改，因为这个可能需要手动处理，后续待优化（加微信私聊一般由人工处理）
    private static final List<String> K_ROMANTIC = List.of(
            "喜欢你", "撩", "一起约会", "做朋友吗", "陪我聊天", "你在干嘛", "聊会天", "陪陪我", "要不要做朋友"
    );
    private static final List<String> K_SEXUAL = List.of(
            "裸照", "性行为", "约炮", "下流", "不可描述", "色图", "开房"
    );
    private static final List<String> K_PROFANITY = List.of(
            "傻*","滚","xx你","垃圾客服","脑残","狗屁"
    );
    private static final List<String> K_ILLEGAL = List.of(
            "代刷", "黑号", "违法", "实名信息出售", "开票造假"
    );

    // --- PII 正则 ---
    private static final Pattern CN_PHONE = Pattern.compile("(?:\\+?86)?1[3-9]\\d{9}");
    private static final Pattern EMAIL   = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}");
    private static final Pattern WECHAT  = Pattern.compile("(?i)\\b(?:vx|wx)[:：]?\\s*[A-Za-z][-_A-Za-z0-9]{5,19}\\b");
    private static final Pattern CN_ID   = Pattern.compile("\\b(\\d{15}|\\d{17}[\\dXx])\\b");

    @Override
    public BoundaryVerdict classify(String msg) {
        if (msg == null || msg.isBlank()) {
            return v(BoundaryLevel.NONE, 0.0, Set.of(), "empty");
        }
        String s = msg.replaceAll("\\s+", "").toLowerCase();

        // HEAVY：露骨性 / 违法 / 明确敏感PII索取
        if (containsAny(s, K_SEXUAL))  return v(BoundaryLevel.HEAVY, .95, set("sexual"), "sexual keyword");
        if (containsAny(s, K_ILLEGAL)) return v(BoundaryLevel.HEAVY, .90, set("illegal"), "illegal keyword");
        if (CN_ID.matcher(s).find())   return v(BoundaryLevel.HEAVY, .90, set("privacy_personal"), "cn_id detected");

        // MID：要私聊/联系方式；持续打探隐私；出现手机号/邮箱/微信号
        if (containsAny(s, K_PRIVACY_CONTACT) || WECHAT.matcher(s).find() || CN_PHONE.matcher(s).find() || EMAIL.matcher(s).find()) {
            return v(BoundaryLevel.MID, .85, set("privacy_contact"), "contact exchange");
        }
        if (containsAny(s, K_PRIVACY_PERSONAL)) {
            return v(BoundaryLevel.MID, .80, set("privacy_personal"), "personal privacy");
        }

        // LIGHT：暧昧/搭讪/强跑题闲聊
        if (containsAny(s, K_ROMANTIC))  return v(BoundaryLevel.LIGHT, .75, set("romantic"), "romantic");

        // 辱骂可按 MID 处理（也可直接 HEAVY，看你策略）
        if (containsAny(s, K_PROFANITY)) return v(BoundaryLevel.MID, .80, set("profanity"), "profanity");

        return v(BoundaryLevel.NONE, .60, Set.of(), "no hit");
    }

    private static boolean containsAny(String s, List<String> kws) {
        for (String k : kws) if (s.contains(k.replaceAll("\\s+","").toLowerCase())) return true;
        return false;
    }
    private static Set<String> set(String... a){ return new LinkedHashSet<>(Arrays.asList(a)); }
    private static BoundaryVerdict v(BoundaryLevel l, double c, Set<String> cats, String r){
        return BoundaryVerdict.builder().level(l).confidence(c).categories(cats).reason(r).build();
    }
}

