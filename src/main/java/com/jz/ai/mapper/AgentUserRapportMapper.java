package com.jz.ai.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.jz.ai.domain.entity.AgentUserRapport;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface AgentUserRapportMapper extends BaseMapper<AgentUserRapport> {

    /** 批量 upsert：用 MySQL ON DUPLICATE KEY UPDATE */
    int upsertBatch(@Param("list") List<AgentUserRapport> list);

}