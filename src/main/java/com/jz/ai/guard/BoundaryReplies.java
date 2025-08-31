package com.jz.ai.guard;


public final class BoundaryReplies {
    private BoundaryReplies(){}

    public static String privacyPersonal() {
        return "为了安全规范，我这边不方便聊个人隐私哈。如有订单/售后/选品相关，直接告诉我，我马上帮你处理～(微笑)";
    }
    public static String romantic() {
        return "感谢关心～我们先把业务相关的事弄好哈；订单/售后我这边优先处理。(微笑)";
    }
    public static String sexualOrIllegal() {
        return "抱歉，我无法处理这类内容。如需业务支持请直接说具体问题，我马上为您跟进。(疑问)";
    }
    public static String profanity() {
        return "理解您的着急，我先帮您把事情处理好；有什么问题直接说，我这边跟进。(汗)";
    }
}
