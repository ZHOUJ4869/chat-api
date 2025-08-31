package com.jz.ai.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.jz.ai.domain.entity.SupportAgent;

public interface SupportAgentService extends IService<SupportAgent> {
    SupportAgent getDefaultAgent();
    SupportAgent getByIdCached(Long id);
    SupportAgent updateAgent(SupportAgent incoming);
    SupportAgent setDefault(Long id);
    void deleteAgent(Long id);
}
