package com.jz.ai.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.jz.ai.domain.entity.SupportAgent;
import com.jz.ai.mapper.SupportAgentMapper;
import com.jz.ai.service.SupportAgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.CachePut;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupportAgentServiceImpl
        extends ServiceImpl<SupportAgentMapper, SupportAgent>
        implements SupportAgentService {

    private final CacheManager cacheManager;

    /** 缓存名字统一用一个常量，避免写错 */
    private static final String AGENTS = "agents";

    private Cache agentsCache() {
        return cacheManager.getCache(AGENTS);
    }

    /** 读取：默认客服（缓存 key='default'），并发下用 sync=true 防击穿 */
    @Override
    @Cacheable(cacheNames = AGENTS, key = "'default'", sync = true)
    public SupportAgent getDefaultAgent() {
        SupportAgent a = this.lambdaQuery()
                .eq(SupportAgent::getIsDefault, 1)
                .last("limit 1")
                .one();
        if (a == null) {
            throw new IllegalStateException("没有可用客服，请先初始化 support_agent");
        }
        return a;
    }

    /** 读取：按 id 缓存（key='id:{id}'） */
    @Override
    @Cacheable(cacheNames = AGENTS, key = "'id:' + #id", sync = true)
    public SupportAgent getByIdCached(Long id) {
        return getById(id);
    }

    /** 更新一个客服；刷新该 id 的缓存，若是默认客服，顺手驱逐 'default' */
    @Override
    @Transactional
    @CachePut(cacheNames = AGENTS, key = "'id:' + #result.id", unless = "#result == null")
    public SupportAgent updateAgent(SupportAgent incoming) {
        // 落库
        updateById(incoming);
        // 回读最新（确保包含 DB 触发器、填充字段）
        SupportAgent fresh = getById(incoming.getId());

        // 如果这个对象被设为默认，驱逐 'default'，让下次重新计算
        if (fresh != null && Integer.valueOf(1).equals(fresh.getIsDefault())) {
            agentsCache().evict("default");
        }
        return fresh;
    }

    /** 切换默认客服：清掉旧默认、设置新默认；刷新新 id 缓存并驱逐 'default' */
    @Override
    @Transactional
    public SupportAgent setDefault(Long id) {
        // 取消旧默认
        this.lambdaUpdate()
                .eq(SupportAgent::getIsDefault, 1)
                .set(SupportAgent::getIsDefault, 0)
                .update();

        // 设置新默认
        this.lambdaUpdate()
                .eq(SupportAgent::getId, id)
                .set(SupportAgent::getIsDefault, 1)
                .update();

        // 回读并刷新缓存
        SupportAgent fresh = getById(id);
        if (fresh != null) {
            agentsCache().put("id:" + id, fresh);
        }
        // 驱逐 'default'，下次 getDefaultAgent() 会重新加载
        agentsCache().evict("default");
        return fresh;
    }

    /** 删除客服：删库并清理对应缓存；若删的是默认客服，顺带删 'default' */
    @Override
    @Transactional
    public void deleteAgent(Long id) {
        SupportAgent before = getById(id);
        removeById(id);

        // 清理该 id 的缓存
        agentsCache().evict("id:" + id);

        // 如果删除的是默认客服，清理 'default'
        if (before != null && Integer.valueOf(1).equals(before.getIsDefault())) {
            agentsCache().evict("default");
        }
    }
}
