# Serverless Infrastructure & Deployment Guide

This directory contains the Infrastructure as Code (IaC) definitions for the **Serverless Distributed Travel Booking System**.

## 🏗 Architecture Components

1. **DynamoDB Tables**:
   - `Travel_Payments`: Primary business state for payments.
   - `Travel_Flights`: Primary business state for flight reservations.
   - `Travel_Hotels`: Primary business state for hotel reservations.
   - `TravelOutbox`: Transactional Outbox table storing event logs with **DynamoDB Streams (`NEW_IMAGE`)** and **Time-To-Live (`ttl`)** enabled for automatic 7-day cleanup.

2. **Database-to-Event-Bus Relay**:
   - `TravelOutboxRelayFunction`: Automatically triggered by DynamoDB Streams from `TravelOutbox`, publishing parsed event payloads to `TravelBookingEventsTopic`.

3. **Asynchronous Notification Pipeline**:
   - `TravelBookingEventsTopic` (AWS SNS): Central event bus topic.
   - `TravelNotificationQueue` (AWS SQS): Subscribed to SNS topic.
   - `TravelNotificationConsumerFunction`: Lambda function polling SQS and dispatching emails through AWS SES.

4. **Resilience & DLQ Policy**:
   - `TravelNotificationDLQ` (AWS SQS): Dead Letter Queue receiving failed messages after **3 failed retry attempts** (`maxReceiveCount: 3`).
   - `TravelNotificationDLQAlarm` (CloudWatch Alarm): Monitors DLQ for poisoned/malformed payloads (`ApproximateNumberOfMessagesVisible >= 1`).

5. **Saga Orchestrator**:
   - `TravelBookingStateMachine` (AWS Step Functions): Payment-First Saga workflow orchestrating `Process Payment` -> `Book Flight` -> `Book Hotel` with automatic compensation rollbacks (`Refund Payment`, `Undo Flight`, `Undo Hotel`).

---

## 🚀 Deployment Instructions

### Prerequisites
- [AWS CLI](https://aws.amazon.com/cli/) configured (`aws configure`)
- [AWS SAM CLI](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/install-sam-cli.html)
- Java 21 & Maven 3.9+
- Verified AWS SES email address (e.g. `xyz@gmail.com`)

### 1. Build the Workers
From the repository root:
```bash
cd travel-booking-saga/travel-workers
mvn clean package
```

### 2. Deploy Infrastructure via AWS SAM
From the `infrastructure` directory:
```bash
cd ../infrastructure
sam build -t template.yaml
sam deploy --guided
```

---

## 🔁 Automated DLQ Redrive Mechanism

When an unhandled exception or malformed JSON payload causes an SQS message to fail 3 times, it is automatically routed to `TravelNotificationDLQ`.

### 1. Check DLQ Messages
```bash
aws sqs receive-message \
  --queue-url <TravelNotificationDLQ_URL> \
  --attribute-names All \
  --max-number-of-messages 10
```

### 2. Automated Redrive (Move Messages Back to Main Queue)
Using AWS SQS message move tasks:
```bash
aws sqs start-message-move-task \
  --source-arn <TravelNotificationDLQ_ARN> \
  --destination-arn <TravelNotificationQueue_ARN>
```

### 3. Check Redrive Status
```bash
aws sqs list-message-move-tasks \
  --source-arn <TravelNotificationDLQ_ARN>
```
