# Project Context: Serverless Distributed Travel Booking System

## 1. Project Overview
A fault-tolerant, event-driven travel booking microservices architecture built on AWS. The system utilizes a Payment-First Hybrid Saga pattern for distributed transactions and the Transactional Outbox pattern to guarantee data consistency between core database states and downstream event notifications.

## 2. Core Technology Stack
*   **Compute:** AWS Lambda (Java), AWS Step Functions (State Machine)
*   **Database:** Amazon DynamoDB (with Time-To-Live functionality)
*   **Messaging/Event Bus:** AWS SNS, AWS SQS, DynamoDB Streams
*   **External Integration:** AWS SES (Email Notifications)
*   **Utility:** AWS Lambda Powertools (Idempotency)
*   **API Routing:** Amazon API Gateway

## 3. Architecture Patterns Implemented
*   **Payment-First Saga Pattern:** Secures funds prior to booking resources. State machine orchestrates compensating transactions (Refund Payment, Undo Flight, Undo Hotel) gracefully upon failure.
*   **Transactional Outbox Pattern:** Eliminates the dual-write problem. Core business state (e.g., `TravelBookings`) and event triggers (e.g., `TravelOutbox`) are saved atomically using DynamoDB `TransactWriteItems`.
*   **Idempotency:** Secures payment endpoints against network retries by generating unique hashed locks via AWS Powertools and DynamoDB.

---

## 4. Current Progress (Completed Tasks)

### Architecture & Design
- [x] Transitioned from a "flight-first" to a "payment-first" workflow for better financial risk management.
- [x] Decoupled the **Booking Path** (synchronous orchestration) from the **Notification Path** (asynchronous choreography).
- [x] Defined the Step Functions Amazon States Language (ASL) definition for the main Saga workflow, including `Catch` blocks and ResultPaths for rollback routing.

### Data Storage & Schema
- [x] Designed the single-table schema for the `TravelOutbox` DynamoDB table (`eventId`, `bookingId`, `eventType`, `payload`, `createdAt`, `ttl`).
- [x] Mapped specific microservice event payloads (`PAYMENT_COMPLETED`, `FLIGHT_BOOKED`, `HOTEL_BOOKED`).
- [x] Configured Idempotency Store logic to utilize DynamoDB TTL for automated cleanup.

---

## 5. Pending Implementation (Remaining Tasks)

### Database to Event Bus Relay
- [x] Enable DynamoDB Streams (`NEW_IMAGE` view type) on the `TravelOutbox` table.
- [x] Develop the background relay Lambda function to process DynamoDB Stream batches.
- [x] Implement the AWS SDK `SnsClient` within the relay Lambda to publish parsed Outbox payloads to the central SNS Topic.

### Asynchronous Notification Pipeline
- [x] Provision the central AWS SNS Topic.
- [x] Provision the AWS SQS Queue and subscribe it to the SNS Topic.
- [x] Develop the notification consumer Lambda that polls SQS and triggers AWS SES to send the confirmation emails.

### Resilience & Error Handling
- [x] Configure a Dead Letter Queue (DLQ) attached to the main SQS queue (e.g., trigger after 3 failed retries).
- [x] Implement an automated DLQ redrive policy or alerting mechanism for malformed JSON payloads.
- [x] Configure `TransactWriteItems` inside the primary Java Lambda handlers (`TravelPaymentFunction`, `TravelFlightFunction`, `TravelHotelFunction`) to ensure atomic writes to both `TravelBookings` and `TravelOutbox`.

### Future Optimizations (Backlog)
- [ ] Enable AWS Lambda SnapStart / GraalVM for Java cold-start optimization.
- [ ] Integrate AWS X-Ray for distributed end-to-end tracing across Step Functions and SNS/SQS.
- [ ] Implement the Circuit Breaker pattern (Resilience4j) for third-party downstream APIs.