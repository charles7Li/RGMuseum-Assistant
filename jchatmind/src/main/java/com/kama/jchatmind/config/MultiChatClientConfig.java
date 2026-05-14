package com.kama.jchatmind.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MultiChatClientConfig {
    // deepseek
    @Bean("deepseek-chat")
    // 创建 DeepSeek 对应的 ChatClient Bean。
    public ChatClient deepSeekChatClient(DeepSeekChatModel deepSeekChatModel) {
        return ChatClient.create(deepSeekChatModel);
    }

    // zhipuai
    @Bean("glm-4.6")
    // 创建智谱模型对应的 ChatClient Bean。
    public ChatClient zhiPuAiChatClient(ZhiPuAiChatModel zhiPuAiChatModel) {
        return ChatClient.create(zhiPuAiChatModel);
    }

    @Bean("qwen3.6-plus")
    // 创建 Qwen3（通义千问）对应的 ChatClient Bean。
    public ChatClient qwen3ChatClient(OpenAiChatModel qwenChatModel){
        return ChatClient.create(qwenChatModel);
    }
}
