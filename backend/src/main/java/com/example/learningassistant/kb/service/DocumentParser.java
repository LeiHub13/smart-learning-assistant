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
            if ("docx".equalsIgnoreCase(ext)
                    || isMime(contentType, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
                return parseDocx(data);
            }
            if ("doc".equalsIgnoreCase(ext)) {
                try {
                    return parseDoc(data);
                } catch (Exception e) {
                    log.warn(".doc 解析失败，降级当纯文本读取: {} - {}", fileName, e.getMessage());
                    return new String(data, StandardCharsets.UTF_8);
                }
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

    private String parseDocx(byte[] data) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    private String parseDoc(byte[] data) throws Exception {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(data));
             WordExtractor extractor = new WordExtractor(doc)) {
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
