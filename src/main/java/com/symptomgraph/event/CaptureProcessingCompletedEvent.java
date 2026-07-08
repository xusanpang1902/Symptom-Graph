package com.symptomgraph.event;

public record CaptureProcessingCompletedEvent(Long captureRecordId, String captureId, String processStatus) {
}
