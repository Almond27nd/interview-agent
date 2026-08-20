/**
 */
package com.interview.agent.loader;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Component;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.stream.Collectors;

/**
 * DOCX 文件解析器（使用 Apache POI）
 */
@Component
public class DocxLoader {

    public String loadDOCX(String path) throws IOException {
        try (FileInputStream fis = new FileInputStream(path);
             XWPFDocument document = new XWPFDocument(fis)) {

            String content = document.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"))
                    .trim();

            if (content.isEmpty()) {
                throw new IOException("loader: DOCX 内容为空: " + path);
            }
            return content;
        }
    }
}
