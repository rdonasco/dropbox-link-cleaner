/*
 * Copyright 2025 The Dropbox Link Cleaner contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.donasco.roy.dropboxlinkcleaner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@SpringBootApplication
public class DropboxLinkCleanerApplication implements CommandLineRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${dropbox.access-token:}")
    private String dropboxAccessToken;

    @Value("${dropbox.api-base-url:https://api.dropboxapi.com/2}")
    private String dropboxApiBaseUrl;

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(DropboxLinkCleanerApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.run(args);
    }

    @Override
    public void run(String... args) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {

            System.out.println("=== Dropbox Shared Link Cleaner ===");
            String token = dropboxAccessToken;
            if (token == null || token.trim().isEmpty()) {
                System.out.println("Dropbox access token is not configured. Set env var DROPBOX_ACCESS_TOKEN or property 'dropbox.access-token'. Exiting.");
                return;
            }

            WebClient client = WebClient.builder()
                    .baseUrl(dropboxApiBaseUrl != null && !dropboxApiBaseUrl.trim().isEmpty() ? dropboxApiBaseUrl.trim() : "https://api.dropboxapi.com/2")
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token.trim())
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build();

            String cursor;
            List<JsonNode> buffer = new ArrayList<>();
            boolean hasMore;
            int page = 1;

            // First fetch
            JsonNode initial = continueSharedLinks(client, null);
            if (initial == null) {
                System.out.println("Failed to list shared links. Exiting.");
                return;
            }
            addLinksFromResponse(initial, buffer);
            hasMore = initial.path("has_more").asBoolean(false);
            cursor = initial.path("cursor").asText(null);

            while (true) {
                // Prefetch to ensure we have up to 10 items ready for the UI page
                while (buffer.size() < 10 && hasMore) {
                    JsonNode cont = continueSharedLinks(client, cursor);
                    if (cont == null) {
                        System.out.println("Failed to continue listing links with current cursor. Attempting to refresh...");
                        JsonNode refreshed = continueSharedLinks(client, null);
                        if (refreshed == null) {
                            System.out.println("Failed to refresh listing after continue failure. Exiting.");
                            return;
                        }
                        // Only populate from refreshed if buffer is currently empty; otherwise keep existing items
                        if (buffer.isEmpty()) {
                            addLinksFromResponse(refreshed, buffer);
                        }
                        hasMore = refreshed.path("has_more").asBoolean(false);
                        cursor = refreshed.path("cursor").asText(null);
                        // Break out of prefetch loop to render what we have, avoiding potential infinite loop
                        break;
                    } else {
                        addLinksFromResponse(cont, buffer);
                        hasMore = cont.path("has_more").asBoolean(false);
                        cursor = cont.path("cursor").asText(null);
                    }
                }

                if (buffer.isEmpty() && !hasMore) {
                    System.out.println("No more shared links.");
                    break;
                }

                // Page of 10
                List<JsonNode> pageItems = new ArrayList<>();
                for (int i = 0; i < 10 && !buffer.isEmpty(); i++) {
                    pageItems.add(buffer.remove(0));
                }

                displayPage(page, pageItems);

                final String selection = retrieveRevocationInput(reader);

                if (selection.equals("exit")) {
                    System.out.println("Exiting.");
                    break;
                } else if (selection.equals("next") || selection.isEmpty()) {
                    page++;
                    continue;
                }

                final Set<Integer> indexes = obtainSelectedItemIndexes(selection, pageItems);

                if (indexes.isEmpty()) {
                    System.out.println("No valid selections. Moving to next page.");
                    page++;
                    continue;
                }

                // Build selected items for confirmation with human-readable names/paths
                List<JsonNode> selectedItems = indexes.stream()
                        .map(pageItems::get)
                        .collect(Collectors.toList());

                List<String> urls = selectedItems.stream()
                        .map(n -> n.path("url").asText())
                        .filter(u -> u != null && !u.trim().isEmpty())
                        .collect(Collectors.toList());

                displaySelectedUrlToUnlink(urls, selectedItems);


                if (!confirmedYesToUnlink(reader)) {
                    System.out.println("Cancelled. Moving to next page.");
                    page++;
                    continue;
                }

                int success = 0, failed = 0;
                for (String u : urls) {
                    boolean ok = revokeSharedLink(client, u);
                    if (ok) {
                        System.out.println("Revoked: " + u);
                        success++;
                    } else {
                        System.out.println("FAILED:  " + u);
                        failed++;
                    }
                }
                System.out.printf("Done. Success: %d, Failed: %d%n", success, failed);
                page++;
            }

            System.out.println("Finished.");
        }
    }

    private static Set<Integer> obtainSelectedItemIndexes(String selection, List<JsonNode> pageItems) {
        Set<Integer> indexes = new LinkedHashSet<>();
        if (selection.equals("all")) {
            for (int i = 0; i < pageItems.size(); i++) indexes.add(i);
        } else if (selection.startsWith("all except")) {
            // Exclude listed numbers from the full selection
            String rest = selection.substring("all except".length()).trim();
            Set<Integer> excluded = new HashSet<>();
            if (!rest.isEmpty()) {
                String[] parts = rest.split(",");
                for (String p : parts) {
                    try {
                        int idx = Integer.parseInt(p.trim()) - 1;
                        if (idx >= 0 && idx < pageItems.size()) excluded.add(idx);
                    } catch (NumberFormatException ignore) {
                    }
                }
            }
            for (int i = 0; i < pageItems.size(); i++) {
                if (!excluded.contains(i)) indexes.add(i);
            }
        } else {
            String[] parts = selection.split(",");
            for (String p : parts) {
                try {
                    int idx = Integer.parseInt(p.trim()) - 1;
                    if (idx >= 0 && idx < pageItems.size()) indexes.add(idx);
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return indexes;
    }

    private static boolean confirmedYesToUnlink(BufferedReader reader) throws IOException {
        System.out.print("Type 'yes' to confirm deletion, anything else to cancel: ");
        String confirm = reader.readLine();
        return "yes".equalsIgnoreCase(confirm != null ? confirm.trim() : "");
    }

    private static void displaySelectedUrlToUnlink(List<String> urls, List<JsonNode> selectedItems) {
        System.out.println("You selected " + urls.size() + " link(s) to revoke:");
        for (JsonNode item : selectedItems) {
            String url = item.path("url").asText("");
            String name = item.path("name").asText("");
            if (name == null || name.trim().isEmpty()) {
                name = item.path("file").path("name").asText(item.path("folder").path("name").asText(""));
            }
            String pathDisplay = item.path("path_display").asText(item.path("path_lower").asText(""));
            String label;
            boolean hasName = name != null && !name.trim().isEmpty();
            boolean hasPath = pathDisplay != null && !pathDisplay.trim().isEmpty();
            if (hasName && hasPath) {
                label = name + " (" + pathDisplay + ")";
            } else if (hasName) {
                label = name;
            } else if (hasPath) {
                label = pathDisplay;
            } else {
                label = url;
            }
            System.out.println(" - " + label + (url != null && !url.trim().isEmpty() ? " [" + url + "]" : ""));
        }
    }

    private static String retrieveRevocationInput(BufferedReader reader) throws IOException {
        System.out.println();
        System.out.print("Select numbers to revoke (comma-separated), 'all' to revoke all on this page, 'all except 2,5' to revoke all but those, 'next' to skip, or 'exit' to quit:\n");
        String selection = reader.readLine();
        if (selection == null) selection = "";
        selection = selection.trim().toLowerCase(Locale.ROOT);
        return selection;
    }

    private static void displayPage(int page, List<JsonNode> pageItems) {
        System.out.println();
        System.out.println("--- Page " + page + " ---");
        for (int i = 0; i < pageItems.size(); i++) {
            JsonNode link = pageItems.get(i);
            String url = link.path("url").asText("(no url)");
            String name = link.path("name").asText("");
            if (name == null || name.trim().isEmpty()) {
                // Try to pull name from file/folder metadata variants
                name = link.path("file").path("name").asText(link.path("folder").path("name").asText(""));
            }
            String pathLower = link.path("path_lower").asText("");
            String expires = link.path("expires").asText("");
            System.out.printf("%2d) %s%n", (i + 1), (name == null || name.trim().isEmpty()) ? url : name + "  [" + url + "]");
            if (pathLower != null && !pathLower.trim().isEmpty()) {
                System.out.println("    path: " + pathLower);
            }
            if (expires != null && !expires.trim().isEmpty()) {
                System.out.println("    expires: " + expires);
            }
        }
    }

    private void addLinksFromResponse(JsonNode resp, List<JsonNode> dest) {
        if (resp == null) return;
        JsonNode links = resp.path("links");
        if (links.isArray()) {
            for (JsonNode l : links) dest.add(l);
        }
    }

    private JsonNode continueSharedLinks(WebClient client, String cursor) {
        try {
            Map<String, Object> body = new HashMap<>();
            if (cursor != null) {
                body.put("cursor", cursor);
            }

            String json = client.post()
                    .uri("/sharing/list_shared_links")
                    .bodyValue(body)
                    .exchangeToMono(resp -> {
                        if (resp.statusCode().is2xxSuccessful()) {
                            return resp.bodyToMono(String.class);
                        } else {
                            return resp.bodyToMono(String.class).defaultIfEmpty("")
                                    .flatMap(errBody -> {
                                        System.out.println("Error continuing list: HTTP " + resp.statusCode().value());
                                        if (!errBody.isEmpty()) {
                                            System.out.println("Response body: " + errBody);
                                        }
                                        return Mono.empty();
                                    });
                        }
                    })
                    .onErrorResume(e -> {
                        System.out.println("Error continuing list: " + e.getMessage());
                        return Mono.empty();
                    })
                    .block();
            if (json == null) return null;
            return MAPPER.readTree(json);
        } catch (Exception e) {
            System.out.println("Exception continueSharedLinks: " + e.getMessage());
            return null;
        }
    }

    private boolean revokeSharedLink(WebClient client, String url) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("url", url);

            Integer status = client.post()
                    .uri("/sharing/revoke_shared_link")
                    .bodyValue(body)
                    .exchangeToMono(resp -> {
                        if (resp.statusCode().is2xxSuccessful()) {
                            return Mono.just(resp.statusCode().value());
                        } else {
                            return resp.bodyToMono(String.class).defaultIfEmpty("")
                                    .map(errBody -> {
                                        System.out.println("Error revoking link: HTTP " + resp.statusCode().value());
                                        if (errBody != null && !errBody.isEmpty()) {
                                            System.out.println("Response body: " + errBody);
                                        }
                                        return resp.statusCode().value();
                                    });
                        }
                    })
                    .onErrorResume(e -> {
                        System.out.println("Error revoking link: " + e.getMessage());
                        return Mono.just(500);
                    })
                    .block();

            return status != null && status >= 200 && status < 300;
        } catch (Exception e) {
            System.out.println("Exception revokeSharedLink: " + e.getMessage());
            return false;
        }
    }

}
