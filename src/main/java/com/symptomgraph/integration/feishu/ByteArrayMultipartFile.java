package com.symptomgraph.integration.feishu;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

class ByteArrayMultipartFile implements MultipartFile {

    private final String name;
    private final String originalFilename;
    private final String contentType;
    private final byte[] content;

    ByteArrayMultipartFile(String name, String originalFilename, String contentType, byte[] content) {
        this.name = name;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.content = content == null ? new byte[0] : content.clone();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return content.length == 0;
    }

    @Override
    public long getSize() {
        return content.length;
    }

    @Override
    public byte[] getBytes() throws IOException {
        return content.clone();
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(java.io.File dest) throws IOException, IllegalStateException {
        throw new UnsupportedOperationException("transferTo is not supported for Feishu in-memory images");
    }
}
