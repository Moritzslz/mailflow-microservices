package de.flowsuite.ragservice.common;

import de.flowsuite.mailflow.common.entity.RagUrl;

import java.util.Map;

public record CrawlingResult(RagUrl ragUrl, String bodyText, Map<String, String> links) {}
