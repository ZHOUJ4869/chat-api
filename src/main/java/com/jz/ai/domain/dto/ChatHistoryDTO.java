package com.jz.ai.domain.dto;

import lombok.*;
import java.util.List;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ChatHistoryDTO {
    /** 本次查询天数 */
    private int days;
    /** 总消息条数（本次返回内） */
    private int total;
    /** 按天分组的区块 */
    private List<ChatDayDTO> sections;
}
