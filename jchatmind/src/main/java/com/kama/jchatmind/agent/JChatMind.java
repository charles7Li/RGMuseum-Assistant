package com.kama.jchatmind.agent;

import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class JChatMind {
    private String agentId;

    private String name;

    private String description;

    private String systemPrompt;

    private ChatClient chatClient;

    private AgentState agentState;

    private List<ToolCallback> availableTools;

    private List<KnowledgeBaseDTO> availableKbs;

    private ToolCallingManager toolCallingManager;

    private ChatMemory chatMemory;

    private String chatSessionId;

    private static final Integer MAX_STEPS = 20;
    private static final String DEFAULT_PUBLIC_BASE_URL = "http://localhost:8080";
    private static final Pattern IMAGE_MARKDOWN_PATTERN =
            Pattern.compile("!\\[[^\\]]*]\\((?:https?://[^\\s)]+|/api/rag/images/content/[0-9a-fA-F-]+)\\)");
    private static final Pattern RELATED_IMAGE_LABEL_PATTERN =
            Pattern.compile("(?im)^\\s*(Related Images|related images)\\s*:\\s*$");
    private static final Pattern REASONING_LEAK_PATTERN = Pattern.compile(
            "(?is)(the user is asking|i have used the .*tool|the search results returned|"
                    + "none of these results|therefore, the evidence is insufficient|"
                    + "according to rule\\s*\\d+|i will formulate the response|response\\s*:)"
    );
    private static final Pattern RESPONSE_MARKER_PATTERN = Pattern.compile("(?is)\\bresponse\\s*:\\s*");
    private static final String NO_EVIDENCE_MARKER = "retrievalStatus=no_text_evidence";
    private static final String RISK_BLOCK_MARKER = "riskStatus=blocked";
    private static final String NO_EVIDENCE_REFUSAL = "No reliable evidence in knowledge base.";
    private static final String RISK_BLOCK_REFUSAL =
            "I can闂佺偨鍎查悰?help with harmful or illegal requests. Please ask a safe, museum-related question.";

    private static final Integer DEFAULT_MAX_MESSAGES = 20;

    private ChatOptions chatOptions;

    private SseService sseService;

    private ChatMessageConverter chatMessageConverter;

    private ChatMessageFacadeService chatMessageFacadeService;

    private ChatResponse lastChatResponse;
    private String runtimeMode = "fast";
    private int maxSteps = MAX_STEPS;
    private boolean planModeEnabled = false;
    private boolean planGenerated = false;
    private String currentPlan = "";

    private final List<ChatMessageDTO> pendingChatMessages = new ArrayList<>();

    public JChatMind() {
    }

    public JChatMind(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     ChatClient chatClient,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter
    ) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;

        this.chatClient = chatClient;

        this.availableTools = availableTools;
        this.availableKbs = availableKbs;

        this.chatSessionId = chatSessionId;
        this.sseService = sseService;

        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;

        this.agentState = AgentState.IDLE;

        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();
        this.chatMemory.add(chatSessionId, memory);

        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        this.toolCallingManager = ToolCallingManager.builder().build();
    }
    public JChatMind(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     ChatClient chatClient,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter,
                     String runtimeMode,
                     Integer maxSteps
    ) {
        this(agentId, name, description, systemPrompt, chatClient, maxMessages, memory,
                availableTools, availableKbs, chatSessionId, sseService, chatMessageFacadeService, chatMessageConverter);
        this.runtimeMode = runtimeMode == null ? "fast" : runtimeMode.trim().toLowerCase();
        this.maxSteps = maxSteps == null ? MAX_STEPS : Math.max(1, maxSteps);
        initPlanMode();
    }

    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\\n\\n[ToolCalling] no tool calls");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1,
                            call.name(),
                            call.arguments()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }


    private void saveMessage(Message message) {
        ChatMessageDTO.ChatMessageDTOBuilder builder = ChatMessageDTO.builder();
        if (message instanceof AssistantMessage assistantMessage) {
            ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(sanitizeAssistantContent(assistantMessage.getText()))
                    .sessionId(this.chatSessionId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(assistantMessage.getToolCalls())
                            .build())
                    .build();
            CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
            chatMessageDTO.setId(chatMessage.getChatMessageId());
            pendingChatMessages.add(chatMessageDTO);
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(this.chatSessionId)
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(toolResponse)
                                .build())
                        .build();
                CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
                chatMessageDTO.setId(chatMessage.getChatMessageId());
                pendingChatMessages.add(chatMessageDTO);
            }
        } else {
            throw new IllegalArgumentException("Unsupported message type: " + message.getClass().getName());
        }
    }

    private void refreshPendingMessages() {
        for (ChatMessageDTO message : pendingChatMessages) {
            ChatMessageVO vo = chatMessageConverter.toVO(message);
            SseMessage sseMessage = SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .message(vo)
                            .build())
                    .metadata(SseMessage.Metadata.builder()
                            .chatMessageId(message.getId())
                            .build())
                    .build();
            sseService.send(this.chatSessionId, sseMessage);
        }
        pendingChatMessages.clear();
    }
    private String sanitizeAssistantContent(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String safeText = stripReasoningLeak(text);
        if (!StringUtils.hasText(safeText)) {
            return "";
        }
        String normalized = safeText.replaceAll(
                "\\(https?://localhost:3000(/api/rag/images/content/[0-9a-fA-F-]+)\\)",
                "(" + DEFAULT_PUBLIC_BASE_URL + "$1)"
        ).replaceAll(
                "\\((/api/rag/images/content/[0-9a-fA-F-]+)\\)",
                "(" + DEFAULT_PUBLIC_BASE_URL + "$1)"
        );

        Matcher matcher = IMAGE_MARKDOWN_PATTERN.matcher(normalized);
        Set<String> imageMarkdown = new LinkedHashSet<>();
        while (matcher.find()) {
            imageMarkdown.add(matcher.group());
        }

        String cleaned = IMAGE_MARKDOWN_PATTERN.matcher(normalized).replaceAll("");
        cleaned = RELATED_IMAGE_LABEL_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n").trim();

        if (imageMarkdown.isEmpty()) {
            return cleaned;
        }
        List<String> selectedImages = imageMarkdown.stream()
                .limit(3)
                .collect(Collectors.toList());
        String imageBlock = String.join("\n", selectedImages);
        if (!StringUtils.hasText(cleaned)) {
            return imageBlock;
        }
        return cleaned + "\n\n" + imageBlock;
    }

    private String stripReasoningLeak(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.trim();
        Matcher responseMarkerMatcher = RESPONSE_MARKER_PATTERN.matcher(normalized);
        if (responseMarkerMatcher.find()) {
            String tail = normalized.substring(responseMarkerMatcher.end()).trim();
            if ((tail.startsWith("\"") && tail.endsWith("\"")) || (tail.startsWith("'") && tail.endsWith("'"))) {
                tail = tail.substring(1, tail.length() - 1).trim();
            }
            return tail;
        }
        if (REASONING_LEAK_PATTERN.matcher(normalized).find()) {
            return "";
        }
        return normalized;
    }

    private String truncateForLog(String text, int maxLength) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        if (maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private void initPlanMode() {
        String latestUserQuery = latestUserQuery();
        this.planModeEnabled = shouldUsePlanMode(latestUserQuery);
        this.planGenerated = false;
        this.currentPlan = "";
        if (this.planModeEnabled) {
            log.info("Plan-ReAct enabled for session={}, mode={}, query={}",
                    this.chatSessionId, this.runtimeMode, latestUserQuery);
        }
    }

    private String latestUserQuery() {
        List<Message> messages = this.chatMemory.get(this.chatSessionId);
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof UserMessage userMessage) {
                return userMessage.getText();
            }
        }
        return "";
    }

    private boolean shouldUsePlanMode(String query) {
        if (!"deep".equals(this.runtimeMode) || !StringUtils.hasText(query)) {
            return false;
        }
        String normalized = query.trim().toLowerCase();
        if (normalized.length() >= 40) {
            return true;
        }
        return containsAny(normalized,
                "step", "first", "then", "compare", "difference", "plan", "flow", "multimodal", "retrieval", "步骤", "流程");
    }

    private boolean containsAny(String text, String... keywords) {
        if (!StringUtils.hasText(text) || keywords == null || keywords.length == 0) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private void generatePlan() {
        this.agentState = AgentState.PLANNING;
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        String planPrompt = """
                婵炶揪绲挎慨鐢割敆濠婂牆鎹堕柕濞垮€涚粈瀣煕濡や焦鐨戝褎瀵ч敍鍐幢濞戞瑱绱紓浣圭⊕閺屟囧箲閵忋倕绀夐柍銉ュ暱閻撴垿鎮楃憴鍕暡濠⒀呮櫕閹澘鐣濋崘鎯ф倎闂佸憡甯楃敮鍥焵?                闁荤姴娲ら崲鏌ュ储濞戞氨纾兼繛鍡楃箰濮ｅ绱掗悩顐壕濠电偠寮撳ù鍥敇閹间礁绀嗛柟铏瑰仧缁€澶愭煕濮橆剚鎹ｉ柣銏㈢帛濞碱亪顢欑粵瀣还闂佸憡鍔曠粔鍨櫠閻ｅ本鍋橀悘鐐靛劦閸?                闁荤喐娲戦悞锕傛儑娴煎瓨鏅?                1. 婵炶揪缍€濞夋洟寮?3-6 闂佸搫顧€缁辨洜妲愰鍫濈煑闂佸灝顑愰崝鍕旈悩顔尖偓褏妲?                2. 闂傚倸娲犻崑鎾绘偡閺囨氨鍔嶆俊鐐插€垮鐗堟償閿濆倵鍋撳鈧畷妯衡枎韫囨挻鐦斿┑鈽嗗亐閸嬫挾绱?閻庤鎮堕崕閬嶅矗閹稿孩濯撮悹鎭掑妽閺嗗繘鏌?                3. 闂佸搫鐗滈崜婵嬫偪閸℃鈻旂€广儱鐗滃ú锝夋偣鐎ｎ亜鏆熼柡浣靛€栫粋鎺旀媼瀹曞洨协閻庤鎮堕崕閬嶅矗閸ф鏅?                4. 闁荤偞绋忛崝蹇涘醇椤忓棛涓嶉柍褜鍓欑叅濞达絿顣介崑鎾存媴缁嬭儻顔夐梺鍦懗閸♀晜鏂€闂?                5. 婵炶揪缍€濞夋洟寮妶鍡欌枖妞ゆ挾鍠愰悗顕€寮堕崼鐔稿碍闁搞値鍙冩俊?                """;

        ChatResponse planResponse = this.chatClient
                .prompt(prompt)
                .system(planPrompt)
                .call()
                .chatClientResponse()
                .chatResponse();

        Assert.notNull(planResponse, "Plan response cannot be null");
        AssistantMessage output = planResponse.getResult().getOutput();
        String planText = output == null ? "" : output.getText();
        if (!StringUtils.hasText(planText)) {
            planText = """
                    1. 闂佸搫瀚ù鐑藉灳濮椻偓閹粙濡搁敃鈧悡鏇㈡⒒閸屻倕娅嶆い锝堝Г缁嬪顢橀悩宕囨殸闂佸搫鍊稿ú銊︽櫠鐠恒劉鍋撻悽娈挎敯闁芥牕瀚粙澶嬫償閵忕姴顥嶉梺鎼炲劙閸曟﹢鍩€?                    2. 婵炶揪缍€濞夋洟寮妶澶嬪剹闁靛鍔庡Σ鏇㈠箹鐎涙ɑ灏ù鍏煎姍瀹曟鏌ㄧ€ｅ灚鈷曠紓浣风┒閸ㄦ椽寮搁崘顔煎珘?闂佹悶鍎辨晶鑺ユ櫠閺嶎偅瀚氬ù锝囨嚀缁侊箓鏌?                    3. 婵炲瓨鍤庨崐鏇烆嚕閸洖鍐€缂佸顑欓崵鐘绘偣閸ワ妇鍔嶇€规挷鐒︾粙澶愬焵椤掑嫭鍤婇悗娑櫱氶崑鎾诡槺缂佹鐭傚畷锝夘敍濞嗗海绠氶柟鑹版彧鐠愮喖鍩€?                    4. 闂佺硶鏅炲銊ц姳椤掑倹瀚氬ù锝囨嚀缁侊箑霉閿濆棛鐭岄柣锛勫枛閻涱噣骞嗛柇锕€娈梺鍝勫缁ㄨ偐绮径灞惧厹妞ゆ棁鍋愰弳浼村级閸繃璐￠柡鍜佸亰婵?                    """;
        }

        this.currentPlan = planText.trim();
        this.planGenerated = true;

        AssistantMessage planMessage = AssistantMessage.builder()
                .content("[Plan]\n" + this.currentPlan)
                .build();
        this.chatMemory.add(this.chatSessionId, planMessage);
        saveMessage(planMessage);
        refreshPendingMessages();
        log.info("Plan generated for session={}", this.chatSessionId);
        if (this.agentState == AgentState.PLANNING) {
            this.agentState = AgentState.IDLE;
        }
    }

    private boolean think() {
        this.agentState = AgentState.THINKING;
        String thinkPrompt = """
                婵炶揪绲挎慨闈浳ｉ崨濠勨枖闁逞屽墯缁嬪顢旈崟顐バ曢梺缁樸仜閺呯娀銆呴锔解拻妞ゆ柨澧介幗鏇㈡煛閸涘绱伴柛蹇斏戦幏鍛村箼閸曨厾顦柣鐘叉穿濞撹绻涢崶顒佸仺闁靛鍎辩拋鏌ユ煟椤剙濡煎ù鍏煎姍瀹曟鎮块婊咁啋闁荤偞绋戦懟顖炈囬崹顕呮桨闁靛绠戦懙褰掓煟閻愬弶顥撻柍?                闂佸憡鐟崹鎶藉极閵堝鍎楅柕澶堝妿濡叉洟骞栫€涙ɑ鎯堢紒?s

                闁荤喐鐟ョ€氼剟宕归鐐存櫖?                1. 闂佺绻愰悧濠囁夐崨顖涱潟闁靛骏绲垮Σ鍫ユ煙鐠団€虫珯缂佽鲸绻堝畷妯侯吋閸涱喚顦ョ紓浣圭⊕濡啫螞閼哥绱ｆ慨妤€鐗忛獮?
                2. 闂佸吋鐪归崕鎾儊婢舵劕绠叉い鏃囧吹閻熸繈鎮洪幒鏃戝姕缂佽鲸绻嗙粻娑㈠川濞ｎ兘鍋撹箛娑樺強閹艰揪绲洪埀顒侇焽閹风姷鈧稒蓱椤牕鈽夐幘宕囆ら柍銉︻焽閳ь剝顫夐悢顒傛?                3. 婵炴垶鎸哥粔瀵告鏉堚晝纾介柡宥庡幐閸嬫捇鎮╂潏鈺冩闁诲骸婀遍崑鐐哄垂閵娾晛绾ч柕澶涘閻栭亶鏌?                4. 婵炲濮鹃褎鎱ㄩ悢琛″亾閻熺増婀伴柛銊﹀哺瀹曘儲鎯旈垾鑼笉濠殿喗绺块崹濠氬箣妞嬪海纾兼い鎿勭磿濞堝爼鏌ｉ～顒€濡煎ù鍏煎姍瀹曟宕欓妶鍥ц€?
                5. 婵炲濮撮幊鎾舵椤撱垹绀勯弶鐐村閸橆剟鏌￠崒姘婵炴挸澧庣槐鎺楀醇閵忋垺鎲板┑鈩冾殔閻楃偟妲愬┑鍥┾枖鐎广儱鐗忕紙濠氭煕閹存繃鎯堥柛銊ラ叄瀵悂骞囬婊呯暫缂備礁顑呴鍐焵?                """.formatted(this.availableKbs);
        thinkPrompt = thinkPrompt + "\n6. For travel or routing questions, prioritize museumTripPlan instead of pure KB QA.";
        thinkPrompt = thinkPrompt + "\n7. For navigation/trip-plan artifact images, ImageKnowledgeTool may search online (1-3 images). For normal KB QA images, keep using KB image retrieval.";
        if (this.planModeEnabled && this.planGenerated && StringUtils.hasText(this.currentPlan)) {
            thinkPrompt = thinkPrompt + "\n\nExecution plan to follow:\n" + this.currentPlan;
        }

        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        StringBuilder aggregatedText = new StringBuilder();
        StringBuilder rawText = new StringBuilder();
        AtomicReference<Integer> streamedSafeLength = new AtomicReference<>(0);
        AtomicReference<ChatResponse> toolCallChunkRef = new AtomicReference<>();
        AtomicReference<ChatResponse> lastChunkRef = new AtomicReference<>();

        this.chatClient
                .prompt(prompt)
                .system(thinkPrompt)
                .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                .stream()
                .chatResponse()
                .doOnNext(chunk -> {
                    lastChunkRef.set(chunk);
                    String token = chunk.getResult().getOutput().getText();
                    if (StringUtils.hasText(token)) {
                        rawText.append(token);
                        String safeVisibleText = stripReasoningLeak(rawText.toString());
                        int sentLength = streamedSafeLength.get();
                        if (safeVisibleText.length() > sentLength) {
                            String safeDelta = safeVisibleText.substring(sentLength);
                            aggregatedText.append(safeDelta);
                            sseService.send(this.chatSessionId, SseMessage.builder()
                                    .type(SseMessage.Type.AI_TOKEN)
                                    .payload(SseMessage.Payload.builder()
                                            .token(safeDelta)
                                            .build())
                                    .build());
                            streamedSafeLength.set(safeVisibleText.length());
                        }
                    }
                    List<AssistantMessage.ToolCall> chunkToolCalls = chunk.getResult().getOutput().getToolCalls();
                    if (chunkToolCalls != null && !chunkToolCalls.isEmpty()) {
                        toolCallChunkRef.set(chunk);
                    }
                })
                .blockLast();

        // 婵炴潙鍚嬮敋闁告ɑ绋掗幏鍛崉閵婏附娈㈤梺?tool calls 闂?chunk闂佹寧绋戦懟顖炲箚娓氣偓瀹曟艾鈻庨幋鐐存闂佸搫鐗冮崑鎾绘煕濮橆剛鍑圭紒鏂跨摠缁?chunk
        ChatResponse toolCallChunk = toolCallChunkRef.get();
        this.lastChatResponse = toolCallChunk != null ? toolCallChunk : lastChunkRef.get();
        Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

        AssistantMessage output = toolCallChunk != null
                ? toolCallChunk.getResult().getOutput()
                : AssistantMessage.builder().content(aggregatedText.toString()).build();

        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
        if (toolCalls.isEmpty() && shouldForceRiskBlockedRefusal() && !isRiskRefusalLike(output.getText())) {
            output = AssistantMessage.builder().content(RISK_BLOCK_REFUSAL).build();
            toolCalls = output.getToolCalls();
        } else if (toolCalls.isEmpty() && shouldForceNoEvidenceRefusal() && !isRefusalLike(output.getText())) {
            output = AssistantMessage.builder().content(NO_EVIDENCE_REFUSAL).build();
            toolCalls = output.getToolCalls();
        }

        saveMessage(output);
        refreshPendingMessages();

        logToolCalls(toolCalls);

        if (this.agentState == AgentState.THINKING) {
            this.agentState = AgentState.IDLE;
        }
        return !toolCalls.isEmpty();
    }

    private boolean shouldForceNoEvidenceRefusal() {
        List<Message> messages = this.chatMemory.get(this.chatSessionId);
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    if (response != null && response.responseData() != null
                            && response.responseData().contains(NO_EVIDENCE_MARKER)) {
                        return true;
                    }
                }
                return false;
            }
            if (message instanceof UserMessage) {
                return false;
            }
        }
        return false;
    }

    private boolean shouldForceRiskBlockedRefusal() {
        List<Message> messages = this.chatMemory.get(this.chatSessionId);
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    if (response != null && response.responseData() != null
                            && response.responseData().contains(RISK_BLOCK_MARKER)) {
                        return true;
                    }
                }
                return false;
            }
            if (message instanceof UserMessage) {
                return false;
            }
        }
        return false;
    }

    private boolean isRiskRefusalLike(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String normalized = text.toLowerCase();
        return normalized.contains("harmful")
                || normalized.contains("illegal")
                || normalized.contains("safe, museum-related question");
    }

    private boolean isRefusalLike(String text) {
        if (!StringUtils.hasText(text)) {
            return false;
        }
        String normalized = text.replaceAll("\\s+", "");
        return normalized.contains("No reliable evidence in knowledge base")
                || normalized.contains("evidence")
                || normalized.contains("cannot provide a certain answer");
    }

    private void execute() {
        this.agentState = AgentState.EXECUTING;
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        if (!this.lastChatResponse.hasToolCalls()) {
            if (this.agentState == AgentState.EXECUTING) {
                this.agentState = AgentState.IDLE;
            }
            return;
        }

        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);

        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, toolExecutionResult.conversationHistory());

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "tool " + resp.name() + " result: " + resp.responseData())
                .collect(Collectors.joining("\n"));

        log.info("tool execution result:\n{}", collect);

        saveMessage(toolResponseMessage);
        refreshPendingMessages();

        if (toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            log.info("task finished by terminate tool");
            return;
        }
        if (this.agentState == AgentState.EXECUTING) {
            this.agentState = AgentState.IDLE;
        }
    }

    private void step() {
        if (this.planModeEnabled && !this.planGenerated) {
            generatePlan();
            return;
        }
        if (think()) {
            execute();
        } else {
            agentState = AgentState.FINISHED;
        }
    }

    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        try {
            String userQuery = latestUserQuery();
            log.info("Agent run start: sessionId={}, mode={}, userQuery={}",
                    this.chatSessionId, this.runtimeMode, truncateForLog(userQuery, 100));
            for (int i = 0; i < maxSteps && agentState != AgentState.FINISHED; i++) {
                int currentStep = i + 1;
                step();
                if (currentStep >= maxSteps && agentState != AgentState.FINISHED) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }
            agentState = AgentState.FINISHED;
        } catch (WebClientResponseException e) {
            agentState = AgentState.ERROR;
            String responseBody = e.getResponseBodyAsString();
            log.error("Error running agent: status={}, response={}", e.getStatusCode(), responseBody, e);
            throw new RuntimeException(
                    "Error running agent: HTTP " + e.getRawStatusCode()
                            + " from model provider. Response: " + responseBody,
                    e
            );
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            log.error("Error running agent", e);
            throw new RuntimeException("Error running agent", e);
        }
    }

    @Override
    public String toString() {
        return "JChatMind {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}

