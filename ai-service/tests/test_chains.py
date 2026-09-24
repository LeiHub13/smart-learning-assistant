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
