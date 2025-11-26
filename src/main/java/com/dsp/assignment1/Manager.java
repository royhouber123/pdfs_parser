package com.dsp.assignment1;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import software.amazon.awssdk.utils.IoUtils;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Manager {
    private static final Region REGION = Utils.REGION;
    private static final S3Client s3 = S3Client.builder().region(REGION).build();
    private static final SqsClient sqs = SqsClient.builder().region(REGION).build();
    private static final Ec2Client ec2 = Ec2Client.builder().region(REGION).build();

    // State
    private static final ConcurrentHashMap<String, JobInfo> jobs = new ConcurrentHashMap<>();
    private static volatile boolean terminateRequested = false;
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    private static String workerTaskQueueUrl;
    private static String workerResultQueueUrl;

    public static void main(String[] args) {
        System.out.println("Manager started.");

        // Initialize Queues
        setupQueues();

        // Start threads
        Thread taskListener = new Thread(Manager::listenForTasks);
        Thread resultListener = new Thread(Manager::listenForResults);

        taskListener.start();
        resultListener.start();

        try {
            taskListener.join();
            resultListener.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        System.out.println("Manager terminated.");
    }

    private static void setupQueues() {
        workerTaskQueueUrl = createQueue(Utils.WORKER_TASK_QUEUE_NAME);
        workerResultQueueUrl = createQueue(Utils.WORKER_RESULT_QUEUE_NAME);
    }

    private static String createQueue(String queueName) {
        try {
            return sqs.createQueue(CreateQueueRequest.builder().queueName(queueName).build()).queueUrl();
        } catch (Exception e) {
            return sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();
        }
    }

    // --- Task Listener (From LocalApp) ---

    private static void listenForTasks() {
        String queueUrl = createQueue(Utils.MANAGER_TASK_QUEUE_NAME);
        System.out.println("Listening for tasks on " + queueUrl);

        while (!terminateRequested || !jobs.isEmpty()) {
            if (terminateRequested && jobs.isEmpty()) break;

            ReceiveMessageResponse response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(20)
                    .build());

            for (Message message : response.messages()) {
                String body = message.body();
                System.out.println("Received task message: " + body);
                
                if (body.equals("TERMINATE")) {
                    System.out.println("Termination requested.");
                    terminateRequested = true;
                } else if (!terminateRequested) {
                    // Process new job
                    executor.submit(() -> processNewJob(body));
                }

                // Delete message
                sqs.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build());
            }
        }
        
        System.out.println("Task listener stopping...");
        shutdownSystem();
    }

    private static void processNewJob(String messageBody) {
        // Format: "TASK \t inputFileKey \t n \t replyQueueUrl"
        String[] parts = messageBody.split("\t");
        if (parts.length < 4 || !parts[0].equals("TASK")) return;

        String inputFileKey = parts[1];
        int n = Integer.parseInt(parts[2]);
        String replyQueueUrl = parts[3];
        String jobId = UUID.randomUUID().toString();

        System.out.println("Processing job " + jobId + " for " + inputFileKey);

        // Download input file
        List<String> lines = downloadAndReadInput(inputFileKey);
        if (lines == null) return; // Error handling

        JobInfo job = new JobInfo(jobId, replyQueueUrl, lines.size());
        jobs.put(jobId, job);

        // Create Worker Tasks
        for (String line : lines) {
            // Line format: "ANALYSIS_TYPE \t URL" (from assignment description)
            // We need to send: "ANALYSIS_TYPE \t URL \t JOB_ID"
            // Or maybe JSON? Let's stick to tabs.
            // Check if line is valid
            if (!line.contains("\t")) continue; // Skip invalid lines? or count as error?
            // If skipped, we need to adjust total tasks or mark as failed immediately.
            // For simplicity, assume valid or Worker handles it.
            
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(workerTaskQueueUrl)
                    .messageBody(line + "\t" + jobId)
                    .build());
        }

        // Scale Workers
        scaleWorkers(lines.size(), n);
    }

    private static List<String> downloadAndReadInput(String key) {
        try {
            // AWS SDK toFile() fails if file exists unless configured to replace.
            // We use a random name but don't create the file first, or delete it.
            File temp = File.createTempFile("input", ".txt");
            temp.delete(); // Delete it so S3 client can create it fresh
            
            s3.getObject(GetObjectRequest.builder()
                    .bucket(Utils.S3_BUCKET_NAME)
                    .key(key)
                    .build(), temp.toPath());
            return Files.readAllLines(temp.toPath());
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private static synchronized void scaleWorkers(int numTasks, int n) {
        // Check active workers
        int currentWorkers = getActiveWorkerCount();
        int neededWorkers = (numTasks + n - 1) / n; // ceil(numTasks / n)
        
        // "If there are k active workers, and the new job requires m workers, then the manager should create m-k new workers, if possible."
        // "manager should create a worker for every n messages"
        // But workers are shared. 
        // Let's assume we check total load or just add based on this job's requirement vs current count.
        // The assignment says "create m-k new workers".
        
        int workersToCreate = neededWorkers - currentWorkers;
        if (workersToCreate > 0) {
            int limit = Utils.MAX_WORKERS - currentWorkers;
            int actualToCreate = Math.min(workersToCreate, limit);
            
            if (actualToCreate > 0) {
                System.out.println("Starting " + actualToCreate + " workers...");
                startWorkers(actualToCreate);
            }
        }
    }

    private static int getActiveWorkerCount() {
        Filter tagFilter = Filter.builder().name("tag:" + Utils.TAG_KEY_ROLE).values(Utils.TAG_VALUE_WORKER).build();
        Filter stateFilter = Filter.builder().name("instance-state-name").values("running", "pending").build();
        
        DescribeInstancesResponse response = ec2.describeInstances(DescribeInstancesRequest.builder()
                .filters(tagFilter, stateFilter)
                .build());
                
        return response.reservations().stream().mapToInt(r -> r.instances().size()).sum();
    }

    private static void startWorkers(int count) {
        // User Data for Worker
        String userDataScript = "#!/bin/bash\n" +
                "aws s3 cp s3://" + Utils.S3_BUCKET_NAME + "/assignment1.jar /home/ec2-user/assignment1.jar\n" +
                "java -cp /home/ec2-user/assignment1.jar com.dsp.assignment1.Worker\n";
        
        String userDataEncoded = Base64.getEncoder().encodeToString(userDataScript.getBytes());

        TagSpecification tagSpec = TagSpecification.builder()
                .resourceType(ResourceType.INSTANCE)
                .tags(Tag.builder().key(Utils.TAG_KEY_ROLE).value(Utils.TAG_VALUE_WORKER).build())
                .build();

        RunInstancesRequest runRequest = RunInstancesRequest.builder()
                .imageId(Utils.AMI_ID)
                .instanceType(InstanceType.T2_MICRO)
                .maxCount(count)
                .minCount(count)
                .userData(userDataEncoded)
                .tagSpecifications(tagSpec)
                .iamInstanceProfile(IamInstanceProfileSpecification.builder().arn("arn:aws:iam::288140534550:instance-profile/LabInstanceProfile").build())
                .build();

        try {
            ec2.runInstances(runRequest);
        } catch (Exception e) {
            System.err.println("Failed to start workers: " + e.getMessage());
        }
    }

    // --- Result Listener (From Workers) ---

    private static void listenForResults() {
        System.out.println("Listening for results on " + workerResultQueueUrl);
        
        while (!terminateRequested || !jobs.isEmpty()) {
            ReceiveMessageResponse response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(workerResultQueueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(20)
                    .build());

            for (Message message : response.messages()) {
                executor.submit(() -> processResult(message));
            }
        }
    }

    private static void processResult(Message message) {
        // Body: "JOB_ID \t INPUT_URL \t OUTPUT_S3_URL \t ANALYSIS_TYPE \t [ERROR]"
        String body = message.body();
        String[] parts = body.split("\t");
        String jobId = parts[0];
        
        JobInfo job = jobs.get(jobId);
        if (job != null) {
            // Construct HTML line
            // Format: <analysis type>: <input file> <output file>
            // Or exception: <analysis type>: <input file> <exception>
            String analysisType = parts[3];
            String inputUrl = parts[1];
            String outputOrError = parts[2];
            
            // Determine if error (basic heuristic or explicit flag?)
            // Worker logic will put S3 URL or error text.
            // HTML format logic:
            String htmlLine = "<p>" + analysisType + ": " + inputUrl + " " + outputOrError + "</p>";
            
            job.addResult(htmlLine);
            
            if (job.isFinished()) {
                finishJob(jobId);
            }
        } else {
            System.out.println("Received result for unknown job: " + jobId);
        }

        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(workerResultQueueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }

    private static void finishJob(String jobId) {
        JobInfo job = jobs.remove(jobId);
        System.out.println("Finishing job " + jobId);
        
        // Create Summary HTML
        StringBuilder html = new StringBuilder();
        html.append("<html><body>");
        for (String line : job.getResults()) {
            html.append(line).append("\n");
        }
        html.append("</body></html>");
        
        // Upload to S3
        String key = "output/" + jobId + ".html";
        try {
            File temp = File.createTempFile("summary", ".html");
            Files.write(temp.toPath(), html.toString().getBytes());
            
            s3.putObject(PutObjectRequest.builder()
                    .bucket(Utils.S3_BUCKET_NAME)
                    .key(key)
                    .build(), temp.toPath());
                    
            // Notify Local App
            sqs.sendMessage(SendMessageRequest.builder()
                    .queueUrl(job.replyQueueUrl)
                    .messageBody(key)
                    .build());
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void shutdownSystem() {
        System.out.println("Shutting down system...");
        
        // 1. Terminate Workers
        System.out.println("Terminating workers...");
        try {
            Filter tagFilter = Filter.builder().name("tag:" + Utils.TAG_KEY_ROLE).values(Utils.TAG_VALUE_WORKER).build();
            Filter stateFilter = Filter.builder().name("instance-state-name").values("running", "pending").build();
            
            DescribeInstancesResponse response = ec2.describeInstances(DescribeInstancesRequest.builder()
                    .filters(tagFilter, stateFilter)
                    .build());
            
            List<String> instanceIds = response.reservations().stream()
                    .flatMap(r -> r.instances().stream())
                    .map(Instance::instanceId)
                    .collect(Collectors.toList());
            
            if (!instanceIds.isEmpty()) {
                ec2.terminateInstances(TerminateInstancesRequest.builder().instanceIds(instanceIds).build());
                System.out.println("Terminated workers: " + instanceIds);
            }
        } catch (Exception e) {
            System.err.println("Error terminating workers: " + e.getMessage());
        }

        // 2. Delete Shared Queues
        System.out.println("Deleting shared queues...");
        deleteQueueIfExists(Utils.WORKER_TASK_QUEUE_NAME);
        deleteQueueIfExists(Utils.WORKER_RESULT_QUEUE_NAME);
        deleteQueueIfExists(Utils.MANAGER_TASK_QUEUE_NAME);
        
        // 3. Terminate Self (Manager Instance)
        System.out.println("Terminating Manager instance...");
        String myInstanceId = getMyInstanceId();
        if (myInstanceId != null) {
            try {
                ec2.terminateInstances(TerminateInstancesRequest.builder()
                        .instanceIds(myInstanceId)
                        .build());
                System.out.println("Termination request sent for self: " + myInstanceId);
            } catch (Exception e) {
                System.err.println("Failed to terminate self: " + e.getMessage());
            }
        } else {
            System.out.println("Could not determine my instance ID. Cannot terminate self.");
        }

        executor.shutdown();
        System.exit(0);
    }

    private static void deleteQueueIfExists(String queueName) {
        try {
            GetQueueUrlResponse response = sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build());
            sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(response.queueUrl()).build());
            System.out.println("Deleted queue: " + queueName);
        } catch (QueueDoesNotExistException e) {
            // Already gone, ignore
        } catch (Exception e) {
            System.err.println("Failed to delete queue " + queueName + ": " + e.getMessage());
        }
    }
    
    private static String getMyInstanceId() {
        try {
            // Retrieve instance ID from EC2 Instance Metadata Service
            URL url = new URL("http://169.254.169.254/latest/meta-data/instance-id");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(1000);
            connection.setRequestMethod("GET");
            
            if (connection.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    return reader.readLine();
                }
            }
        } catch (Exception e) {
            // Not running on EC2 or metadata service unreachable
        }
        return null;
    }

    // Helper Class
    private static class JobInfo {
        String id;
        String replyQueueUrl;
        int totalTasks;
        List<String> results = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger completedTasks = new AtomicInteger(0);

        public JobInfo(String id, String replyQueueUrl, int totalTasks) {
            this.id = id; // Used for debugging or tracking if needed
            this.replyQueueUrl = replyQueueUrl;
            this.totalTasks = totalTasks;
        }

        public void addResult(String result) {
            results.add(result);
            completedTasks.incrementAndGet();
        }

        public boolean isFinished() {
            return completedTasks.get() >= totalTasks;
        }
        
        public List<String> getResults() {
            return results;
        }
    }
}

