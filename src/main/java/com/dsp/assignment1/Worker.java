package com.dsp.assignment1;

import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.TaggedWord;
import edu.stanford.nlp.parser.lexparser.LexicalizedParser;
import edu.stanford.nlp.process.DocumentPreprocessor;
import edu.stanford.nlp.trees.GrammaticalStructure;
import edu.stanford.nlp.trees.GrammaticalStructureFactory;
import edu.stanford.nlp.trees.PennTreebankLanguagePack;
import edu.stanford.nlp.trees.Tree;
import edu.stanford.nlp.trees.TreebankLanguagePack;
import edu.stanford.nlp.trees.TypedDependency;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Worker {
    private static final Region REGION = Utils.REGION;
    private static final S3Client s3 = S3Client.builder().region(REGION).build();
    private static final SqsClient sqs = SqsClient.builder().region(REGION).build();

    private static LexicalizedParser parser;

    public static void main(String[] args) {
        System.out.println("Worker started.");

        // Initialize Stanford Parser (Legacy 3.6.0)
        // Load default english model
        try {
            parser = LexicalizedParser.loadModel("/home/ec2-user/stanford/englishPCFG.ser.gz");
        } catch (Exception e) {
            System.err.println("Failed to load parser model: " + e.getMessage());
            // e.printStackTrace();
            // If model is missing, we can't proceed. 
            // Maybe try to load from file system if it was downloaded?
            // For now assume it's in the classpath (in the shaded jar models).
        }

        String taskQueueUrl = getQueueUrl(Utils.WORKER_TASK_QUEUE_NAME);
        String resultQueueUrl = getQueueUrl(Utils.WORKER_RESULT_QUEUE_NAME);

        System.out.println("Listening for tasks on " + taskQueueUrl);

        while (true) {
            ReceiveMessageResponse response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(taskQueueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(20)
                    .visibilityTimeout(1800)
                    .build());

            if (!response.messages().isEmpty()) {
                Message message = response.messages().get(0);
                try {
                    processMessage(message, resultQueueUrl);
                    
                    sqs.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(taskQueueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());
                            
                } catch (Exception e) {
                    e.printStackTrace();
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
        String[] parts = body.split("\t");
        String analysisType = parts[0];
        String url = parts[1];
        String jobId = parts[2];
        
        String resultUrlOrError;
        
        try {
            File inputFile = downloadFile(url);
            String outputContent = analyze(inputFile, analysisType);
            
            String outputKey = "output/" + jobId + "/" + UUID.randomUUID() + ".txt";
            uploadToS3(outputKey, outputContent);
            
            resultUrlOrError = "s3://" + Utils.S3_BUCKET_NAME + "/" + outputKey;
            
        } catch (Exception e) {
            e.printStackTrace();
            resultUrlOrError = "Exception: " + e.getMessage();
        }
        
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

    private static String analyze(File file, String type) throws IOException {
        // Create temporary file for output (streaming to disk instead of RAM)
        File outputFile = File.createTempFile("worker_analysis", ".txt");
        
        // DocumentPreprocessor splits text into sentences efficiently
        DocumentPreprocessor tokenizer = new DocumentPreprocessor(file.getAbsolutePath());
        
        TreebankLanguagePack tlp = new PennTreebankLanguagePack();
        GrammaticalStructureFactory gsf = tlp.grammaticalStructureFactory();

        // 1. Define a thread pool (utilize all available CPUs)
        int cores = Runtime.getRuntime().availableProcessors();
        ExecutorService executor = Executors.newFixedThreadPool(cores);
        List<Future<String>> futures = new ArrayList<>();

        try {
            // 2. Submit parsing tasks to thread pool
            for (List<HasWord> sentence : tokenizer) {
                // Capture sentence in final variable for lambda
                final List<HasWord> sentenceCopy = new ArrayList<>(sentence);
                
                // Submit the parsing task to the pool
                futures.add(executor.submit(() -> {
                    // Validation check inside the thread
                    if (sentenceCopy.size() > 80) {
                        return "";
                    }
                    
                    // The heavy lifting - parsing
                    Tree parse = parser.apply(sentenceCopy);
                    return processParseResult(parse, type, gsf);
                }));
            }

            // 3. Collect results in order (preserves text order) and write to file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
                for (Future<String> future : futures) {
                    try {
                        String resultString = future.get(); // Waits for thread to finish
                        if (!resultString.isEmpty()) {
                            writer.write(resultString);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } finally {
            executor.shutdown();
        }
        
        // Read the file content and return as string
        byte[] content = Files.readAllBytes(outputFile.toPath());
        
        // Clean up temporary file
        outputFile.delete();
        
        return new String(content);
    }
    
    /**
     * Helper method to process parse result based on analysis type
     */
    private static String processParseResult(Tree parse, String type, GrammaticalStructureFactory gsf) {
        StringBuilder result = new StringBuilder();
        
        switch (type) {
            case "POS":
                List<TaggedWord> taggedWords = parse.taggedYield();
                for (TaggedWord tw : taggedWords) {
                    result.append(tw.word()).append("/").append(tw.tag()).append(" ");
                }
                result.append("\n");
                break;
                
            case "CONSTITUENCY":
                result.append(parse.toString()).append("\n");
                break;
                
            case "DEPENDENCY":
                GrammaticalStructure gs = gsf.newGrammaticalStructure(parse);
                Collection<TypedDependency> tdl = gs.typedDependencies();
                result.append(tdl.toString()).append("\n");
                break;
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
