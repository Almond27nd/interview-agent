/**
 * @author: 公众号：IT杨秀才
 * @doc: AI模拟面试官 - Java版（Spring AI Alibaba）
 */
package com.interview.agent.loader;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 文档加载器：根据文件扩展名自动选择解析器（与 Go 版本一致）
 * 支持 PDF / TXT / DOCX / Markdown
 */
@Slf4j
@Component
public class DocumentLoader {

    private final PdfLoader pdfLoader;
    private final DocxLoader docxLoader;

    public DocumentLoader(PdfLoader pdfLoader, DocxLoader docxLoader) {
        this.pdfLoader = pdfLoader;
        this.docxLoader = docxLoader;
    }

    /**
     * 根据文件扩展名自动选择解析器，返回纯文本内容
     */
    public String loadFile(String path) throws IOException {
        Path filePath = Path.of(path);
        if (!Files.exists(filePath)) {
            throw new IOException("loader: 文件不存在: " + path);
        }

        String ext = getExtension(path).toLowerCase();
        return switch (ext) {
            case ".txt", ".md", ".markdown" -> loadText(filePath);
            case ".pdf" -> pdfLoader.loadPDF(path);
            case ".docx" -> docxLoader.loadDOCX(path);
            case ".doc" -> throw new IOException("loader: 不支持 .doc 格式（Word 97-2003），请转换为 .docx");
            default -> loadText(filePath); // 尝试当纯文本读取
        };
    }

    /**
     * 解码 base64 文件并提取文本
     */
    public String parseBase64File(String filename, String base64Data) throws IOException {
        byte[] data = Base64.getDecoder().decode(base64Data);

        Path tmpPath = Path.of(System.getProperty("java.io.tmpdir"), "ia-upload-" + filename);
        try {
            Files.write(tmpPath, data);
            return loadFile(tmpPath.toString());
        } finally {
            Files.deleteIfExists(tmpPath);
        }
    }

    /**
     * 读取纯文本文件
     */
    private String loadText(Path path) throws IOException {
        String content = Files.readString(path).trim();
        if (content.isEmpty()) {
            throw new IOException("loader: 文件内容为空: " + path);
        }
        return content;
    }

    /**
     * 判断输入是否是 URL
     */
    public static boolean isURL(String s) {
        if (s == null) return false;
        s = s.trim();
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /**
     * 判断输入是否是文件路径
     */
    public static boolean isFilePath(String s) {
        if (s == null || s.trim().isEmpty()) return false;
        s = s.trim();
        if (s.startsWith("/") || s.startsWith("./") || s.startsWith("~/")) {
            return true;
        }
        String lower = s.toLowerCase();
        return lower.endsWith(".pdf") || lower.endsWith(".docx")
                || lower.endsWith(".txt") || lower.endsWith(".md");
    }

    private String getExtension(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot) : "";
    }
}
