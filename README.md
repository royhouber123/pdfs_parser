# Assignment 1: Text Analysis in the Cloud

## Overview
This project implements a distributed text analysis system using Amazon AWS (EC2, S3, SQS). The system is composed of:
1.  **Local Application**: Uploads input files and starts the Manager.
2.  **Manager**: Manages the workload, spawns Workers, and aggregates results.
3.  **Workers**: Process text files using Stanford CoreNLP (POS, Constituency, Dependency parsing).

## Prerequisites
1.  Java 8 or higher
2.  Maven
3.  AWS Account and Credentials (configured in `~/.aws/credentials` or environment variables)
4.  An Amazon Linux 2 AMI with Java installed (Update `AMI_ID` in `src/main/java/com/dsp/assignment1/Utils.java`)

## Configuration
Open `src/main/java/com/dsp/assignment1/Utils.java` and update:
-   `AMI_ID`: The ID of your AMI (must have Java and AWS CLI/Libs).
-   `S3_BUCKET_NAME`: (Optional) If you want a specific bucket name.

## How to Run

### 1. Build the Project
```bash
mvn clean package
```
This will create `target/assignment1-1.0-SNAPSHOT.jar`.

### 2. Run the Local Application
```bash
java -jar target/assignment1-1.0-SNAPSHOT.jar <inputFileName> <outputFileName> <n> [terminate]
```

-   `inputFileName`: Path to the local input file containing URLs.
-   `outputFileName`: Path where the output HTML will be saved.
-   `n`: Number of messages per worker (Worker to File ratio).
-   `terminate`: (Optional) "terminate" to shutdown the manager after completion.

**Example:**
```bash
java -jar target/assignment1-1.0-SNAPSHOT.jar input-sample.txt output.html 10 terminate
```

## System Architecture

1.  **Local Application** checks for an existing Manager. If none, it starts one.
2.  It uploads the input file to S3 and sends a task message to the Manager via SQS.
3.  **Manager** downloads the input file, splits it into tasks (one per line), and pushes them to the Worker Queue.
4.  **Manager** scales **Workers** based on the number of tasks and `n`.
5.  **Workers** pull messages, download the text file, run Stanford CoreNLP, upload results to S3, and notify the Manager.
6.  **Manager** collects results. Once all tasks for a job are done, it creates a summary HTML, uploads it to S3, and notifies the Local Application.
7.  **Local Application** downloads the HTML and exits.

## Security
-   AWS Credentials are **not** hardcoded. They are loaded from the environment or IAM Instance Profile.
-   **IMPORTANT**: Ensure your local machine has `~/.aws/credentials` setup.
-   Workers and Manager run with an IAM Role (Instance Profile) `LabInstanceProfile` (ensure this exists in your AWS account with S3/SQS/EC2 permissions).

## Persistence & Scalability
-   **SQS** ensures messages are not lost. If a worker crashes, the visibility timeout expires, and another worker picks up the task.
-   **Manager** uses a thread pool to handle multiple local applications concurrently.
-   **S3** stores all intermediate and final results.


## Analysis Types
The system supports the following analysis types using Stanford CoreNLP:
1.  **POS (Part-of-Speech Tagging)**: Assigns parts of speech to each word, such as noun, verb, adjective, etc. (e.g., `Word/NN`).
2.  **CONSTITUENCY (Constituency Parsing)**: Breaks a text into sub-phrases, or constituents. The output is a tree representation of the syntactic structure of the sentence.
3.  **DEPENDENCY (Dependency Parsing)**: Analyzes the grammatical structure of a sentence, establishing relationships between "head" words and words which modify those heads. The output is a list of typed dependencies.

## Important Notes
-   **Cleanup**: All files are uploaded to a specific S3 bucket defined in `Utils.java`. To clean up, simply delete this bucket or the specific folders (`input/` and `output/`) within it.
-   **Verification**: Although the Manager terminates workers, always verify via the AWS Console that all instances are terminated to avoid unexpected charges.
-   **Access**: Input files (URLs in the text file) must be publicly accessible or accessible by the EC2 instances for the Workers to download them.


