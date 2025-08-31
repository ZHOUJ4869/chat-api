package com.jz.ai.service;

public interface BehaviorSignalsService {
    /** 返回适合注入 System 的紧凑 JSON 字符串（无则返回空串） */
    String buildSignalsJson(Long userId);
}