# Assignment 1: Text Analysis in the Cloud

## Overview
This project implements a distributed text analysis system using Amazon AWS (EC2, S3, SQS). The system performs natural language processing on text files using Stanford CoreNLP and is composed of three main components:

1.  **Local Application**: Runs on your local machine, uploads input files to S3, starts/checks for Manager, sends tasks, and downloads final results.
2.  **Manager**: Runs on EC2, manages the workload, spawns and scales Workers dynamically, aggregates results, and generates summary HTML files.
3.  **Workers**: Run on EC2, process text files using Stanford CoreNLP (POS, Constituency, Dependency parsing) with multi-threaded processing.

## Prerequisites
1.  Java 8 or higher
2.  Maven 3.x
3.  AWS Account with appropriate permissions
4.  AWS Credentials configured (in `~/.aws/credentials` or via environment variables)
5.  IAM Instance Profile named `LabInstanceProfile` with permissions for:
    - EC2 (describe instances, run instances, terminate instances)
    - S3 (read/write access)
    - SQS (create/read/delete queues and messages)
6.  Pre-configured AMIs:
    - Manager AMI: `ami-023186f8ad1eccd14` (must have `manager.jar` at `/home/ec2-user/manager.jar`)
    - Worker AMI: `ami-0ed259eaabf1ff80d` (must have `worker.jar` at `/home/ec2-user/worker.jar`)
    - Both AMIs should have Java 8+ and AWS CLI installed

## Configuration

### Utils.java Configuration
Open `src/main/java/com/dsp/assignment1/Utils.java` and verify/update:
-   `REGION`: AWS region (default: `US_EAST_1`)
-   `S3_BUCKET_NAME`: S3 bucket name (default: `dsp-assignment1-parser`)
-   `MAX_WORKERS`: Maximum number of workers (default: 18, accounting for EC2 instance limits)

### AMI Configuration
-   **Manager AMI ID**: Defined in `LocalApplication.java` as `MANAGER_AMI_ID = "ami-023186f8ad1eccd14"`
-   **Worker AMI ID**: Defined in `Manager.java` as `WORKER_AMI_ID = "ami-0ed259eaabf1ff80d"`
-   Update these values if using different AMIs

### Input File Format
The input file should be a text file with one task per line, formatted as:
```
ANALYSIS_TYPE<TAB>URL
```

Where:
-   `ANALYSIS_TYPE` is one of: `POS`, `CONSTITUENCY`, or `DEPENDENCY`
-   `URL` is a publicly accessible URL to a text file
-   Fields are separated by a tab character (`\t`)

**Example (`input-sample.txt`):**
```
POS	https://www.gutenberg.org/files/1659/1659-0.txt
CONSTITUENCY	https://www.gutenberg.org/files/1659/1659-0.txt
DEPENDENCY	https://www.gutenberg.org/files/1659/1659-0.txt
```

## Building the Project

### Option 1: Build All Components Together
```bash
mvn clean package
```
This creates `target/assignment1-1.0-SNAPSHOT.jar` with all components.

### Option 2: Build Components Separately
The project includes separate POM files for modular builds:

**Build Local Application:**
```bash
mvn clean package -f pom-local.xml
```
Creates `target/local-app.jar`

**Build Manager:**
```bash
mvn clean package -f pom-manager.xml
```
Creates `target/manager.jar` (must be uploaded to Manager AMI at `/home/ec2-user/manager.jar`)

**Build Worker:**
```bash
mvn clean package -f pom-worker.xml
```
Creates `target/worker.jar` (must be uploaded to Worker AMI at `/home/ec2-user/worker.jar`)

**Note:** The Manager and Worker JARs must be pre-installed on their respective AMIs before use.

## How to Run

### Run the Local Application
```bash
java -jar target/assignment1-1.0-SNAPSHOT.jar <inputFileName> <outputFileName> <n> [terminate]
```

