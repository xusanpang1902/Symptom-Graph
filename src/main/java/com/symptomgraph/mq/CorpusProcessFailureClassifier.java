package com.symptomgraph.mq;

import com.symptomgraph.exception.VisionRecognitionException;
import org.springframework.stereotype.Component;

@Component
public class CorpusProcessFailureClassifier {

    private static final String STATUS_MODEL_FAILED = "MODEL_FAILED";
    private static final String STATUS_PARSE_FAILED = "PARSE_FAILED";

    public CorpusProcessFailure classify(RuntimeException ex) {
        if (ex instanceof VisionRecognitionException recognitionException) {
            String parseStatus = recognitionException.getParseStatus();
            boolean retryable = STATUS_MODEL_FAILED.equals(parseStatus);
            return new CorpusProcessFailure(
                    STATUS_MODEL_FAILED.equals(parseStatus) ? "MODEL_FAILED" : "PARSE_FAILED",
                    parseStatus,
                    recognitionException.getMessage(),
                    recognitionException.getModelRawResponse(),
                    retryable
            );
        }

        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        if (message.contains("download OSS object")) {
            return new CorpusProcessFailure("OSS_DOWNLOAD_FAILED", STATUS_MODEL_FAILED, message, null, true);
        }
        if (message.contains("export markdown")) {
            return new CorpusProcessFailure("MARKDOWN_EXPORT_FAILED", STATUS_PARSE_FAILED, message, null, true);
        }
        return new CorpusProcessFailure("UNKNOWN", STATUS_PARSE_FAILED, message, null, true);
    }
}
