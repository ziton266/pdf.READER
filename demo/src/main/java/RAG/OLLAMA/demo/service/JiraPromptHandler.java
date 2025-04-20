package RAG.OLLAMA.demo.service;

import com.atlassian.jira.rest.client.api.domain.Version;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.Comment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.Map;

@Service
public class JiraPromptHandler {
    private static final Logger logger = LoggerFactory.getLogger(JiraPromptHandler.class);

    private final ChatModel chatModel;
    private final MyJiraClient jiraClient;
    private final VectorStore vectorStore;

    @Autowired
    public JiraPromptHandler(ChatModel chatModel, MyJiraClient jiraClient, VectorStore vectorStore) {
        this.chatModel = chatModel;
        this.jiraClient = jiraClient;
        this.vectorStore = vectorStore;
    }

    public String processQueryWithRagAndJira(String question) {
        // Step 1: First check if we have direct references to versions or tickets
        List<String> directVersions = extractVersionsFromQuestion(question);
        List<String> directTickets = extractTicketsFromQuestion(question);

        // If direct references found, skip the LLM check
        if (!directVersions.isEmpty() || !directTickets.isEmpty()) {
            logger.info("Direct references found - Versions: {}, Tickets: {}", directVersions, directTickets);
            return processWithJiraAndRag(question, directVersions, directTickets);
        }

        // Step 2: Otherwise, check if we need JIRA information via LLM
        String jiraCheckResponse = checkIfJiraRequired(question);
        logger.info("JIRA check response: {}", jiraCheckResponse);

        // Process the LLM response
        List<String> versions = extractValues(jiraCheckResponse, "VERSIONS:");
        List<String> tickets = extractValues(jiraCheckResponse, "JIRA_TICKETS:");

        // If JIRA is not required and no references detected, just use RAG
        if ("jira not required".equalsIgnoreCase(jiraCheckResponse.trim()) && versions.isEmpty() && tickets.isEmpty()) {
            return processWithRagOnly(question);
        }

        // Step 3: JIRA is required, process accordingly
        return processWithJiraAndRag(question, versions, tickets);
    }

    private List<String> extractVersionsFromQuestion(String question) {
        List<String> versions = new ArrayList<>();

        // Pattern to match version numbers like "version 10000" or "v10000"
        Pattern versionPattern = Pattern.compile("(?i)(?:version|v)\\s*(\\d+)");
        Matcher matcher = versionPattern.matcher(question);

        while (matcher.find()) {
            versions.add(matcher.group(1));
        }

        logger.info("Directly extracted versions from question: {}", versions);
        return versions;
    }

    private List<String> extractTicketsFromQuestion(String question) {
        List<String> tickets = new ArrayList<>();
        // Pattern to match JIRA ticket IDs like AIAG-3, PROJECT-123, etc.
        Pattern ticketPattern = Pattern.compile("([A-Z]+-\\d+)");
        Matcher matcher = ticketPattern.matcher(question);

        while (matcher.find()) {
            tickets.add(matcher.group(1));
        }

        logger.info("Directly extracted tickets from question: {}", tickets);
        return tickets;
    }

    private String checkIfJiraRequired(String question) {
        String prompt = "Given this question could you please check if we need information from jira system to respond? " +
                "If yes, answer with the following structure:\n" +
                "BEGIN\n" +
                "JIRA required\n" +
                "VERSIONS: [V1,V2,V3...]\n" +
                "JIRA_TICKETS: [J1,J2,J3...]\n" +
                "END\n" +
                "The separator should be a comma.\n" +
                "If jira is not required, return \"jira not required\".\n" +
                "The question is: " + question;

        return chatModel.call(prompt);
    }

    private String processWithRagOnly(String question) {
        // Just use RAG to find similar documents
        List<Document> similarDocuments = vectorStore.similaritySearch(question);
        return generateResponse(question, similarDocuments, null);
    }