**Parameters:**
-   `inputFileName`: Path to the local input file containing analysis tasks (tab-separated: ANALYSIS_TYPE and URL)
-   `outputFileName`: Path where the output HTML summary will be saved locally
-   `n`: Number of tasks per worker (determines worker scaling: `ceil(numTasks / n)` workers will be created)
-   `terminate`: (Optional) If set to "terminate", sends termination signal to Manager after job completion

**Example:**
```bash
java -jar target/assignment1-1.0-SNAPSHOT.jar input-sample.txt output.html 10 terminate
```

## System Architecture & Workflow

### Detailed Flow

1.  **Local Application Startup:**
    - Ensures S3 bucket exists (creates if missing)
    - Uploads input file to S3 at `input/{localAppId}/{filename}`
    - Checks for active Manager instance (by tag `Role=Manager`)
    - If no Manager exists, launches a new Manager EC2 instance (t3.large)
    - Creates a unique reply queue (`LocalAppQueue-{localAppId}`)
    - Sends task message to Manager: `"TASK \t inputFileKey \t n \t replyQueueUrl"`

2.  **Manager Processing:**
    - Listens on `ManagerTaskQueue` for task messages
    - Downloads input file from S3
    - Parses each line (ANALYSIS_TYPE and URL)
    - Creates worker tasks: sends messages to `WorkerTaskQueue` with format `"ANALYSIS_TYPE \t URL \t JOB_ID"`
    - Scales workers dynamically:
      - Calculates needed workers: `ceil(numTasks / n)`
      - Creates `max(0, neededWorkers - currentWorkers)` new workers (up to `MAX_WORKERS` limit)
      - Launches t3.large EC2 instances tagged with `Role=Worker`
    - Listens on `WorkerResultQueue` for completion messages
    - Aggregates results as they arrive
    - When all tasks for a job complete, generates HTML summary and uploads to S3
    - Sends completion message to Local Application's reply queue with S3 key

3.  **Worker Processing:**
    - On startup, downloads Stanford parser model from S3 (`stanford/englishPCFG.ser.gz`) to `/home/ec2-user/stanford/`
    - Listens on `WorkerTaskQueue` for tasks
    - For each task:
      - Downloads text file from the provided URL
      - Performs analysis based on type (POS, CONSTITUENCY, or DEPENDENCY)
      - Uses multi-threaded processing (one thread per CPU core) for parallel sentence parsing
      - Skips sentences longer than 80 words (performance optimization)
      - Uploads results to S3 at `output/{jobId}/{uuid}.txt`
      - Sends result message to Manager: `"JOB_ID \t INPUT_URL \t OUTPUT_S3_URL \t ANALYSIS_TYPE"`
    - Continues processing until terminated

4.  **Result Aggregation:**
    - Manager collects all results for a job
    - Generates HTML summary with format: `<p>ANALYSIS_TYPE: INPUT_URL OUTPUT_S3_URL</p>` for each result
    - Uploads summary to S3 at `output/{jobId}.html`
    - Notifies Local Application via reply queue

5.  **Local Application Completion:**
    - Receives completion message with S3 key
    - Downloads HTML summary from S3
    - Saves to local output file
    - If "terminate" flag was set, sends "TERMINATE" message to Manager
    - Cleans up reply queue
    - Exits

6.  **Termination (if requested):**
    - Manager receives "TERMINATE" message
    - Waits for all active jobs to complete
    - Terminates all Worker instances
    - Deletes shared SQS queues (`WorkerTaskQueue`, `WorkerResultQueue`, `ManagerTaskQueue`)
    - Terminates itself

## SQS Queues

The system uses three types of SQS queues:

1.  **ManagerTaskQueue**: Communication from Local Application to Manager
    - Message format: `"TASK \t inputFileKey \t n \t replyQueueUrl"` or `"TERMINATE"`

2.  **WorkerTaskQueue**: Task distribution from Manager to Workers
    - Message format: `"ANALYSIS_TYPE \t URL \t JOB_ID"`
    - Visibility timeout: 1800 seconds (30 minutes) to handle long-running analyses

