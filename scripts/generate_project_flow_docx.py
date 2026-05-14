from __future__ import annotations

import datetime as _dt
import os
import zipfile
from pathlib import Path
from typing import Iterable, List
from xml.sax.saxutils import escape


WORKSPACE = Path(r"C:\Code\github\JChatMind")
OUTPUT = WORKSPACE / "docs" / "JChatMind_Project_Flow_Study_Guide.docx"


def _w(text: str) -> str:
    return escape(text, entities={"'": "&apos;", '"': "&quot;"})


def _run_xml(text: str, bold: bool = False, size: int | None = None, mono: bool = False) -> str:
    fonts = "Consolas" if mono else "Microsoft YaHei"
    size_xml = f'<w:sz w:val="{size}"/><w:szCs w:val="{size}"/>' if size else ""
    bold_xml = "<w:b/>" if bold else ""
    return (
        "<w:r>"
        "<w:rPr>"
        f'<w:rFonts w:ascii="{fonts}" w:hAnsi="{fonts}" w:eastAsia="{fonts}" w:cs="{fonts}"/>'
        f"{size_xml}"
        f"{bold_xml}"
        "</w:rPr>"
        f"<w:t xml:space=\"preserve\">{_w(text)}</w:t>"
        "</w:r>"
    )


def _paragraph_xml(
    text: str = "",
    *,
    bold: bool = False,
    size: int = 22,
    align: str | None = None,
    mono: bool = False,
    left: int | None = None,
) -> str:
    ppr = ["<w:pPr>"]
    if align:
        ppr.append(f'<w:jc w:val="{align}"/>')
    if left is not None:
        ppr.append(f'<w:ind w:left="{left}"/>')
    ppr.append("</w:pPr>")

    runs: List[str] = []
    for idx, line in enumerate(text.split("\n")):
        if idx > 0:
            runs.append("<w:r><w:br/></w:r>")
        if line:
            runs.append(_run_xml(line, bold=bold, size=size, mono=mono))
        else:
            runs.append(_run_xml(" ", bold=bold, size=size, mono=mono))

    if not runs:
        runs.append(_run_xml(" ", bold=bold, size=size, mono=mono))

    return "<w:p>" + "".join(ppr) + "".join(runs) + "</w:p>"


def _page_break_xml() -> str:
    return "<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>"


def _document_xml(paragraphs: Iterable[str]) -> str:
    body = "".join(paragraphs)
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document xmlns:wpc="http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas" '
        'xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" '
        'xmlns:o="urn:schemas-microsoft-com:office:office" '
        'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
        'xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math" '
        'xmlns:v="urn:schemas-microsoft-com:vml" '
        'xmlns:wp14="http://schemas.microsoft.com/office/word/2010/wordprocessingDrawing" '
        'xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" '
        'xmlns:w10="urn:schemas-microsoft-com:office:word" '
        'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
        'xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml" '
        'xmlns:wpg="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup" '
        'xmlns:wpi="http://schemas.microsoft.com/office/word/2010/wordprocessingInk" '
        'xmlns:wne="http://schemas.microsoft.com/office/word/2006/wordml" '
        'xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" '
        'mc:Ignorable="w14 wp14">'
        "<w:body>"
        f"{body}"
        "<w:sectPr>"
        '<w:pgSz w:w="11906" w:h="16838"/>'
        '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708" w:gutter="0"/>'
        "<w:cols w:space=\"708\"/>"
        "<w:docGrid w:linePitch=\"360\"/>"
        "</w:sectPr>"
        "</w:body>"
        "</w:document>"
    )


def _styles_xml() -> str:
    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:docDefaults>
    <w:rPrDefault>
      <w:rPr>
        <w:rFonts w:ascii="Microsoft YaHei" w:hAnsi="Microsoft YaHei" w:eastAsia="Microsoft YaHei" w:cs="Microsoft YaHei"/>
        <w:sz w:val="22"/>
        <w:szCs w:val="22"/>
      </w:rPr>
    </w:rPrDefault>
  </w:docDefaults>
  <w:style w:type="paragraph" w:default="1" w:styleId="Normal">
    <w:name w:val="Normal"/>
    <w:qFormat/>
  </w:style>
