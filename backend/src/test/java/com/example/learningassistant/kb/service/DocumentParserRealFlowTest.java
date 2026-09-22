package com.example.learningassistant.kb.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 端到端解析验证：现场生成真实 PDF/DOCX 字节走完整 parse 流程；
 * .doc 无法用 POI 合成合法二进制，只验证降级与拒绝两条分支。
 */
class DocumentParserRealFlowTest {

    private final DocumentParser parser = new DocumentParser();

    private byte[] pdfWithText(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void parseRealPdf() throws Exception {
        byte[] data = pdfWithText("Hello real PDF flow");
        String result = parser.parse("real.pdf", "application/pdf", data);
        assertTrue(result.contains("Hello real PDF flow"), result);
    }

    @Test
    void parseRealDocx() throws Exception {
        byte[] data;
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("这是 docx 正文内容");
            doc.write(out);
            data = out.toByteArray();
        }
        String result = parser.parse("real.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", data);
        assertTrue(result.contains("docx 正文"), result);
    }

    @Test
    void emptyScannedPdfGivesClearError() throws Exception {
        byte[] data;
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage()); // 无文字，模拟扫描件
            doc.save(out);
            data = out.toByteArray();
        }
        DocumentParser.DocumentParseException ex = assertThrows(DocumentParser.DocumentParseException.class,
                () -> parser.parse("scan.pdf", "application/pdf", data));
        assertTrue(ex.getMessage().contains("OCR"), ex.getMessage());
    }

    @Test
    void binaryDocRejectedNotMojibake() {
        // 伪造 OLE 复合文档特征（含 NUL 字节），必须报清晰错误而非返回乱码
        byte[] binary = new byte[256];
        for (int i = 0; i < binary.length; i++) {
            binary[i] = (byte) (i % 32);
        }
        DocumentParser.DocumentParseException ex = assertThrows(DocumentParser.DocumentParseException.class,
                () -> parser.parse("fake.doc", "application/msword", binary));
        assertTrue(ex.getMessage().contains("docx"), ex.getMessage());
    }

    @Test
    void mislabeledTxtAsDocFallsBackToText() {
        byte[] data = "这其实是纯文本，被误标成 .doc".getBytes(StandardCharsets.UTF_8);
        String result = parser.parse("mislabeled.doc", "application/msword", data);
        assertTrue(result.contains("纯文本"), result);
    }
}