3.  **WorkerResultQueue**: Results from Workers to Manager
    - Message format: `"JOB_ID \t INPUT_URL \t OUTPUT_S3_URL \t ANALYSIS_TYPE"`

4.  **LocalAppQueue-{localAppId}**: Per-job reply queue (temporary)
    - Message format: S3 key of summary HTML file

## Analysis Types

The system supports three types of natural language analysis using Stanford CoreNLP:

1.  **POS (Part-of-Speech Tagging)**: 
    - Assigns grammatical tags to each word (e.g., `Word/NN` for noun, `Word/VB` for verb)
    - Output format: Space-separated tagged words, one sentence per line

2.  **CONSTITUENCY (Constituency Parsing)**: 
    - Breaks text into syntactic constituents (phrases)
    - Output format: Penn Treebank-style parse tree, one sentence per line
    - Example: `(ROOT (S (NP (DT The) (NN cat)) (VP (VBD sat) (PP (IN on) (NP (DT the) (NN mat))))))`

3.  **DEPENDENCY (Dependency Parsing)**: 
    - Analyzes grammatical relationships between words (head-dependent relationships)
    - Output format: Typed dependencies, one sentence per line
    - Example: `[det(cat-2, The-1), nsubj(sat-3, cat-2), root(ROOT-0, sat-3), ...]`

## Performance Optimizations

-   **Multi-threading**: Workers use thread pools (one thread per CPU core) for parallel sentence processing
-   **Sentence filtering**: Sentences longer than 80 words are skipped to prevent memory issues
-   **Long polling**: SQS queues use 20-second long polling to reduce API calls
-   **Streaming I/O**: Workers write analysis results to temporary files to manage memory efficiently
-   **Worker scaling**: Workers are shared across multiple jobs and scaled based on workload

## Security

-   AWS Credentials are **not** hardcoded. They are loaded from:
    - Environment variables
    - `~/.aws/credentials` file (for Local Application)
    - IAM Instance Profile `LabInstanceProfile` (for EC2 instances)
-   **IMPORTANT**: 
    - Ensure your local machine has AWS credentials configured
    - The IAM Instance Profile must have permissions for EC2, S3, and SQS operations
    - EC2 instances use instance metadata service (IMDSv2) for secure credential access

## Persistence & Scalability

-   **SQS**: Ensures message durability. If a worker crashes, messages become visible again after the visibility timeout expires
-   **S3**: Stores all input files, intermediate results, and final summaries
-   **Concurrent Job Handling**: Manager uses a thread pool to handle multiple Local Application requests simultaneously
-   **Dynamic Scaling**: Workers are created and terminated based on workload
-   **Fault Tolerance**: Failed tasks can be retried (messages return to queue after visibility timeout)

## Output Format

The final HTML output contains one paragraph per analysis result:

```html
<html><body>
<p>POS: https://example.com/file.txt s3://bucket/output/jobId/uuid.txt</p>
<p>CONSTITUENCY: https://example.com/file.txt s3://bucket/output/jobId/uuid.txt</p>
<p>DEPENDENCY: https://example.com/file.txt s3://bucket/output/jobId/uuid.txt</p>
...
</body></html>
```

If an error occurs during processing, the output will show:
```html
<p>ANALYSIS_TYPE: URL Exception: error message</p>
```

## Important Notes

-   **AMI Preparation**: Manager and Worker JARs must be pre-installed on their respective AMIs before use
-   **Stanford Model**: The parser model (`englishPCFG.ser.gz`) must be uploaded to S3 at `{S3_BUCKET_NAME}/stanford/englishPCFG.ser.gz` before running workers
-   **Cleanup**: 
    - All files are stored in S3 bucket defined in `Utils.java`
    - To clean up, delete the S3 bucket or specific folders (`input/` and `output/`)
    - Shared queues are deleted on Manager termination
