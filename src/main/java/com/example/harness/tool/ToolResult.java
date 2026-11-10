package com.example.harness.tool;

/** Structured result used internally before it is converted to model-readable text. */
public record ToolResult(
        boolean success,
        String content,
        String errorCode) {

    public ToolResult {
        content = content == null ? "" : content;
        if (success) {
            errorCode = null;
        } else if (errorCode == null || errorCode.isBlank()) {
            throw new IllegalArgumentException("失败的工具结果必须包含错误码");
        }
    }

    public static ToolResult success(String content) {
        return new ToolResult(true, content, null);
    }

    public static ToolResult failure(String errorCode, String message) {
        return new ToolResult(false, message, errorCode);
    }

    /** Keeps the current Qwen tool-message protocol based on string content. */
    public String toModelContent() {
        return success ? content : "工具错误[" + errorCode + "]：" + content;
    }
}
