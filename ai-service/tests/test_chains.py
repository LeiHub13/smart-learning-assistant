from langchain_core.messages import SystemMessage

from app import chains


def test_strip_code_fence():
    assert chains._strip_code_fence("```json\n[{}]\n```") == "[{}]"
    assert chains._strip_code_fence("[{}]") == "[{}]"


def test_ensure_json_array():
    assert chains._ensure_json_array('[{"a":1}]') == '[{"a":1}]'
    assert chains._ensure_json_array("前缀```json\n[1,2]\n```后缀") == "[1,2]"


def test_chunks_to_text():
    text = chains._chunks_to_text([{"content": "abc"}, {"content": "def"}])
    assert "[1] abc" in text
    assert "[2] def" in text


def test_prepare_rag_passes_course_kb_ids(monkeypatch):
    """课程级检索范围 kb_ids 须透传到 rag 层：否则答疑只搜单库，串不到本课程的其它知识库。"""
    from app import rag

    class _Ctx:
        sources = "1,2"
        context = "[1] 资料"
        hits = [{"chunkId": 1}]

    ctx = _Ctx()
    recorded = {}

    def fake_build_context(kb_id, question, history=None, kb_ids=None):
        recorded["kb_id"] = kb_id
        recorded["kb_ids"] = kb_ids
        return ctx

    monkeypatch.setattr(rag, "build_context", fake_build_context)

    meta = {}
    result = chains._prepare_rag("rag_qa", "问题", None, None, 7, meta, kb_ids=[7, 9])

    assert result == ctx.context
    assert meta["sources"] == ctx.sources
    assert recorded == {"kb_id": 7, "kb_ids": [7, 9]}


def test_assemble_injects_user_profile(monkeypatch, tmp_path):
    """跨会话学生画像须以 system 消息注入答疑上下文。"""
    monkeypatch.setattr(chains.memory.config, "MEMORY_DIR", str(tmp_path))
    monkeypatch.setattr(chains.memory, "user_profile", lambda uid: "学生喜欢先看例子再听原理")

    msgs = chains._assemble("free", "怎么学递归？", None, "sess-p", user_id=9)
    profile = [m for m in msgs
               if isinstance(m, SystemMessage) and "学生长期画像" in m.content]
    assert profile and "先看例子" in profile[0].content


def test_assemble_no_profile_when_absent(monkeypatch, tmp_path):
    monkeypatch.setattr(chains.memory.config, "MEMORY_DIR", str(tmp_path))

    msgs = chains._assemble("free", "怎么学递归？", None, "sess-q", user_id=9)
    assert not any(isinstance(m, SystemMessage) and "学生长期画像" in m.content for m in msgs)


def test_remember_profile_gated_by_slots(monkeypatch):
    """画像提炼并发闸门：槽位占满时放弃本轮（不起线程），释放后恢复提炼。"""
    import threading

    called = threading.Event()
    monkeypatch.setattr(chains, "_extract_profile", lambda *a, **k: called.set())

    # 占满全部槽位
    while chains._PROFILE_SLOTS.acquire(blocking=False):
        pass
    chains._remember(None, 9, "请结合例题讲讲递归的基准情况", "好的，我们来看这道题……" * 10)
    assert not called.is_set()

    for _ in range(chains._PROFILE_CONCURRENCY):
        chains._PROFILE_SLOTS.release()
    chains._remember(None, 9, "请结合例题讲讲递归的基准情况", "好的，我们来看这道题……" * 10)
    assert called.wait(timeout=5)


def test_socratic_prompt_appended_only_when_enabled():
    """引导模式开关：开启时系统提示拼入苏格拉底引导块，关闭时不出现在提示里。"""
    on = chains._build_messages("free", "什么是TCP三次握手？", None, socratic=True)
    assert "苏格拉底引导模式" in on[0].content
    assert "引导问题" in on[0].content
    off = chains._build_messages("free", "什么是TCP三次握手？", None)
    assert "苏格拉底引导" not in off[0].content
    rag_on = chains._build_messages("rag_qa", "什么是TCP三次握手？", ["片段A"], socratic=True)
    assert "苏格拉底引导模式" in rag_on[0].content


def test_doc_overview_prompt_shape():
    """文档速览场景：系统提示要求只输出 JSON（summary/points/examPoints）。"""
    text = "【资料标题】x.md" + chr(10) + "【资料文本】正文"
    msgs = chains._build_messages("doc_overview", text, None)
    assert msgs[0].content.count("summary") >= 1
    assert "points" in msgs[0].content and "examPoints" in msgs[0].content
    assert "不要输出任何其他文字" in msgs[0].content
    assert msgs[-1].content.startswith("【资料标题】")


def test_suggest_followups_parses_and_truncates(monkeypatch):
    """追问推荐：解析 JSON 数组、首尾清理、超长截断、最多 3 条，并以 followup 场景记统计。"""
    seen = {}

    def fake_call(fn, scene):
        seen["scene"] = scene
        return ('["  什么是递归的终止条件？ ", "递归和循环怎么选？",'
                ' "' + "长" * 45 + '", "第四条被丢弃"]')

    monkeypatch.setattr(chains, "_call_with_limit", fake_call)
    out = chains.suggest_followups("讲讲递归", "递归是……")
    assert seen["scene"] == "followup"
    assert out == ["什么是递归的终止条件？", "递归和循环怎么选？", "长" * 40]


def test_suggest_followups_failure_returns_empty(monkeypatch):
    """追问推荐是旁路增强：模型失败/输出非法时回落为空列表，绝不抛错影响主回答。"""
    def boom(fn, scene):
        raise RuntimeError("上游挂了")
    monkeypatch.setattr(chains, "_call_with_limit", boom)
    assert chains.suggest_followups("问题", "回答") == []
    monkeypatch.setattr(chains, "_call_with_limit", lambda fn, scene: "模型没输出 JSON")
    assert chains.suggest_followups("问题", "回答") == []


def test_stream_attaches_followups_into_meta(monkeypatch):
    """答疑流收尾须把追问建议挂进 meta（经 done 事件回传 Java 的载体）；自由/知识库两场景一致。"""
    class _Chunk:
        content = "答案正文"
        usage_metadata = None

    class _Model:
        def stream(self, msgs):
            yield _Chunk()

    monkeypatch.setattr(chains, "get_model", lambda: _Model())

    def fake_attach(meta, question, answer, session_id):
        meta["followups"] = ["追问1", "追问2"]

    monkeypatch.setattr(chains, "_attach_followups", fake_attach)
    for scene in ("free", "rag_qa"):
        meta = {}
        list(chains.stream(scene, "问题", chunks=["资料"], meta=meta))
        assert meta["followups"] == ["追问1", "追问2"]