-   **Verification**: Always verify via AWS Console that all EC2 instances are terminated to avoid unexpected charges
-   **Access**: Input file URLs must be publicly accessible or accessible by EC2 instances (Workers download directly from URLs)
-   **Instance Limits**: Maximum 18 workers can be created (19 total instances - 1 manager = 18 workers)
-   **Instance Types**: Both Manager and Workers use `t3.large` instances
-   **Key Pair**: Instances are launched with key pair `vockey` (update in code if using different key)

## Test Results

### Instance Configuration
-   **Manager AMI**: `ami-023186f8ad1eccd14` (t3.large)
-   **Worker AMI**: `ami-0ed259eaabf1ff80d` (t3.large)
-   **Instance Type**: t3.large (2 vCPU, 8GB RAM)

### Performance Metrics
-   **Execution Time**: Less than 1 hour
-   **Tasks per Worker (n)**: 1
-   **Test Input**: Standard input file with multiple analysis tasks

## Troubleshooting

-   **Manager not starting**: Check AMI ID, IAM permissions, and that `manager.jar` exists on the AMI
-   **Workers not processing**: Verify `worker.jar` and Stanford model are accessible, check SQS queue permissions
-   **Results not appearing**: Check WorkerResultQueue for messages, verify S3 upload permissions
-   **Timeout issues**: Increase SQS visibility timeout if analyses take longer than 30 minutes

---

## Assignment Questions & Answers

### Scalability (1 million, 2 million, 1 billion clients)

The system is designed to handle moderate scale effectively: SQS can handle millions of messages with high throughput, and workers scale dynamically based on workload up to the 18-worker limit. However, for 1 million+ concurrent clients, the current architecture has bottlenecks: the single Manager instance becomes a bottleneck, the 18-worker limit constrains throughput, and Local Applications would need connection pooling or batching. For 1 billion clients, the system would require architectural changes: multiple Manager instances behind a load balancer, AWS Auto Scaling Groups for workers, SQS FIFO queues for ordering, and potentially a distributed job tracking system (e.g., DynamoDB) to replace the in-memory job map.

### Persistence & Failure Handling

The system handles most failure scenarios: SQS provides message durability (messages persist even if workers crash), workers use 1800-second visibility timeouts so failed tasks automatically return to the queue, S3 stores all results permanently, and broken communications are handled via SQS retries. However, there are gaps: if the Manager dies mid-job, job state is lost (jobs are stored in-memory ConcurrentHashMap), if a worker stalls longer than 30 minutes the message becomes visible to another worker (good), but there's no mechanism to detect and handle permanently stalled workers, and if SQS/S3 services fail, the system cannot proceed (no local fallback). The system could be improved with job state persistence (DynamoDB), dead letter queues for failed tasks, and health checks for worker instances.

### Threads Usage (When Good/Bad)

Threads are used appropriately in two places: Workers use thread pools (one thread per CPU core) for parallel sentence parsing, which is excellent for CPU-bound NLP tasks and maximizes hardware utilization. The Manager uses an ExecutorService (cached thread pool) to handle multiple concurrent jobs from different Local Applications, allowing it to process multiple requests simultaneously without blocking. Threads would be bad if used for I/O-bound operations without proper async handling, but the system correctly uses SQS long polling (20 seconds) to avoid busy-waiting threads. The only potential issue is that Manager's result listener processes up to 10 messages concurrently, which could cause memory pressure with very large jobs, but this is generally acceptable.

### Multiple Clients Running Simultaneously

Yes, the system is designed and tested to handle multiple clients concurrently: the Manager uses an ExecutorService to process each job in a separate thread, each job gets a unique jobId and its own reply queue (LocalAppQueue-{localAppId}), and workers pull from a shared task queue so they can process tasks from multiple jobs simultaneously. Jobs are tracked independently using ConcurrentHashMap with synchronized result lists, ensuring thread-safe aggregation. The system correctly finishes each job when all its tasks complete, and results are isolated per job, so multiple clients receive correct, non-interleaved results.

### System Understanding (Pen & Paper Walkthrough)

