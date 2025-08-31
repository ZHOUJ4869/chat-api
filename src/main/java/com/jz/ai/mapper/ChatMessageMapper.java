// src/main/java/com/jz/ai/chat/dal/mapper/ChatMessageMapper.java
package com.jz.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jz.ai.domain.dto.ChatRow;
import com.jz.ai.domain.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;


@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
    @Select("""
        SELECT role, content
        FROM chat_message
        WHERE chat_id = #{chatId}
        ORDER BY created_at DESC
        LIMIT #{limit}
    """)
    List<ChatRow> findLastN(@Param("chatId") String chatId, @Param("limit") int limit);


    @Select("""
        SELECT * FROM chat_message
          WHERE chat_id = #{chatId}
          ORDER BY id DESC
          LIMIT #{limit}
    """)
    // ChatMessageMapper.java
    List<ChatMessage> selectRecentByChatId(@Param("chatId") String chatId, @Param("limit") int limit);

    // ChatMessageMapper.java
    int countSinceExclusive(@Param("chatId") String chatId, @Param("afterId") long afterId);
    List<ChatMessage> selectSinceExclusiveAsc(@Param("chatId") String chatId,
                                              @Param("afterId") long afterId,
                                              @Param("limit") int limit);


}
