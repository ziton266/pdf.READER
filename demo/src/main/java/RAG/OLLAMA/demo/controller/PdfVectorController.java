package RAG.OLLAMA.demo.controller;

import RAG.OLLAMA.demo.service.JiraPromptHandler;
import RAG.OLLAMA.demo.service.PdfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.ai.document.Document;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

@RestController
public class PdfVectorController {
    private static final Logger logger = LoggerFactory.getLogger(PdfVectorController.class);

    private final VectorStore vectorStore;
    private final ChatModel chatModel;
    private final PdfService pdfService;
    private final JiraPromptHandler jiraPromptHandler;

    public PdfVectorController(
            VectorStore vectorStore,
            ChatModel chatModel,
            PdfService pdfService,
            JiraPromptHandler jiraPromptHandler
    ) {
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
        this.pdfService = pdfService;
        this.jiraPromptHandler = jiraPromptHandler;
    }

    @GetMapping("/")
    public String simplify(@RequestParam(value = "question", defaultValue = "Summarize the document") String question) {
        try {
            logger.info("Processing question: {}", question);

            // First, check if this is a simple chat message or greeting
            if (isSimpleInteraction(question)) {
                logger.info("Processing as simple interaction");
                return chatModel.call(question);
            }

            // Use the new JIRA/RAG handler that determines if JIRA is needed and processes accordingly
            return jiraPromptHandler.processQueryWithRagAndJira(question);

        } catch (Exception e) {
            logger.error("Error processing request", e);
            // Forward the error to Ollama for appropriate response
            String errorPrompt = String.format(
                    "There was an error processing the request: %s. Please provide a helpful response to the user's question: %s",
                    e.getMessage(),
                    question
            );
            return chatModel.call(errorPrompt);
        }
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public String uploadAndQuery(
            @RequestParam("file") MultipartFile file,
            @RequestParam("question") String question) {
        try {
            // Process the uploaded PDF and store in vector database
            String response = pdfService.processPdfAndAnswerQuestion(file, question);
            return response;
        } catch (Exception e) {
            logger.error("Error processing PDF upload", e);
            // Forward the error to Ollama for appropriate response
            String errorPrompt = String.format(
                    "There was an error processing the PDF upload: %s. Please provide a helpful response to the user's question: %s",
                    e.getMessage(),
                    question
            );
            return chatModel.call(errorPrompt);
        }
    }

    @GetMapping("/jira")
    public String jiraQuery(@RequestParam("question") String question) {
        try {
            logger.info("Processing direct Jira query: {}", question);
            return jiraPromptHandler.processQueryWithRagAndJira(question);
        } catch (Exception e) {
            logger.error("Error processing Jira query", e);
            // Forward the error to Ollama for appropriate response
            String errorPrompt = String.format(
                    "There was an error processing the Jira query: %s. Please provide a helpful response to the user's question: %s",
                    e.getMessage(),
                    question
            );
            return chatModel.call(errorPrompt);
        }
    }

    private boolean isSimpleInteraction(String question) {
        String normalized = question.toLowerCase().trim();
        return normalized.equals("hello") ||
                normalized.equals("hi") ||
                normalized.equals("hey") ||
                normalized.startsWith("hello ") ||
                normalized.startsWith("hi ") ||
                normalized.length() < 10; // Very short messages likely simple interactions
    }
}