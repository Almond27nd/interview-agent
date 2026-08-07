/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.loader;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;

/**
 * PDF 文件解析器（使用 Apache PDFBox）
 */
@Component
public class PdfLoader {

    public String loadPDF(String path) throws IOException {
        try (PDDocument document = Loader.loadPDF(new File(path))) {
            int totalPages = document.getNumberOfPages();
            if (totalPages == 0) {
                throw new IOException("loader: PDF 页数为 0: " + path);
            }

            PDFTextStripper stripper = new PDFTextStripper();
            String content = stripper.getText(document).trim();

            if (content.isEmpty()) {
                throw new IOException("loader: PDF 未提取到文本（可能是扫描件）: " + path);
            }
            return content;
        }
    }
}
