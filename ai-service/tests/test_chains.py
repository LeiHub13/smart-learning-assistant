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
