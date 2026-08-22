package com.example.learningassistant.kb.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文档解析：支持 PDF（PDFBox）、Word（POI）、TXT、Markdown（纯文本）。
 */
@Slf4j
@Service
public class DocumentParser {

    public String parse(String fileName, String contentType, byte[] data) {
        String ext = extension(fileName);
        try {
            if ("pdf".equalsIgnoreCase(ext) || isMime(contentType, "application/pdf")) {
                return parsePdf(data);
            }
            if ("docx".equalsIgnoreCase(ext) || "doc".equalsIgnoreCase(ext)
                    || isMime(contentType, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
                return parseWord(data);
            }
            // txt / md / code 等按 UTF-8 文本处理
            return new String(data, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("文档解析失败: {} - {}", fileName, e.getMessage());
            throw new IllegalStateException("无法解析该文档: " + fileName);
        }
    }

    private String parsePdf(byte[] data) throws Exception {
        try (PDDocument doc = Loader.loadPDF(data)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private String parseWord(byte[] data) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
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
