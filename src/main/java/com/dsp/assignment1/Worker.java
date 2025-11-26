package com.dsp.assignment1;

import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.pipeline.StanfordCoreNLP;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreeCoreAnnotations;
import edu.stanford.nlp.util.CoreMap;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

public class Worker {
    private static final Region REGION = Utils.REGION;
    private static final S3Client s3 = S3Client.builder().region(REGION).build();
    private static final SqsClient sqs = SqsClient.builder().region(REGION).build();

    private static StanfordCoreNLP pipeline;

    public static void main(String[] args) {
        System.out.println("Worker started.");

        // Initialize Stanford CoreNLP
        Properties props = new Properties();
        props.put("annotators", "tokenize, ssplit, pos, parse");
        // "parse" usually includes constituency and dependency
        pipeline = new StanfordCoreNLP(props);

        String taskQueueUrl = getQueueUrl(Utils.WORKER_TASK_QUEUE_NAME);
        String resultQueueUrl = getQueueUrl(Utils.WORKER_RESULT_QUEUE_NAME);

        System.out.println("Listening for tasks on " + taskQueueUrl);

        while (true) {
            ReceiveMessageResponse response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(taskQueueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(20)
                    .visibilityTimeout(60) // Extend if processing takes longer
                    .build());

            if (!response.messages().isEmpty()) {
                Message message = response.messages().get(0);
                try {
                    processMessage(message, resultQueueUrl);
                    
                    // Delete message ONLY after successful processing and reporting
                    sqs.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(taskQueueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
                            
                } catch (Exception e) {
                    e.printStackTrace();
                    // If an exception occurred (e.g. failed to send result), do NOT delete the message.
                    // Let visibility timeout expire so it can be retried.
                }
            }
        }
    }

    private static String getQueueUrl(String queueName) {
        return sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();
    }

    private static void processMessage(Message message, String resultQueueUrl) {
        String body = message.body();
        System.out.println("Processing: " + body);
        // Format: "ANALYSIS_TYPE \t URL \t JOB_ID"
        String[] parts = body.split("\t");
        String analysisType = parts[0];
        String url = parts[1];
        String jobId = parts[2];
        
        String resultUrlOrError;
        
        try {
            // Download file
            File inputFile = downloadFile(url);
            String content = new String(Files.readAllBytes(inputFile.toPath()));
            
            // Analyze
            String outputContent = analyze(content, analysisType);
            
            // Upload Result
            String outputKey = "output/" + jobId + "/" + UUID.randomUUID() + ".txt";
            uploadToS3(outputKey, outputContent);
            
            resultUrlOrError = "s3://" + Utils.S3_BUCKET_NAME + "/" + outputKey;
            
        } catch (Exception e) {
            e.printStackTrace();
            resultUrlOrError = "Exception: " + e.getMessage();
        }
        
        // Send Result
        // "JOB_ID \t INPUT_URL \t OUTPUT_S3_URL \t ANALYSIS_TYPE \t [ERROR]"
        String resultBody = String.join("\t", jobId, url, resultUrlOrError, analysisType);
        
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(resultQueueUrl)
                .messageBody(resultBody)
                .build());
    }

    private static File downloadFile(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        File temp = File.createTempFile("worker_input", ".txt");
        try (InputStream in = url.openStream()) {
            Files.copy(in, temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return temp;
    }

    private static String analyze(String text, String type) {
        // "In case the Stanford parser find it hard to analyze the whole text file, feel free to parse the file line-by-line"
        // Let's assume file is not huge, but for robustness we could split.
        // Given the assignment, let's do per-sentence or per-line if needed.
        // Stanford CoreNLP handles multiple sentences.
        
        Annotation document = new Annotation(text);
        pipeline.annotate(document);
        
        List<CoreMap> sentences = document.get(CoreAnnotations.SentencesAnnotation.class);
        StringBuilder result = new StringBuilder();
        
        for (CoreMap sentence : sentences) {
            switch (type) {
                case "POS":
                    for (CoreLabel token : sentence.get(CoreAnnotations.TokensAnnotation.class)) {
                        String word = token.get(CoreAnnotations.TextAnnotation.class);
                        String pos = token.get(CoreAnnotations.PartOfSpeechAnnotation.class);
                        result.append(word).append("/").append(pos).append(" ");
                    }
                    result.append("\n");
                    break;
                    
                case "CONSTITUENCY":
                    Tree tree = sentence.get(TreeCoreAnnotations.TreeAnnotation.class);
                    result.append(tree.toString()).append("\n");
                    break;
                    
                case "DEPENDENCY":
                    SemanticGraph dependencies = sentence.get(SemanticGraphCoreAnnotations.CollapsedCCProcessedDependenciesAnnotation.class);
                    result.append(dependencies.toString(SemanticGraph.OutputFormat.LIST)).append("\n");
                    break;
            }
        }
        
        return result.toString();
    }

    private static void uploadToS3(String key, String content) throws IOException {
        File temp = File.createTempFile("worker_output", ".txt");
        Files.write(temp.toPath(), content.getBytes());
        
        s3.putObject(PutObjectRequest.builder()
                .bucket(Utils.S3_BUCKET_NAME)
                .key(key)
                .build(), temp.toPath());
    }
}

