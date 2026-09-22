package com.example.learningassistant.kb.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * 文档解析：支持 PDF（PDFBox）、Word（POI）、TXT、Markdown（纯文本）。
 */
@Slf4j
@Service
public class DocumentParser {

    public String parse(String fileName, String contentType, byte[] data) {
        String ext = extension(fileName);
        String text;
        try {
            if ("pdf".equalsIgnoreCase(ext) || isMime(contentType, "application/pdf")) {
                text = parsePdf(data);
            } else if ("docx".equalsIgnoreCase(ext)
                    || isMime(contentType, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
                text = parseDocx(data);
            } else if ("doc".equalsIgnoreCase(ext)) {
                text = parseDoc(data, fileName);
            } else {
                // txt / md / code 等按 UTF-8 文本处理
                text = new String(data, StandardCharsets.UTF_8);
            }
        } catch (DocumentParseException e) {
            throw e;
        } catch (Exception e) {
            log.warn("文档解析失败: {} - {}", fileName, e.getMessage());
            throw new DocumentParseException("无法解析该文档（" + fileName + "）：" + e.getMessage());
        }

        if (text == null || text.isBlank()) {
            throw new DocumentParseException(
                    "未能从该文档提取到文字（" + fileName + "）。若是 PDF/图片扫描件，需先做 OCR 文字识别后再上传。");
        }
        return text;
    }

    /**
     * 解析失败但携带可读原因的异常，避免被全局兜底转成"系统繁忙"。
     */
    public static class DocumentParseException extends RuntimeException {
        public DocumentParseException(String message) {
            super(message);
        }
    }

    private String parsePdf(byte[] data) throws Exception {
        try (PDDocument doc = Loader.loadPDF(data)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String parseDocx(byte[] data) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private String parseDoc(byte[] data, String fileName) throws Exception {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(data));
             WordExtractor extractor = new WordExtractor(doc)) {
            return extractor.getText();
        } catch (Exception e) {
            // 老 .doc 是二进制格式，HWPF 失败时不能直接按 UTF-8 读（会得到乱码）；
            // 仅当字节本身就是可打印文本（被误标 .doc 的 txt）时才降级。
            if (looksLikeText(data)) {
                log.warn(".doc 非二进制 Word，降级当纯文本读取: {} - {}", fileName, e.getMessage());
                return new String(data, StandardCharsets.UTF_8);
            }
            throw new DocumentParseException(
                    "无法解析该 .doc 文件（" + fileName + "）。文件可能已损坏，或为更旧的 Word 格式，建议另存为 .docx 后重新上传。");
        }
    }

    /**
     * 粗略判断字节是否为可读文本：含 NUL 空字节、或 UTF-8 严格解码失败即视为二进制。
     */
    private boolean looksLikeText(byte[] data) {
        if (data == null || data.length == 0) {
            return false;
        }
        for (byte b : data) {
            if (b == 0x00) {
                return false; // 二进制复合文档（如 .doc/.xls）必有 NUL 填充
            }
        }
        try {
            StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String extension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase();
    }

    private boolean isMime(String contentType, String expected) {
        return contentType != null && contentType.toLowerCase().startsWith(expected.toLowerCase());
    }
}
