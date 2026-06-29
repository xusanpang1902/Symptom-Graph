package com.symptomgraph.controller;

import com.symptomgraph.dto.CorpusUploadResponse;
import com.symptomgraph.config.GeminiProperties;
import com.symptomgraph.config.OpenRouterProperties;
import com.symptomgraph.config.VisionProperties;
import com.symptomgraph.entity.CaptureRecord;
import com.symptomgraph.entity.CorpusRecord;
import com.symptomgraph.service.CaptureRecordService;
import com.symptomgraph.service.CorpusIngestionService;
import com.symptomgraph.service.CorpusRecordService;
import com.symptomgraph.service.OssStorageService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class CorpusPageController {

    private final CorpusIngestionService corpusIngestionService;
    private final CaptureRecordService captureRecordService;
    private final CorpusRecordService corpusRecordService;
    private final OssStorageService ossStorageService;
    private final VisionProperties visionProperties;
    private final GeminiProperties geminiProperties;
    private final OpenRouterProperties openRouterProperties;

    public CorpusPageController(CorpusIngestionService corpusIngestionService,
                                CaptureRecordService captureRecordService,
                                CorpusRecordService corpusRecordService,
                                OssStorageService ossStorageService,
                                VisionProperties visionProperties,
                                GeminiProperties geminiProperties,
                                OpenRouterProperties openRouterProperties) {
        this.corpusIngestionService = corpusIngestionService;
        this.captureRecordService = captureRecordService;
        this.corpusRecordService = corpusRecordService;
        this.ossStorageService = ossStorageService;
        this.visionProperties = visionProperties;
        this.geminiProperties = geminiProperties;
        this.openRouterProperties = openRouterProperties;
    }

    @GetMapping("/corpus/upload")
    public String uploadForm(Model model) {
        addModelSelectionAttributes(model, null, null);
        return "corpus-upload";
    }

    @GetMapping("/corpus/manage")
    public String manage() {
        return "corpus-manage";
    }

    @GetMapping("/corpus/analytics")
    public String analytics() {
        return "corpus-analytics";
    }

    @PostMapping("/corpus/upload")
    public String upload(@RequestParam("file") MultipartFile file,
                         @RequestParam(value = "force", defaultValue = "false") boolean force,
                         @RequestParam(value = "provider", required = false) String provider,
                         @RequestParam(value = "model", required = false) String modelName,
                         Model model) {
        CorpusUploadResponse result = corpusIngestionService.ingest(file, force, provider, modelName);
        model.addAttribute("result", result);
        model.addAttribute("signedUrl", resolveSignedUrl(result));
        addModelSelectionAttributes(model, result.getProvider(), result.getModel());
        return "corpus-upload";
    }

    private void addModelSelectionAttributes(Model model, String selectedProvider, String selectedModel) {
        String provider = StringUtils.hasText(selectedProvider) ? selectedProvider : visionProperties.getProvider();
        String modelName = StringUtils.hasText(selectedModel) ? selectedModel : resolveDefaultModel(provider);

        model.addAttribute("selectedProvider", provider);
        model.addAttribute("selectedModel", modelName);
        model.addAttribute("providerOptions", List.of("gemini", "openrouter"));
        model.addAttribute("modelOptionsByProvider", modelOptionsByProvider());
        model.addAttribute("defaultModelsByProvider", defaultModelsByProvider());
    }

    private String resolveDefaultModel(String provider) {
        if ("openrouter".equalsIgnoreCase(provider)) {
            return openRouterProperties.getModel();
        }
        return geminiProperties.getModel();
    }

    private Map<String, List<String>> modelOptionsByProvider() {
        Map<String, List<String>> options = new LinkedHashMap<>();
        options.put("gemini", geminiProperties.getModelOptions());
        options.put("openrouter", openRouterProperties.getModelOptions());
        return options;
    }

    private Map<String, String> defaultModelsByProvider() {
        Map<String, String> defaults = new LinkedHashMap<>();
        defaults.put("gemini", geminiProperties.getModel());
        defaults.put("openrouter", openRouterProperties.getModel());
        return defaults;
    }

    private String resolveSignedUrl(CorpusUploadResponse result) {
        if (result == null || !StringUtils.hasText(result.getCaptureId())) {
            return null;
        }

        List<CorpusRecord> records = corpusRecordService.listByCaptureId(result.getCaptureId());
        if (records.isEmpty() || !StringUtils.hasText(records.get(0).getOssObjectKey())) {
            if (result.getCaptureRecordId() == null) {
                return null;
            }
            CaptureRecord captureRecord = captureRecordService.getById(result.getCaptureRecordId());
            if (captureRecord == null || !StringUtils.hasText(captureRecord.getOssObjectKey())) {
                return null;
            }
            return ossStorageService.generateSignedUrl(captureRecord.getOssObjectKey());
        }
        return ossStorageService.generateSignedUrl(records.get(0).getOssObjectKey());
    }
}
