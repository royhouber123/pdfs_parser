package com.dsp.assignment1;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.io.File;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.UUID;

public class LocalApplication {
    private static final Region REGION = Utils.REGION;
    public static final String MANAGER_AMI_ID = "ami-023186f8ad1eccd14";
    private static final S3Client s3 = S3Client.builder().region(REGION).build();
    private static final SqsClient sqs = SqsClient.builder().region(REGION).build();
    private static final Ec2Client ec2 = Ec2Client.builder().region(REGION).build();

    public static void main(String[] args) {
        if (args.length < 3) {
            System.out.println("Usage: java -jar yourjar.jar inputFileName outputFileName n [terminate]");
            System.exit(1);
        }

        String inputFileName = args[0];
        String outputFileName = args[1];
        int n = Integer.parseInt(args[2]);
        boolean terminate = args.length > 3 && args[3].equals("terminate");

        String localAppId = UUID.randomUUID().toString();
        System.out.println("Local Application ID: " + localAppId);

        try {
            // 1. Setup S3
            System.out.println("Setting up S3...");
            ensureBucketExists(Utils.S3_BUCKET_NAME);

            // 2. Upload input file to S3
            System.out.println("Uploading input file to S3...");
            String inputFileKey = "input/" + localAppId + "/" + new File(inputFileName).getName();
            uploadFileToS3(inputFileName, Utils.S3_BUCKET_NAME, inputFileKey);
            System.out.println("Input file uploaded to: " + Utils.S3_BUCKET_NAME + "/" + inputFileKey);

            // 2. Check for Manager
            System.out.println("Checking for active Manager...");
            ensureManagerActive();

            // 3. Create Reply-To Queue
            String replyQueueName = "LocalAppQueue-" + localAppId;
            String replyQueueUrl = createQueue(replyQueueName);
            System.out.println("Created reply queue: " + replyQueueUrl);

            // 4. Send message to Manager
            // Format: "TASK \t inputFileKey \t n \t replyQueueUrl"
            String messageBody = String.join("\t", "TASK", inputFileKey, String.valueOf(n), replyQueueUrl);
            String managerQueueUrl = getQueueUrl(Utils.MANAGER_TASK_QUEUE_NAME);
            sendMessage(managerQueueUrl, messageBody);
            System.out.println("Task sent to Manager.");

            // 5. Wait for response
            System.out.println("Waiting for response...");
            waitForResponse(replyQueueUrl, outputFileName);

            // 6. Handle termination
            if (terminate) {
                System.out.println("Sending terminate message to Manager...");
                sendMessage(managerQueueUrl, "TERMINATE");
            }

            // 7. Cleanup
            System.out.println("Cleaning up queue...");
            deleteQueue(replyQueueUrl);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void ensureBucketExists(String bucketName) {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            System.out.println("Bucket " + bucketName + " already exists.");
        } catch (NoSuchBucketException e) {
            System.out.println("Bucket " + bucketName + " does not exist. Creating...");
            s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
            System.out.println("Bucket " + bucketName + " created successfully.");
        } catch (Exception e) {
            // If headBucket fails for any other reason, try to create the bucket
            System.out.println("Error checking bucket existence: " + e.getMessage() + ". Attempting to create...");
            try {
                s3.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
                System.out.println("Bucket " + bucketName + " created successfully.");
            } catch (Exception createEx) {
                // If bucket already exists, that's fine
                if (createEx.getMessage() != null && createEx.getMessage().contains("BucketAlreadyExists")) {
                    System.out.println("Bucket " + bucketName + " already exists (created by another process).");
                } else {
                    throw createEx;
                }
            }
        }
    }

    private static void uploadFileToS3(String filePath, String bucket, String key) {
        s3.putObject(PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build(), Paths.get(filePath));
    }

    private static void ensureManagerActive() {
        Filter tagFilter = Filter.builder()
                .name("tag:" + Utils.TAG_KEY_ROLE)
                .values(Utils.TAG_VALUE_MANAGER)
                .build();
        
        Filter stateFilter = Filter.builder()
                .name("instance-state-name")
                .values("running", "pending")
                .build();

        DescribeInstancesResponse response = ec2.describeInstances(DescribeInstancesRequest.builder()
                .filters(tagFilter, stateFilter)
                .build());

        boolean managerActive = response.reservations().stream()
                .anyMatch(r -> !r.instances().isEmpty());

        if (!managerActive) {
            System.out.println("No active Manager found. Starting new Manager...");
            startManager();
        } else {
            System.out.println("Manager is already active.");
        }
    }

    private static void startManager() {
        // User Data script to start the Manager
        // Using baked-in JAR at /home/ec2-user/manager.jar
        String userDataScript = "#!/bin/bash\n" +
                // "aws s3 cp s3://" + Utils.S3_BUCKET_NAME + "/manager.jar /home/ec2-user/manager.jar\n" + // Skip download
                "java -jar /home/ec2-user/manager.jar\n";
        
        String userDataEncoded = Base64.getEncoder().encodeToString(userDataScript.getBytes());

        TagSpecification tagSpec = TagSpecification.builder()
                .resourceType(ResourceType.INSTANCE)
                .tags(Tag.builder().key(Utils.TAG_KEY_ROLE).value(Utils.TAG_VALUE_MANAGER).build())
                .build();

        RunInstancesRequest runRequest = RunInstancesRequest.builder()
                .imageId(MANAGER_AMI_ID)
                .instanceType(InstanceType.T3_LARGE) 
                .keyName("vockey")
                .maxCount(1)
                .minCount(1)
                .userData(userDataEncoded)
                .tagSpecifications(tagSpec)
                .iamInstanceProfile(IamInstanceProfileSpecification.builder().name("LabInstanceProfile").build()) 
                .build();

        ec2.runInstances(runRequest);
        System.out.println("Manager instance launched.");
    }

    private static String createQueue(String queueName) {
        CreateQueueRequest request = CreateQueueRequest.builder()
                .queueName(queueName)
                .build();
        return sqs.createQueue(request).queueUrl();
    }

    private static String getQueueUrl(String queueName) {
        try {
            return sqs.getQueueUrl(GetQueueUrlRequest.builder().queueName(queueName).build()).queueUrl();
        } catch (QueueDoesNotExistException e) {
            // If manager queue doesn't exist, create it (or Manager should create it? 
            // Usually Manager creates it, but LocalApp might start first.
            // Let's create it if missing.
            return createQueue(queueName);
        }
    }

    private static void sendMessage(String queueUrl, String body) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .build());
    }

    private static void waitForResponse(String queueUrl, String outputFileName) {
        while (true) {
            ReceiveMessageResponse response = sqs.receiveMessage(ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(1)
                    .waitTimeSeconds(20) // Long polling
                    .build());

            if (!response.messages().isEmpty()) {
                Message message = response.messages().get(0);
                String body = message.body(); // Body should be S3 key of summary file
                System.out.println("Received response: " + body);
                
                // Body: "done \t S3_KEY" or just S3_KEY? 
                // "done message from the manager to the application (S3 location of the analysis summary file)."
                
                String summaryFileKey = body; // Assuming body is just the key or URL
                
                // Download file
                System.out.println("Downloading summary file...");
                s3.getObject(GetObjectRequest.builder()
                        .bucket(Utils.S3_BUCKET_NAME)
                        .key(summaryFileKey)
                        .build(), Paths.get(outputFileName));
                
                System.out.println("Summary file saved to " + outputFileName);
                
                // Delete message
                sqs.deleteMessage(DeleteMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle())
                        .build());
                
                break;
            }
        }
    }

    private static void deleteQueue(String queueUrl) {
        sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(queueUrl).build());
    }
}

