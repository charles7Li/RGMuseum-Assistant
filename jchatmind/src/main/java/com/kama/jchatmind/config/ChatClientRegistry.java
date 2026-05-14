package com.kama.jchatmind.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class ChatClientRegistry {

    private final Map<String, ChatClient> chatClients;

    // 注入并缓存所有已注册的 ChatClient。
    public ChatClientRegistry(Map<String, ChatClient> chatClients) {
        this.chatClients = chatClients;
    }

    // 按模型键名获取对应 ChatClient 实例。
    public ChatClient get(String key) {
        return chatClients.get(key);
    }
}
