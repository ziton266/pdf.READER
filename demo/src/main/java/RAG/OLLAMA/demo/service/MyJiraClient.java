package RAG.OLLAMA.demo.service;

import com.atlassian.jira.rest.client.api.IssueRestClient;
import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.Comment;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.SearchResult;
import com.atlassian.jira.rest.client.api.domain.Version;
import com.atlassian.jira.rest.client.api.domain.input.IssueInput;
import com.atlassian.jira.rest.client.api.domain.input.IssueInputBuilder;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

@Service
public class MyJiraClient {
    private static final Logger logger = LoggerFactory.getLogger(MyJiraClient.class);

    @Value("${jira.url}")
    private String jiraUrl;

    @Value("${jira.username}")
    private String username;

    @Value("${jira.password}")
    private String password;

    private JiraRestClient restClient;

    @PostConstruct
    public void init() {
        this.restClient = getJiraRestClient();
    }

    private JiraRestClient getJiraRestClient() {
        return new AsynchronousJiraRestClientFactory()
                .createWithBasicHttpAuthentication(getJiraUri(), this.username, this.password);
    }

    private URI getJiraUri() {
        return URI.create(this.jiraUrl);
    }

    public String createIssue(String projectKey, Long issueType, String issueSummary) {
        IssueRestClient issueClient = restClient.getIssueClient();

        // Créer le builder avec le projet et le résumé
        IssueInputBuilder issueBuilder = new IssueInputBuilder();
        issueBuilder.setProjectKey(projectKey)
                .setSummary(issueSummary);

        // Définir le type de problème correctement
        // Note: Il est préférable d'utiliser une valeur numérique pour issueType
        // Par exemple: 10001 pour "Story", 10002 pour "Task", etc.
        issueBuilder.setIssueTypeId(issueType);

        IssueInput newIssue = issueBuilder.build();
        return issueClient.createIssue(newIssue).claim().getKey();
    }

    public void updateIssueDescription(String issueKey, String newDescription) {
        IssueInput input = new IssueInputBuilder()
                .setDescription(newDescription)
                .build();
        restClient.getIssueClient()
                .updateIssue(issueKey, input)
                .claim();
    }

    /**
     * Récupère tous les tickets associés à une version Jira
     *
     * @param versionId L'identifiant ou le nom de la version Jira
     * @return Liste des tickets inclus dans cette version
     */
    public List<Issue> getTicketsByVersion(String versionId) {
        List<Issue> tickets = new ArrayList<>();

        try {
            // Requête JQL pour récupérer les tickets de cette version
            String jql = "fixVersion = '" + versionId + "'";

            // Exécuter la recherche (max 500 tickets)
            SearchResult result = restClient.getSearchClient()
                    .searchJql(jql, 500, 0, null)
                    .claim();

            // Ajouter les résultats à notre liste
            result.getIssues().forEach(tickets::add);

            return tickets;
        } catch (Exception e) {
            throw new RuntimeException("Erreur lors de la récupération des tickets pour la version: " + versionId, e);
        }
    }

    /**
     * Récupère les informations détaillées sur une version Jira spécifique
     *
     * @param versionId Identifiant ou nom de la version Jira
     * @return Objet Version contenant les métadonnées de la version
     */
    public Version getVersionInfo(String versionId) {
        try {
            // D'abord, essayons de déterminer le projet à partir du versionId
            String projectKey = getProjectKeyFromVersionId(versionId);

            logger.info("Looking for version '{}' in project '{}'", versionId, projectKey);

            // Récupérer toutes les versions du projet
            Iterable<Version> versions = restClient.getProjectClient()
                    .getProject(projectKey)
                    .claim()
                    .getVersions();

            // Chercher la version spécifique par nom ou ID
            for (Version version : versions) {
                if (version.getName().equals(versionId) ||
                        (version.getId() != null && version.getId().toString().equals(versionId))) {
                    return version;
                }
            }

            throw new RuntimeException("Version not found: " + versionId);
        } catch (Exception e) {
            throw new RuntimeException("Error retrieving version information: " + versionId, e);
        }
    }

    /**
     * Méthode utilitaire pour extraire la clé du projet à partir de l'ID de version
     *
     * @param versionId Identifiant ou nom de la version
     * @return Clé du projet associé à cette version
     */
    private String getProjectKeyFromVersionId(String versionId) {
        // Si l'ID de version contient un tiret, on prend la partie avant comme clé de projet
        if (versionId.contains("-")) {
            return versionId.split("-")[0];
        }

        // Sinon, on peut essayer de rechercher dans tous les projets
        try {
            // On pourrait faire une recherche JQL pour trouver le projet contenant cette version
            String jql = "fixVersion = '" + versionId + "'";
            SearchResult result = restClient.getSearchClient()
                    .searchJql(jql, 1, 0, null)
                    .claim();

            if (result.getIssues().iterator().hasNext()) {
                return result.getIssues().iterator().next().getProject().getKey();
            }
        } catch (Exception e) {
            logger.warn("Could not determine project key from version ID: {}", versionId, e);
        }

        // Si on ne peut pas déterminer le projet, on pourrait retourner un projet par défaut
        // ou lancer une exception
        throw new RuntimeException("Unable to determine project key from version ID: " + versionId);
    }

    public Issue getIssue(String issueKey) {
        return restClient.getIssueClient()
                .getIssue(issueKey)
                .claim();
    }

    public void deleteIssue(String issueKey, boolean deleteSubtasks) {
        restClient.getIssueClient()
                .deleteIssue(issueKey, deleteSubtasks)
                .claim();
    }

    public void addComment(Issue issue, String commentBody) {
        restClient.getIssueClient()
                .addComment(issue.getCommentsUri(), Comment.valueOf(commentBody));
    }

    public List<Comment> getAllComments(String issueKey) {
        return StreamSupport.stream(getIssue(issueKey).getComments().spliterator(), false)
                .collect(Collectors.toList());
    }
}