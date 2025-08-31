package com.jz.ai.config;


import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "chat.moderation")
public class ModerationProperties {

    /** 轻度越界允许提醒并拉回的最低亲密度阈值（原 40） */
    private int lightReplyFloor = 40;

    /** 中度越界允许提醒并拉回的最低亲密度阈值（原 60） */
    private int midReplyFloor = 60;

    /** 命中“沉默”时的亲密度下调分值 */
    private int silenceDegradePoints = 8;

    /** 给“边界提醒”时的亲密度下调分值（可选，默认不降） */
    private int boundaryDegradePoints = 4;

}