</w:styles>
"""


def _content_types_xml() -> str:
    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>
"""


def _rels_xml() -> str:
    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>
"""


def _document_rels_xml() -> str:
    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>
"""


def _core_props_xml() -> str:
    now = _dt.datetime.now(_dt.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")
    return f"""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
    xmlns:dc="http://purl.org/dc/elements/1.1/"
    xmlns:dcterms="http://purl.org/dc/terms/"
    xmlns:dcmitype="http://purl.org/dc/dcmitype/"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <dc:title>JChatMind 项目流程学习文档</dc:title>
  <dc:creator>Codex</dc:creator>
  <cp:lastModifiedBy>Codex</cp:lastModifiedBy>
  <dcterms:created xsi:type="dcterms:W3CDTF">{now}</dcterms:created>
  <dcterms:modified xsi:type="dcterms:W3CDTF">{now}</dcterms:modified>
</cp:coreProperties>
"""


def _app_props_xml() -> str:
    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"
    xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes">
  <Application>Codex</Application>
</Properties>
"""


def build_paragraphs() -> List[str]:
    p = []

    p.append(_paragraph_xml("JChatMind 项目流程学习文档", bold=True, size=32, align="center"))
    p.append(_paragraph_xml("基于当前仓库代码整理，重点解释简历里提到的 Agent / RAG / CLIP / rerank / SSE 主流程。", size=20, align="center"))
    p.append(_paragraph_xml("说明：这份文档按“代码实际实现”来写，其中简历里提到的 Plan 更接近轻量规划与步骤控制；仓库当前没有单独的 Planner 类。", size=18))

    p.append(_paragraph_xml("一、这个项目解决什么问题", bold=True, size=26))
    p.append(_paragraph_xml("项目目标是做一个面向知识库问答和多模态检索的智能体系统。用户在前端发起提问后，后端会根据 Agent 配置决定是否调用知识库检索、图片检索和其他工具，再把结果流式返回给前端。", size=20))
    p.append(_paragraph_xml("从代码结构上看，它由三层组成：", size=20))
    p.append(_paragraph_xml("1. 业务入口层：Controller + 前端页面，负责会话、消息、知识库、文档和 SSE 连接。", size=20, left=720))
    p.append(_paragraph_xml("2. Agent 执行层：JChatMindFactory + JChatMind + ToolCallingManager，负责构造运行时、执行模型思考和工具调用。", size=20, left=720))
    p.append(_paragraph_xml("3. 检索层：RagServiceImpl + ImageRagServiceImpl + HttpRerankServiceImpl，负责向量检索、混合检索、重排和多模态召回。", size=20, left=720))

    p.append(_paragraph_xml("二、端到端主流程", bold=True, size=26))
    p.append(_paragraph_xml("下面这条链路是学习时最值得先吃透的。", size=20))
    p.append(_paragraph_xml("前端 AgentChatView 订阅 SSE -> 用户发送消息 -> 创建会话/消息落库 -> 发布 ChatEvent -> 异步触发 JChatMind -> 模型 decide 是否调用工具 -> RAG / 图片检索 / rerank -> 结果保存并通过 SSE 推给前端。", size=20))
    p.append(_paragraph_xml("你可以把它理解成一个“消息驱动的 Agent 回路”，而不是单次问答函数。", size=20))

    p.append(_paragraph_xml("三、用户提问时发生了什么", bold=True, size=26))
    p.append(_paragraph_xml("1. 前端先连 SSE。`ui/src/components/views/AgentChatView.tsx` 里会调用 `new EventSource(http://localhost:8080/sse/connect/{chatSessionId})`。", size=20))
    p.append(_paragraph_xml("2. 用户发消息后，前端通过 `POST /api/chat-messages` 把用户内容保存到数据库。", size=20))
    p.append(_paragraph_xml("3. `ChatMessageFacadeServiceImpl.createChatMessage()` 在写库成功后发布 `ChatEvent`。", size=20))
    p.append(_paragraph_xml("4. `ChatEventListener` 异步接收事件，创建 `JChatMind` 实例并调用 `run()`。", size=20))
    p.append(_paragraph_xml("5. `JChatMind` 会从数据库取最近的聊天记录，恢复 memory，再进入“思考 -> 执行”的循环。", size=20))
    p.append(_paragraph_xml("6. 如果模型选择了工具调用，工具结果会再次保存到消息表，并通过 SSE 实时推给前端。", size=20))

    p.append(_paragraph_xml("四、Agent 执行层怎么做", bold=True, size=26))
    p.append(_paragraph_xml("核心文件是 `jchatmind/src/main/java/com/kama/jchatmind/agent/JChatMind.java` 和 `JChatMindFactory.java`。", size=20))
    p.append(_paragraph_xml("JChatMindFactory 的职责是“拼装运行时”：", size=20))
    p.append(_paragraph_xml("1. 从数据库读取 Agent 配置。", size=20, left=720))
    p.append(_paragraph_xml("2. 读取会话最近消息，恢复为 Spring AI 的 Message 列表。", size=20, left=720))
    p.append(_paragraph_xml("3. 根据 Agent 的 allowedTools 和 allowedKbs 组装工具与知识库。", size=20, left=720))
    p.append(_paragraph_xml("4. 加上 system prompt 防护约束，生成真正用于执行的 JChatMind。", size=20, left=720))
    p.append(_paragraph_xml("5. 按运行模式 fast / deep 设置最大步数。", size=20, left=720))
    p.append(_paragraph_xml("JChatMind 真正做事的方式是一个循环：", size=20))
    p.append(_paragraph_xml("think()：把当前上下文、知识库列表和工具列表交给模型，让模型决定下一步要不要调用工具。", size=20, left=720))
    p.append(_paragraph_xml("execute()：如果模型产生 tool_calls，就由 ToolCallingManager 执行工具，得到 ToolResponseMessage。", size=20, left=720))
    p.append(_paragraph_xml("step()：先思考，再执行，直到没有工具调用或达到最大步数。", size=20, left=720))
    p.append(_paragraph_xml("这就是这份简历里 ReAct 的实际落地方式：模型先思考，再通过工具行动，再根据结果继续思考。", size=20))
    p.append(_paragraph_xml("如果你在简历里写 Plan，建议理解成“轻量规划”而不是独立的 Planner 模块：目前仓库更多是通过 step loop 和 prompt 约束实现计划感。", size=20))

    p.append(_paragraph_xml("五、RAG 检索层怎么做", bold=True, size=26))
    p.append(_paragraph_xml("RAG 的核心在 `RagServiceImpl`。它不是单纯做一次向量搜索，而是做了“向量召回 + 关键词召回 + RRF 融合 + rerank”的完整流程。", size=20))
    p.append(_paragraph_xml("检索过程可以拆成四步：", size=20))
    p.append(_paragraph_xml("1. 先调用外部 embedding 服务，把 query 转成向量。", size=20, left=720))
    p.append(_paragraph_xml("2. 在 PostgreSQL/pgvector 里做 dense recall，并行做 lexical recall。", size=20, left=720))
    p.append(_paragraph_xml("3. 按 query 特征动态调权，使用 RRF 融合两个候选集。", size=20, left=720))
    p.append(_paragraph_xml("4. 如果启用了 rerank，再把候选文本发给 rerank 服务重排。", size=20, left=720))
    p.append(_paragraph_xml("这里对应了简历里的“RAG + pgvector + rerank + 动态召回策略”。", size=20))
    p.append(_paragraph_xml("实现细节上，`rag.route.dynamic-enabled` 会根据 query 长短、是否带数字/ID、是否像实体查询等特征，动态提高 lexical 或 dense 的权重。", size=20))
    p.append(_paragraph_xml("这让短查询更偏关键词，长查询更偏向量语义，属于比较实用的检索路由策略。", size=20))

    p.append(_paragraph_xml("六、rerank 是怎么接的", bold=True, size=26))
    p.append(_paragraph_xml("`HttpRerankServiceImpl` 是一个 HTTP 客户端封装，它把 query + documents 发给 `rerank_service` 里的 `/rerank` 接口。", size=20))
    p.append(_paragraph_xml("如果 rerank 服务返回的是 score 数组或 results 数组，代码都会兼容解析，然后再按分数排序。", size=20))
    p.append(_paragraph_xml("这意味着 rerank 在这个项目里是一个独立外部服务，不是主工程里直接加载的大模型推理。", size=20))
    p.append(_paragraph_xml("从学习角度记住一句话：先召回，再重排。召回负责“别漏”，重排负责“排准”。", size=20))

    p.append(_paragraph_xml("七、多模态图片检索怎么做", bold=True, size=26))
    p.append(_paragraph_xml("图片能力由 `ImageRagServiceImpl` 提供，代码里明确使用了 `OFA-Sys/chinese-clip-vit-base-patch16`。", size=20))
    p.append(_paragraph_xml("它有两条主线：", size=20))
    p.append(_paragraph_xml("1. 入库：上传图片后，先存文件，再调用 `/embed-image` 生成图像向量，最后写入 `ImageEmbedding` 表。", size=20, left=720))
    p.append(_paragraph_xml("2. 查询：用户文本先走 `/embed-text`，再去 `ImageEmbedding` 表里做向量相似检索，返回图片 URL。", size=20, left=720))
    p.append(_paragraph_xml("如果是 Markdown 文档，`DocumentFacadeServiceImpl` 还会解析图片链接，把文档里的图片一并抽出来做索引。", size=20))
    p.append(_paragraph_xml("这部分对应简历里的“CN-CLIP / CLIP 图文双向检索”。", size=20))

    p.append(_paragraph_xml("八、文档入库和切分流程", bold=True, size=26))
    p.append(_paragraph_xml("文档上传的入口在 `DocumentFacadeServiceImpl.uploadDocument()`。流程大致是：", size=20))
    p.append(_paragraph_xml("1. 先创建 document 记录。", size=20, left=720))
    p.append(_paragraph_xml("2. 保存原始文件到 `document.storage.base-path`。", size=20, left=720))
    p.append(_paragraph_xml("3. 更新 document 的 metadata，记录 filePath。", size=20, left=720))
    p.append(_paragraph_xml("4. 如果是 Markdown，则按 500 字左右窗口切块，重叠 100 字。", size=20, left=720))
    p.append(_paragraph_xml("5. 每个 chunk 调 `ragService.embed()` 生成向量后写入 `ChunkBgeM3`。", size=20, left=720))
    p.append(_paragraph_xml("6. 顺便抽取 Markdown 内的图片，再交给 `ImageRagService` 做图片索引。", size=20, left=720))
    p.append(_paragraph_xml("这就是知识库从文件到向量库的完整链路。", size=20))

    p.append(_paragraph_xml("九、前端是怎么把流式效果做出来的", bold=True, size=26))
    p.append(_paragraph_xml("前端 `AgentChatView.tsx` 同时做了两件事：", size=20))
    p.append(_paragraph_xml("1. 用普通 REST 接口加载历史消息、创建会话、发送消息。", size=20, left=720))
    p.append(_paragraph_xml("2. 用 SSE 监听后端推送，把 AI 生成内容一条条追加到消息列表。", size=20, left=720))
    p.append(_paragraph_xml("`AgentChatHistory.tsx` 里会把 assistant 的 tool_calls、tool response 和普通文本分层显示，所以你能在界面上看到“模型调用了什么工具、工具返回了什么结果”。", size=20))
    p.append(_paragraph_xml("这个交互设计非常适合面试时讲清楚：不是一次性返回一个大 JSON，而是用户能看见模型的思考和执行过程。", size=20))

    p.append(_paragraph_xml("十、简历里的每个点在代码里对应什么", bold=True, size=26))
    p.append(_paragraph_xml("Java | Spring Boot：Controller、Service、Mapper、事件监听、异步任务、SSE 都在后端主工程里。", size=20))
    p.append(_paragraph_xml("Spring AI：`MultiChatClientConfig`、`ChatClientRegistry`、`ToolCallingManager`、`DefaultToolCallingChatOptions` 是核心。", size=20))
    p.append(_paragraph_xml("PostgreSQL | pgvector：`ChunkBgeM3` 和 `ImageEmbedding` 都依赖向量字段做相似搜索。", size=20))
    p.append(_paragraph_xml("RAG：`DocumentFacadeServiceImpl` 负责入库切块，`RagServiceImpl` 负责检索，`KnowledgeTools` 负责把检索结果包装成工具输出。", size=20))
    p.append(_paragraph_xml("CLIP：`ImageRagServiceImpl` 调用图文 embedding 接口，完成图像向量入库和文本搜图。", size=20))
    p.append(_paragraph_xml("rerank：`HttpRerankServiceImpl` 对接独立 rerank_service，支持候选排序优化。", size=20))
    p.append(_paragraph_xml("ReAct / Plan：当前仓库更偏 ReAct，Plan 以轻量步骤控制和 prompt 约束的方式存在。", size=20))

    p.append(_paragraph_xml("十一、学习时建议优先看的文件", bold=True, size=26))
    p.append(_paragraph_xml("建议按这个顺序读：", size=20))
    for item in [
        r"C:\Code\github\JChatMind\ui\src\components\views\AgentChatView.tsx",
        r"C:\Code\github\JChatMind\jchatmind\src\main\java\com\kama\jchatmind\controller\ChatMessageController.java",
        r"C:\Code\github\JChatMind\jchatmind\src\main\java\com\kama\jchatmind\service\impl\ChatMessageFacadeServiceImpl.java",
        r"C:\Code\github\JChatMind\jchatmind\src\main\java\com\kama\jchatmind\event\listener\ChatEventListener.java",
        r"C:\Code\github\JChatMind\jchatmind\src\main\java\com\kama\jchatmind\agent\JChatMindFactory.java",
        r"C:\Code\github\JChatMind\jchatmind\src\main\java\com\kama\jchatmind\agent\JChatMind.java",
        r"C:\Code\github\JChatMind\jchatmind\src\main\java\com\kama\jchatmind\service\impl\RagServiceImpl.java",
        r"C:\Code\github\JChatMind\jchatmind\src\main\java\com\kama\jchatmind\service\impl\HttpRerankServiceImpl.java",
        r"C:\Code\github\JChatMind\jchatmind\src\main\java\com\kama\jchatmind\service\impl\ImageRagServiceImpl.java",
        r"C:\Code\github\JChatMind\jchatmind\src\main\java\com\kama\jchatmind\service\impl\DocumentFacadeServiceImpl.java",
    ]:
        p.append(_paragraph_xml(f"• {item}", size=18, left=720))

    p.append(_paragraph_xml("十二、你在简历面试里可以怎么讲", bold=True, size=26))
    p.append(_paragraph_xml("一个比较稳的讲法是：", size=20))
    p.append(_paragraph_xml("“我做的是一个基于 Spring Boot + Spring AI 的多模态知识问答系统。前端通过 SSE 实时接收 Agent 的执行过程，后端用事件驱动方式把用户消息转成 Agent 任务，再在 JChatMind 的循环里完成思考、工具调用和结果回传。知识检索部分做了向量召回、关键词召回、RRF 融合和 rerank，同时通过 CLIP 完成图文双向检索，保证文本问答和图片问答都能覆盖。”", size=20))
    p.append(_paragraph_xml("如果面试官继续追问，你就按“会话层、Agent 层、检索层、评估层”四层展开。", size=20))

    p.append(_paragraph_xml("十三、一个重要的真实性提醒", bold=True, size=26))
    p.append(_paragraph_xml("仓库当前代码里，前端已经预留了 `AI_PLANNING / AI_THINKING / AI_EXECUTING / AI_DONE` 这些状态，但后端主链路实际发送得最明确的是 `AI_GENERATED_CONTENT`。", size=20))
    p.append(_paragraph_xml("所以如果你要把这份项目写进简历，最好区分“已经落地的能力”和“下一步演进目标”。这样讲会更可信，也更容易把项目讲深。", size=20))

    return p


def build_docx(output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)

    paragraphs = build_paragraphs()
    document_xml = _document_xml(paragraphs)

    with zipfile.ZipFile(output_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("[Content_Types].xml", _content_types_xml())
        zf.writestr("_rels/.rels", _rels_xml())
        zf.writestr("docProps/core.xml", _core_props_xml())
        zf.writestr("docProps/app.xml", _app_props_xml())
        zf.writestr("word/document.xml", document_xml)
        zf.writestr("word/_rels/document.xml.rels", _document_rels_xml())
        zf.writestr("word/styles.xml", _styles_xml())


if __name__ == "__main__":
    build_docx(OUTPUT)
    print(str(OUTPUT))