The system follows a clear asynchronous message-passing architecture: LocalApp uploads input to S3, sends "TASK" message to ManagerTaskQueue, and waits on its unique reply queue. Manager receives task, downloads input from S3, parses lines, sends each line as a task to WorkerTaskQueue with jobId, scales workers if needed, and listens on WorkerResultQueue. Workers pull tasks from WorkerTaskQueue, download text from URL, process with Stanford CoreNLP (multi-threaded), upload results to S3, and send result message to WorkerResultQueue. Manager aggregates results by jobId, when all tasks complete for a job, generates HTML summary, uploads to S3, and sends S3 key to LocalApp's reply queue. LocalApp receives S3 key, downloads HTML, saves locally, and exits. All communication is asynchronous via SQS/S3 with no direct blocking dependencies.

### Termination Process Management

The termination process is well-managed: when "TERMINATE" is sent, Manager sets terminateRequested flag and waits for all active jobs to complete (checks jobs.isEmpty() before proceeding), then terminates all Worker instances via EC2 API, deletes shared SQS queues (WorkerTaskQueue, WorkerResultQueue, ManagerTaskQueue), and terminates itself using instance metadata service. However, there's a potential issue: if Manager crashes during termination, workers and queues may remain (no cleanup), and if termination happens mid-job, that job's LocalApp reply queue may not be cleaned up (LocalApp handles its own queue deletion, but if Manager dies, the queue remains). The system could be improved with a termination confirmation mechanism and cleanup scripts.

### System Limitations Usage

The system respects and utilizes AWS limitations effectively: MAX_WORKERS=18 accounts for EC2 instance limits (19 total - 1 manager = 18 workers), workers use t3.large instances (2 vCPU, 8GB RAM) which is appropriate for Stanford CoreNLP processing, SQS visibility timeout of 1800 seconds (30 minutes) accommodates long-running analyses, and long polling (20 seconds) reduces API calls and costs. The system could better utilize limitations by implementing worker auto-scaling down (currently only scales up), using spot instances for cost savings, and implementing SQS batch operations (currently processes messages one at a time in some places) to maximize throughput within AWS service limits.

### Worker Efficiency (Are All Workers Working Hard?)

All workers work efficiently because they pull from a shared WorkerTaskQueue using long polling, so idle workers immediately grab new tasks as they become available. Workers process tasks in parallel (multi-threaded per worker), and there's no worker-to-worker communication that could cause blocking. However, some workers may appear to "slack" if tasks have varying difficulty (e.g., small files finish quickly while large files take longer), but this is expected behavior - the shared queue ensures work distribution. The only inefficiency is that workers process one task at a time (could process multiple tasks concurrently per worker), but this is a design choice to prevent memory exhaustion with large files. Overall, the work-stealing queue pattern ensures maximum utilization.

### Manager Responsibilities (Is It Doing Too Much?)

The Manager's responsibilities are appropriately defined and not excessive: it coordinates job lifecycle (receives tasks, tracks completion), manages worker scaling (calculates needed workers, launches instances), aggregates results (collects from workers, generates HTML), and handles termination (cleanup). These are all coordination tasks that belong in a manager component. The Manager does NOT do actual text processing (delegated to workers), does NOT download/process files (workers do this), and does NOT mix concerns - each component has clear separation. The only potential improvement would be to separate worker management into a separate "Scaler" component, but for this scale, the current design is appropriate and follows the manager-worker pattern correctly.

### Distributed System Understanding (Dependencies & Awaiting)

The system is truly distributed with minimal dependencies: LocalApp, Manager, and Workers are independent processes that communicate only via SQS (asynchronous messages) and S3 (shared storage), with no direct network calls or blocking dependencies. Workers don't wait for Manager (they poll SQS independently), Manager doesn't wait for workers (it processes results asynchronously), and components can fail independently without cascading failures. The only synchronous dependency is LocalApp waiting for Manager's reply (via SQS long polling), but this is acceptable as LocalApp needs the result. There are no circular dependencies, no tight coupling, and the system can scale horizontally (multiple workers) without coordination overhead. The system correctly implements distributed principles: stateless workers, message-based communication, and shared-nothing architecture via S3.

