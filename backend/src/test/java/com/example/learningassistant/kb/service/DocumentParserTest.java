package com.example.learningassistant.kb.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void testParsePdf() throws IOException {
        byte[] pdfBytes;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Hello World - PDF parsing test");
                cs.endText();
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            doc.save(bos);
            pdfBytes = bos.toByteArray();
        }

        String result = parser.parse("test.pdf", "application/pdf", pdfBytes);
        assertNotNull(result);
        assertTrue(result.contains("Hello World"));
        System.out.println("PDF parse OK: [" + result.trim() + "]");
    }

    @Test
    void testParseTxt() {
        byte[] content = "这是一段测试文本".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String result = parser.parse("test.txt", "text/plain", content);
        assertEquals("这是一段测试文本", result);
    }

    @Test
    void testParseMd() {
        byte[] content = "# 标题\n这是一段 Markdown".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String result = parser.parse("test.md", "text/markdown", content);
        assertTrue(result.contains("# 标题"));
    }
}
