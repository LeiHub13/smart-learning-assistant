"""文档解析与分块（自 Java 侧 DocumentParser / KbService.split 迁移过来）。

职责边界：本模块把上传的原始文件字节变成可入库的文本块，Java 拿到 chunks 后落 t_chunk。
chunk 正文的唯一数据源仍是 Java 侧 t_chunk，本模块不持有数据。

支持：PDF（pypdf）、Word（python-docx，仅 .docx）、纯文本类（txt/md/代码文件，UTF-8 严格解码）。
老式 .doc 是 OLE 二进制复合文档，纯 Python 无可靠抽取方案，已明确不再支持。
"""
from __future__ import annotations

import logging

from app import config

logger = logging.getLogger("ai-service.parsing")

PDF_MIME = "application/pdf"
DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"


class ParseError(Exception):
    """解析/分块失败，message 是给终端用户看的可读原因，不能被兜底成"系统繁忙"。"""


def extension(file_name: str | None) -> str:
    if not file_name or "." not in file_name:
        return ""
    return file_name.rsplit(".", 1)[-1].lower()


def _is_mime(content_type: str | None, expected: str) -> bool:
    return bool(content_type) and content_type.lower().startswith(expected.lower())


def parse(file_name: str | None, content_type: str | None, data: bytes) -> str:
    """把文件字节抽成纯文本。失败一律抛 ParseError。"""
    ext = extension(file_name)
    if ext == "doc":
        raise ParseError(
            f"不支持 .doc 旧版 Word 格式（{file_name}）。请在 Word 中另存为 .docx 后重新上传。"
        )

    try:
        if ext == "pdf" or _is_mime(content_type, PDF_MIME):
            text = _parse_pdf(data)
        elif ext == "docx" or _is_mime(content_type, DOCX_MIME):
            text = _parse_docx(data)
        else:
            text = _parse_text(data, file_name)
    except ParseError:
        raise
    except Exception as e:  # noqa: BLE001
        logger.warning("文档解析失败: %s - %s", file_name, e)
        raise ParseError(f"无法解析该文档（{file_name}）：{e}") from e

    if not text or not text.strip():
        raise ParseError(
            f"未能从该文档提取到文字（{file_name}）。"
            "若是 PDF 图片扫描件，需先做 OCR 文字识别后再上传。"
        )
    return text


def to_chunks(text: str, size: int | None = None) -> list[str]:
    """定长硬切，与迁移前的 Java 分块行为保持一致（无重叠、无段落感知）。"""
    chunk_size = size or config.RAG_CHUNK_SIZE
    return [text[i:i + chunk_size] for i in range(0, len(text), chunk_size)]


def parse_to_chunks(file_name: str | None, content_type: str | None, data: bytes) -> list[str]:
    return to_chunks(parse(file_name, content_type, data))


def _parse_pdf(data: bytes) -> str:
    import io

    from pypdf import PdfReader

    reader = PdfReader(io.BytesIO(data))
    if reader.is_encrypted:
        # 空密码尝试解密：无口令加密的 PDF 仍可读，真加密的会抛错
        try:
            reader.decrypt("")
        except Exception as e:  # noqa: BLE001
            raise ParseError(f"该 PDF 已加密，无法解析。请解除密码后重新上传。（{e}）") from e
    return "".join((page.extract_text() or "") + "\n" for page in reader.pages)


def _parse_docx(data: bytes) -> str:
    import io

    from docx import Document

    doc = Document(io.BytesIO(data))
    parts = [p.text for p in doc.paragraphs]
    for table in doc.tables:
        for row in table.rows:
            parts.append("\t".join(cell.text for cell in row.cells))
    return "\n".join(parts)


def _parse_text(data: bytes, file_name: str | None) -> str:
    """txt/md/代码等按 UTF-8 读；含 NUL 或严格解码失败说明其实是二进制，直接报错而非产出乱码。"""
    if not data:
        return ""
    if b"\x00" in data:
        raise ParseError(
            f"该文件不是可解析的文本格式（{file_name}）。"
            "若为 Word 文档请使用 .docx，若为 PDF 请使用 .pdf 扩展名上传。"
        )
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError as e:
        raise ParseError(
            f"该文件不是 UTF-8 编码的文本（{file_name}），请先转换为 UTF-8 编码后重新上传。（{e}）"
        ) from e
