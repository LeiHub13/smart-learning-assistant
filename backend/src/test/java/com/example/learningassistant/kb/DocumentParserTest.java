package com.example.learningassistant.kb;

import com.example.learningassistant.kb.service.DocumentParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentParserTest {

    private final DocumentParser parser = new DocumentParser();

    @Test
    void parseTxt() {
        byte[] data = "Hello 世界".getBytes(StandardCharsets.UTF_8);
        String text = parser.parse("test.txt", "text/plain", data);
        assertEquals("Hello 世界", text);
    }

    @Test
    void parseMd() {
        byte[] data = "# Title\n正文".getBytes(StandardCharsets.UTF_8);
        String text = parser.parse("note.md", "text/markdown", data);
        assertTrue(text.contains("正文"));
    }
}