    private String processWithJiraAndRag(String question, List<String> versions, List<String> tickets) {
        StringBuilder jiraContext = new StringBuilder();
        Map<String, Document> jiraDocuments = new HashMap<>();

        // Get JIRA version data if needed
        if (!versions.isEmpty()) {
            jiraContext.append("VERSION INFORMATION:\n");
            for (String versionId : versions) {
                try {
                    // Get version metadata
                    Version version = jiraClient.getVersionInfo(versionId);

                    StringBuilder versionMetadata = new StringBuilder();
                    versionMetadata.append("Version: ").append(version.getName()).append("\n")
                            .append("Description: ").append(version.getDescription() != null ? version.getDescription() : "N/A").append("\n")
                            .append("Release Date: ").append(version.getReleaseDate() != null ? version.getReleaseDate() : "Not specified").append("\n")
                            .append("Released: ").append(version.isReleased()).append("\n")
                            .append("Archived: ").append(version.isArchived()).append("\n");

                    jiraContext.append(versionMetadata.toString()).append("\n");

                    // Then get issues for this version
                    List<Issue> versionIssues = jiraClient.getTicketsByVersion(versionId);

                    // Create a document for each version to store in vector store
                    StringBuilder versionData = new StringBuilder();
                    versionData.append("Version ").append(versionId).append(" - ").append(versionIssues.size())
                            .append(" issues\n");

                    // Group by issue type
                    long bugCount = versionIssues.stream()
                            .filter(i -> i.getIssueType().getName().equalsIgnoreCase("Bug"))
                            .count();
                    long taskCount = versionIssues.stream()
                            .filter(i -> i.getIssueType().getName().equalsIgnoreCase("Task"))
                            .count();

                    versionData.append("Bugs: ").append(bugCount).append("\n");
                    versionData.append("Tasks: ").append(taskCount).append("\n\n");

                    // Add issues details
                    versionData.append("Issues in version ").append(versionId).append(":\n");
                    for (Issue issue : versionIssues) {
                        versionData.append("- ").append(issue.getKey()).append(": ")
                                .append(issue.getSummary()).append(" (")
                                .append(issue.getIssueType().getName()).append(")\n");
                    }

                    // Create document for vector store
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("source", "jira_version");
                    metadata.put("version", versionId);

                    Document versionDoc = new Document(versionData.toString(), metadata);
                    jiraDocuments.put("version_" + versionId, versionDoc);

                    // Add to JIRA context
                    jiraContext.append(versionData.toString()).append("\n");
                } catch (Exception e) {
                    logger.error("Error fetching version data for {}: {}", versionId, e.getMessage());
                    jiraContext.append("Error fetching data for version ").append(versionId).append(": ")
                            .append(e.getMessage()).append("\n\n");
                }
            }
        }

        // Get specific ticket data if needed
        if (!tickets.isEmpty()) {
            jiraContext.append("SPECIFIC ISSUES:\n");
            for (String ticketKey : tickets) {
                try {
                    Issue issue = jiraClient.getIssue(ticketKey);

                    // Create detailed information about the ticket
                    StringBuilder ticketData = new StringBuilder();
                    ticketData.append("Issue ").append(ticketKey).append(":\n")
                            .append("Summary: ").append(issue.getSummary()).append("\n")
                            .append("Type: ").append(issue.getIssueType().getName()).append("\n");

                    if (issue.getStatus() != null) {
                        ticketData.append("Status: ").append(issue.getStatus().getName()).append("\n");
                    }

                    if (issue.getDescription() != null) {
                        ticketData.append("Description: ").append(issue.getDescription()).append("\n");
                    }

                    if (issue.getAssignee() != null) {
                        ticketData.append("Assignee: ").append(issue.getAssignee().getDisplayName()).append("\n");
                    }

                    // Add comments if available
                    List<Comment> comments = jiraClient.getAllComments(ticketKey);
                    if (comments != null && !comments.isEmpty()) {
                        ticketData.append("Comments:\n");
                        for (Comment comment : comments) {
                            ticketData.append("- ")
                                    .append(comment.getAuthor().getDisplayName())
                                    .append(": ")
                                    .append(comment.getBody())
                                    .append("\n");
                        }
                    }

                    // Create document for vector store
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("source", "jira_ticket");
                    metadata.put("ticket", ticketKey);

                    Document ticketDoc = new Document(ticketData.toString(), metadata);
                    jiraDocuments.put("ticket_" + ticketKey, ticketDoc);

                    // Add to JIRA context
                    jiraContext.append(ticketData.toString()).append("\n");
                } catch (Exception e) {
                    logger.error("Error fetching ticket data for {}: {}", ticketKey, e.getMessage());
                    jiraContext.append("Error fetching data for ticket ").append(ticketKey).append(": ")
                            .append(e.getMessage()).append("\n\n");
                }
            }
        }

        // Store JIRA data in vector store for future use
        if (!jiraDocuments.isEmpty()) {
            try {
                List<Document> docsToStore = new ArrayList<>(jiraDocuments.values());
                vectorStore.accept(docsToStore);
                logger.info("Stored {} JIRA documents in vector store", docsToStore.size());
            } catch (Exception e) {
                logger.error("Error storing JIRA data in vector store: {}", e.getMessage());
            }
        }

        // Now get RAG documents - if we have JIRA data, include that in the search
        List<Document> similarDocuments = vectorStore.similaritySearch(question);

        // Combine JIRA data with RAG for the final response
        return generateResponse(question, similarDocuments, jiraContext.toString());
    }

    private List<String> extractValues(String jiraResponse, String prefix) {
        List<String> values = new ArrayList<>();
        Pattern pattern = Pattern.compile(prefix + "\\s*\\[(.*)\\]");
        Matcher matcher = pattern.matcher(jiraResponse);

        if (matcher.find() && matcher.groupCount() >= 1) {
            String valuesStr = matcher.group(1).trim();
            if (!valuesStr.isEmpty()) {
                values = Arrays.stream(valuesStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            }
        }

        return values;
    }

    private String generateResponse(String question, List<Document> similarDocuments, String jiraContext) {
        // Extract RAG context from similar documents
        String ragContext = similarDocuments.stream()
                .map(this::extractDocumentText)
                .collect(Collectors.joining("\n\n"));

        // Build the full prompt with all available information
        StringBuilder fullPrompt = new StringBuilder();
        fullPrompt.append("Please answer the following question using the provided elements:\n\n");
        fullPrompt.append("QUESTION:\n").append(question).append("\n\n");

        if (jiraContext != null && !jiraContext.isEmpty()) {
            fullPrompt.append("JIRA INFORMATION:\n").append(jiraContext).append("\n\n");
        }

        if (!ragContext.isEmpty()) {
            fullPrompt.append("DOCUMENT CONTEXT:\n").append(ragContext).append("\n\n");
        }

        // Call the LLM with the combined context
        logger.info("Generating response with context length: {}", fullPrompt.length());
        return chatModel.call(fullPrompt.toString());
    }

    private String extractDocumentText(Document document) {
        try {
            // Multiple strategies to extract text
            if (document.getMetadata() != null) {
                String[] possibleKeys = {"page_content", "text", "content", "document"};

                for (String key : possibleKeys) {
                    Object content = document.getMetadata().get(key);
                    if (content != null) {
                        return content.toString();
                    }
                }
            }

            return document.getText();
        } catch (Exception e) {
            logger.warn("Could not extract document text", e);
            return "Unable to extract document text";
        }
    }
}