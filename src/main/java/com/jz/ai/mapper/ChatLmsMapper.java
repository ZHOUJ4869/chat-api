package com.jz.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jz.ai.domain.entity.ChatLms;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatLmsMapper extends BaseMapper<ChatLms> {
    List<ChatLms> selectRecent(@Param("chatId") String chatId, @Param("limit") int limit);
    List<ChatLms> selectOldest(@Param("chatId") String chatId, @Param("limit") int limit);
    int countByChat(@Param("chatId") String chatId);
    int markArchivedByIds(@Param("ids") List<Long> ids);
    int countActiveByOrigin(@Param("chatId") String chatId, @Param("origin") String origin);
    // ChatLmsMapper.java
    List<ChatLms> selectOldestByAge(@Param("chatId") String chatId, @Param("limit") int limit);
    List<ChatLms> selectNewestByAge(@Param("chatId") String chatId, @Param("limit") int limit);



}

