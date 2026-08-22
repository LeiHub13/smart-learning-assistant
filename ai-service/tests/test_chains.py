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
