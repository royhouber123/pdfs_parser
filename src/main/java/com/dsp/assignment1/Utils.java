package com.dsp.assignment1;

import software.amazon.awssdk.regions.Region;

public class Utils {
    public static final Region REGION = Region.US_EAST_1;
    
    // SQS Queues
    public static final String MANAGER_TASK_QUEUE_NAME = "ManagerTaskQueue";
    public static final String WORKER_TASK_QUEUE_NAME = "WorkerTaskQueue";
    public static final String WORKER_RESULT_QUEUE_NAME = "WorkerResultQueue";
    
    // S3
    public static final String S3_BUCKET_NAME = "dsp-assignment1-parser"; 

    // Tags
    public static final String TAG_KEY_ROLE = "Role";
    public static final String TAG_VALUE_MANAGER = "Manager";
    public static final String TAG_VALUE_WORKER = "Worker";

    public static final int MAX_WORKERS = 18; // 19 instances limit - 1 manager = 18 workers
}
