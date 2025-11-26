package com.dsp.assignment1;

import software.amazon.awssdk.regions.Region;

public class Utils {
    public static final Region REGION = Region.US_EAST_1;
    
    // SQS Queues
    public static final String MANAGER_TASK_QUEUE_NAME = "ManagerTaskQueue";
    public static final String WORKER_TASK_QUEUE_NAME = "WorkerTaskQueue";
    public static final String WORKER_RESULT_QUEUE_NAME = "WorkerResultQueue";
    
    // S3
    public static final String BUCKET_NAME = "dsp-assignment1-bucket-" + System.currentTimeMillis(); 
    // Since we can't easily share dynamic bucket names across separate JVMs without a config file or convention,
    // we should probably use a fixed name or passed via args. 
    // For this assignment, let's assume the LocalApp creates the bucket and the Manager knows it 
    // OR the Manager is started with the bucket name.
    // However, the LocalApp starts the Manager.
    // Let's stick to a hardcoded unique-ish string for this user/assignment.
    public static final String S3_BUCKET_NAME = "dsp-assignment1-royho-parser"; 

    // AMI
    public static final String AMI_ID = "ami-0fa3fe0fa7920f68e";

    // Tags
    public static final String TAG_KEY_ROLE = "Role";
    public static final String TAG_VALUE_MANAGER = "Manager";
    public static final String TAG_VALUE_WORKER = "Worker";

    public static final int MAX_WORKERS = 18; // 19 instances limit - 1 manager = 18 workers
}
