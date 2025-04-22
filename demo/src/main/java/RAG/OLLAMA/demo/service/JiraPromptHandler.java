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

        // If direct references found, process them and store in vector database
        if (!directVersions.isEmpty() || !directTickets.isEmpty()) {
            logger.info("Direct references found - Versions: {}, Tickets: {}", directVersions, directTickets);
            // Fetch and store JIRA data in vector store
            fetchAndStoreJiraData(directVersions, directTickets);
        } else {
            // Step 2: Otherwise, check if we need JIRA information via LLM
            String jiraCheckResponse = checkIfJiraRequired(question);
            logger.info("JIRA check response: {}", jiraCheckResponse);

            // Process the LLM response
            List<String> versions = extractValues(jiraCheckResponse, "VERSIONS:");
            List<String> tickets = extractValues(jiraCheckResponse, "JIRA_TICKETS:");

            // If JIRA references detected, fetch and store them
            if (!versions.isEmpty() || !tickets.isEmpty()) {
                fetchAndStoreJiraData(versions, tickets);
            }
        }

        // Step 3: Now use RAG to answer the question using all available information
        // This includes both previously stored documents and newly fetched JIRA data
        return processWithRagOnly(question);
    }

    private void fetchAndStoreJiraData(List<String> versions, List<String> tickets) {
        List<Document> jiraDocuments = new ArrayList<>();

        // Get JIRA version data if needed
        if (!versions.isEmpty()) {
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

                    // Create a document for version metadata
                    Map<String, Object> metadataMap = new HashMap<>();
                    metadataMap.put("source", "jira_version_metadata");
                    metadataMap.put("version_id", versionId);
                    metadataMap.put("version_name", version.getName());

                    Document versionMetadataDoc = new Document(versionMetadata.toString(), metadataMap);
                    jiraDocuments.add(versionMetadataDoc);

                    // Then get issues for this version
                    List<Issue> versionIssues = jiraClient.getTicketsByVersion(versionId);

                    // Create a document for version issues summary
                    StringBuilder versionSummary = new StringBuilder();
                    versionSummary.append("Version ").append(versionId).append(" - ").append(versionIssues.size())
                            .append(" issues\n");

                    // Group by issue type
                    long bugCount = versionIssues.stream()
                            .filter(i -> i.getIssueType().getName().equalsIgnoreCase("Bug"))
                            .count();
                    long taskCount = versionIssues.stream()
                            .filter(i -> i.getIssueType().getName().equalsIgnoreCase("Task"))
                            .count();

                    versionSummary.append("Bugs: ").append(bugCount).append("\n");
                    versionSummary.append("Tasks: ").append(taskCount).append("\n");

                    Map<String, Object> summaryMetadata = new HashMap<>();
                    summaryMetadata.put("source", "jira_version_summary");
                    summaryMetadata.put("version_id", versionId);
                    summaryMetadata.put("bug_count", bugCount);
                    summaryMetadata.put("task_count", taskCount);

                    Document versionSummaryDoc = new Document(versionSummary.toString(), summaryMetadata);
                    jiraDocuments.add(versionSummaryDoc);

                    // Create individual documents for each issue in the version
                    for (Issue issue : versionIssues) {
                        StringBuilder issueData = new StringBuilder();
                        issueData.append("Issue ").append(issue.getKey()).append(" in version ").append(versionId).append(":\n")
                                .append("Summary: ").append(issue.getSummary()).append("\n")
                                .append("Type: ").append(issue.getIssueType().getName()).append("\n");

                        if (issue.getStatus() != null) {
                            issueData.append("Status: ").append(issue.getStatus().getName()).append("\n");
                        }

                        Map<String, Object> issueMetadata = new HashMap<>();
                        issueMetadata.put("source", "jira_issue");
                        issueMetadata.put("issue_key", issue.getKey());
                        issueMetadata.put("version_id", versionId);
                        issueMetadata.put("issue_type", issue.getIssueType().getName());

                        Document issueDoc = new Document(issueData.toString(), issueMetadata);
                        jiraDocuments.add(issueDoc);
                    }

                    logger.info("Processed version {} with {} issues", versionId, versionIssues.size());
                } catch (Exception e) {
                    logger.error("Error fetching version data for {}: {}", versionId, e.getMessage());
                }
            }
        }

        // Get specific ticket data if needed
        if (!tickets.isEmpty()) {
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

                    // Create metadata map
                    Map<String, Object> metadata = new HashMap<>();
                    metadata.put("source", "jira_ticket");
                    metadata.put("ticket_key", ticketKey);
                    metadata.put("issue_type", issue.getIssueType().getName());
                    if (issue.getStatus() != null) {
                        metadata.put("status", issue.getStatus().getName());
                    }

                    // Create document for ticket basic info
                    Document ticketDoc = new Document(ticketData.toString(), metadata);
                    jiraDocuments.add(ticketDoc);

                    // Add comments as separate documents if available
                    List<Comment> comments = jiraClient.getAllComments(ticketKey);
                    if (comments != null && !comments.isEmpty()) {
                        for (Comment comment : comments) {
                            StringBuilder commentData = new StringBuilder();
                            commentData.append("Comment on issue ").append(ticketKey).append(" by ")
                                    .append(comment.getAuthor().getDisplayName()).append(":\n")
                                    .append(comment.getBody());

                            Map<String, Object> commentMetadata = new HashMap<>();
                            commentMetadata.put("source", "jira_comment");
                            commentMetadata.put("ticket_key", ticketKey);
                            commentMetadata.put("author", comment.getAuthor().getDisplayName());

                            Document commentDoc = new Document(commentData.toString(), commentMetadata);
                            jiraDocuments.add(commentDoc);
                        }
                    }

                    logger.info("Processed ticket {} with {} comments", ticketKey, comments.size());
                } catch (Exception e) {
                    logger.error("Error fetching ticket data for {}: {}", ticketKey, e.getMessage());
                }
            }
        }

        // Store all JIRA data in vector store for future use
        if (!jiraDocuments.isEmpty()) {
            try {
                vectorStore.accept(jiraDocuments);
                logger.info("Stored {} JIRA documents in vector store", jiraDocuments.size());
            } catch (Exception e) {
                logger.error("Error storing JIRA data in vector store: {}", e.getMessage());
            }
        }
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
        // Use RAG to find similar documents (which now includes JIRA data)
        List<Document> similarDocuments = vectorStore.similaritySearch(question);

        // Generate response with the similar documents
        return generateResponse(question, similarDocuments);
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

    private String generateResponse(String question, List<Document> similarDocuments) {
        // Extract context from similar documents
        String context = similarDocuments.stream()
                .map(this::extractDocumentText)
                .collect(Collectors.joining("\n\n"));

        // Build the full prompt
        StringBuilder fullPrompt = new StringBuilder();
        fullPrompt.append("Please answer the following question using the provided context:\n\n");
        fullPrompt.append("QUESTION:\n").append(question).append("\n\n");
        fullPrompt.append("CONTEXT:\n").append(context).append("\n\n");
        fullPrompt.append("Please provide a clear and concise answer based only on the information in the context. " +
                "If the context doesn't contain relevant information, say so rather than making up an answer.");

        // Call the LLM with the context
        logger.info("Generating response with context length: {}", fullPrompt.length());
        return chatModel.call(fullPrompt.toString());
    }

    private String extractDocumentText(Document document) {
        try {
            // First try to get the document text directly
            String text = document.getText();
            if (text != null && !text.isEmpty()) {
                // Add source information to help contextualize the information
                Map<String, Object> metadata = document.getMetadata();
                StringBuilder result = new StringBuilder(text);

                // Add a source identifier if available
                if (metadata != null && metadata.containsKey("source")) {
                    result.append("\n[Source: ").append(metadata.get("source"));

                    // Add more specific identifiers for different source types
                    if ("jira_version".equals(metadata.get("source")) && metadata.containsKey("version_id")) {
                        result.append(", Version: ").append(metadata.get("version_id"));
                    } else if ("jira_ticket".equals(metadata.get("source")) && metadata.containsKey("ticket_key")) {
                        result.append(", Ticket: ").append(metadata.get("ticket_key"));
                    } else if ("jira_comment".equals(metadata.get("source")) && metadata.containsKey("ticket_key")) {
                        result.append(", Ticket: ").append(metadata.get("ticket_key"));
                        if (metadata.containsKey("author")) {
                            result.append(", Author: ").append(metadata.get("author"));
                        }
                    }

                    result.append("]");
                }

                return result.toString();
            }

            // Try to extract from metadata if direct text is empty
            if (document.getMetadata() != null) {
                String[] possibleKeys = {"page_content", "text", "content", "document"};

                for (String key : possibleKeys) {
                    Object content = document.getMetadata().get(key);
                    if (content != null) {
                        return content.toString();
                    }
                }
            }

            return "Unable to extract text from document";
        } catch (Exception e) {
            logger.warn("Could not extract document text", e);
            return "Unable to extract document text";
        }
    }
}