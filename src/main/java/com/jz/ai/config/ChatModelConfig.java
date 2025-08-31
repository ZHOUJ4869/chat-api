package com.jz.ai.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.function.FunctionCallbackResolver;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Configuration
public class ChatModelConfig {

    private static final List<String> CHAT_MODELS = List.of(
            "qwen-max",
            "qwen-plus",
            "qwen-turbo",
            "qwen-long",
            "qwen3-14b",
            "qwen3-32b",
            "qwen2.5-14b-instruct",
            "qwen2.5-7b-instruct"
    );

    @Bean
    @Primary
    public EmbeddingModel createEmbeddingModel(DashScopeApi dashScopeApi,@Value("${rag.embedding.model}") String embeddingModelName){
        return  new DashScopeEmbeddingModel(dashScopeApi, MetadataMode.EMBED,
                DashScopeEmbeddingOptions.builder()
                        .withModel(embeddingModelName)
                        .build());
    }


    @Bean(name = "customChatModelMap")
    public Map<String, ChatModel> modelMap(DashScopeApi dashScopeApi
    , @Value("${spring.ai.dashscope.chat.options.temperature}") double temperature,
                                           @Value("${spring.ai.dashscope.chat.options.top-p}") double topP) {
        return CHAT_MODELS.stream().collect(Collectors.toMap(
                Function.identity(),
                model -> new DashScopeChatModel(dashScopeApi,
                        DashScopeChatOptions.builder()
                                .withModel(model)
                                .withTemperature(temperature)
                                .withTopP(topP)
                                .build())
        ));
    }
}

