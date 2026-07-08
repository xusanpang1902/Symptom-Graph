package com.symptomgraph.integration.feishu;

public record FeishuImageResource(byte[] bytes, String contentType, String filename) {
}
