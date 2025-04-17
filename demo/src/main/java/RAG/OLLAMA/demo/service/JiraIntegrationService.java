package RAG.OLLAMA.demo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.atlassian.jira.rest.client.api.domain.Issue;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class JiraIntegrationService {
    private static final Logger logger = LoggerFactory.getLogger(JiraIntegrationService.class);

    private final ChatModel chatModel;
    private final MyJiraClient jiraClient;

    @Autowired
    public JiraIntegrationService(ChatModel chatModel, MyJiraClient jiraClient) {
        this.chatModel = chatModel;
        this.jiraClient = jiraClient;
    }

    public String processQuery(String question) {
        if (isJiraQuery(question)) {
            return processJiraQuery(question);
        }
        return forwardToOllama(question, null);
    }

    private boolean isJiraQuery(String question) {
        String normalized = question.toLowerCase();
        return normalized.contains("jira") ||
                normalized.contains("issue") ||
                normalized.contains("bug") ||
                normalized.contains("task") ||
                normalized.contains("story") ||
                normalized.contains("ticket") ||
                normalized.contains("version") ||
                normalized.matches(".*\\b(v\\d+\\.\\d+\\.\\d+\\.\\d+)\\b.*");
    }

    public String processJiraQuery(String question) {
        String jiraQueryAnalysis = analyzeJiraQuery(question);

        try {
            String jiraData;

            if (jiraQueryAnalysis.startsWith("VERSION_BUGS:")) {
                String version = jiraQueryAnalysis.substring("VERSION_BUGS:".length()).trim();
                jiraData = fetchVersionBugsData(version);
            } else if (jiraQueryAnalysis.startsWith("VERSION_TASKS:")) {
                String version = jiraQueryAnalysis.substring("VERSION_TASKS:".length()).trim();
                jiraData = fetchVersionTasksData(version);
            } else if (jiraQueryAnalysis.startsWith("PROJECT_TASKS:")) {
                String project = jiraQueryAnalysis.substring("PROJECT_TASKS:".length()).trim();
                jiraData = fetchProjectTasksData(project);
            } else {
                String jql = createJqlFromQuestion(question);
                jiraData = fetchGeneralJiraData(jql);
            }

            return forwardToOllama(question, jiraData);

        } catch (Exception e) {
            logger.error("Error processing Jira query", e);
            String errorContext = "Error accessing Jira: " + e.getMessage() +
                    "\nThe system was unable to retrieve the requested Jira data.";
            return forwardToOllama(question, errorContext);
        }
    }

    private String forwardToOllama(String question, String context) {
        String prompt;

        if (context != null && !context.isEmpty()) {
            prompt = String.format(
                    "You are an assistant that helps with both general questions and queries about Jira. " +
                            "Please use the following context data when answering the question.\n\n" +
                            "CONTEXT:\n%s\n\n" +
                            "QUESTION:\n%s\n\n" +
                            "Based on the context, provide a helpful response. If the context doesn't have " +
                            "relevant information, respond naturally as if having a conversation.",
                    context, question
            );
        } else {
            prompt = question;
        }

        logger.info("Forwarding to Ollama: {}", prompt);
        return chatModel.call(prompt);
    }

    private String analyzeJiraQuery(String question) {
        Pattern versionBugPattern = Pattern.compile("(?:how many )?bugs? (?:in|for) (?:version )?([\\w.-]+)", Pattern.CASE_INSENSITIVE);
        Matcher bugMatcher = versionBugPattern.matcher(question);
        if (bugMatcher.find()) {
            return "VERSION_BUGS:" + bugMatcher.group(1);
        }

        Pattern versionTaskPattern = Pattern.compile("(?:how many )?tasks? (?:in|for) (?:version )?([\\w.-]+)", Pattern.CASE_INSENSITIVE);
        Matcher taskMatcher = versionTaskPattern.matcher(question);
        if (taskMatcher.find()) {
            return "VERSION_TASKS:" + taskMatcher.group(1);
        }

        Pattern projectTaskPattern = Pattern.compile("(?:how many )?tasks? (?:in|for) (?:project )?([\\w-]+)", Pattern.CASE_INSENSITIVE);
        Matcher projectMatcher = projectTaskPattern.matcher(question);
        if (projectMatcher.find() && !taskMatcher.find()) {
            return "PROJECT_TASKS:" + projectMatcher.group(1);
        }

        return "GENERAL";
    }

    private String createJqlFromQuestion(String question) {
        String prompt = String.format(
                "Convert this question about Jira into a JQL query. " +
                        "Respond with ONLY the JQL query, nothing else.\n\n" +
                        "Question: %s",
                question
        );

        String jql = chatModel.call(prompt).trim();
        logger.info("Generated JQL: {}", jql);
        return jql;
    }

    private String fetchVersionBugsData(String version) {
        logger.info("Fetching bugs for version: {}", version);

        List<Issue> allIssues = jiraClient.getTicketsByVersion(version);
        List<Issue> bugs = allIssues.stream()
                .filter(issue -> issue.getIssueType().getName().equalsIgnoreCase("Bug") ||
                        issue.getIssueType().getName().equalsIgnoreCase("Defect"))
                .collect(Collectors.toList());

        StringBuilder data = new StringBuilder();
        data.append("Version: ").append(version).append("\n");
        data.append("Total bugs found: ").append(bugs.size()).append("\n\n");

        for (Issue bug : bugs) {
            data.append("- Key: ").append(bug.getKey()).append("\n");
            data.append("  Summary: ").append(bug.getSummary()).append("\n");
            data.append("  Status: ").append(bug.getStatus().getName()).append("\n");
            data.append("  Type: ").append(bug.getIssueType().getName()).append("\n");
            data.append("  Priority: ").append(bug.getPriority() != null ? bug.getPriority().getName() : "None").append("\n\n");
        }

        return data.toString();
    }

    private String fetchVersionTasksData(String version) {
        logger.info("Fetching tasks for version: {}", version);

        List<Issue> allIssues = jiraClient.getTicketsByVersion(version);
        List<Issue> tasks = allIssues.stream()
                .filter(issue -> issue.getIssueType().getName().equalsIgnoreCase("Task"))
                .collect(Collectors.toList());

        StringBuilder data = new StringBuilder();
        data.append("Version: ").append(version).append("\n");
        data.append("Total tasks found: ").append(tasks.size()).append("\n\n");

        for (Issue task : tasks) {
            data.append("- Key: ").append(task.getKey()).append("\n");
            data.append("  Summary: ").append(task.getSummary()).append("\n");
            data.append("  Status: ").append(task.getStatus().getName()).append("\n");
            data.append("  Assignee: ").append(task.getAssignee() != null ? task.getAssignee().getDisplayName() : "Unassigned").append("\n\n");
        }

        return data.toString();
    }

    private String fetchProjectTasksData(String projectKey) {
        return "Project: " + projectKey + "\n" +
                "Note: Direct project task querying requires implementing additional JiraClient functionality.";
    }

    private String fetchGeneralJiraData(String jql) {
        return "JQL Query: " + jql + "\n" +
                "Note: Direct JQL querying requires implementing additional JiraClient functionality.";
    }
}
