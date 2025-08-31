package com.jz.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ChatLmsCursorMapper {
    @Select("SELECT last_to_id FROM chat_lms_cursor WHERE chat_id = #{chatId}")
    Long selectLastToId(@Param("chatId") String chatId);

    @Insert("""
        INSERT INTO chat_lms_cursor (chat_id, last_to_id, last_to_ts, last_window_len)
        VALUES (#{chatId}, #{lastToId}, #{lastToTs}, #{windowLen})
        ON DUPLICATE KEY UPDATE last_to_id = VALUES(last_to_id),
                                last_to_ts = VALUES(last_to_ts),
                                last_window_len = VALUES(last_window_len),
                                updated_at = CURRENT_TIMESTAMP
    """)
    int upsert(@Param("chatId") String chatId,
               @Param("lastToId") long lastToId,
               @Param("lastToTs") long lastToTs,
               @Param("windowLen") int windowLen);
}
