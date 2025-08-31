package com.jz.ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jz.ai.domain.entity.AgentUserRapport;

import java.time.Duration;
import java.util.Map;

public interface AgentUserRapportService extends IService<AgentUserRapport> {
    AgentUserRapport getOrInit(Long agentId, Long userId);
    int bumpOnUserUtter(String chatId,Long agentId, Long userId, String userText,int retrieveSize);
    void decay(Long userId,Long agentId,int score);//
    Map<String,Integer> peekLastDims(Long agentId, Long userId);//
    void notifyViolation(Long agentId, Long userId, Duration cooldown);// 返回最新分数
}