package com.symptomgraph.service;

import com.symptomgraph.entity.CorpusRecord;

public interface MarkdownExportService {

    String export(CorpusRecord record);
}
