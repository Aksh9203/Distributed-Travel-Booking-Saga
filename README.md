# Serverless Travel Booking Saga (AWS Step Functions & Java)

## 📌 Overview
A cloud-native distributed transaction system built on AWS. It implements the **Saga Design Pattern** to manage bookings across multiple microservices (Flight, Hotel, Payment). It features a "self-healing" mechanism that automatically rolls back transactions if any step fails, ensuring data consistency without a central database lock.

## 🏗 Architecture
* **Orchestrator:** AWS Step Functions (Standard Workflow)
* **Compute:** AWS Lambda (Java 21, Optimized with Custom Runtime logic)
* **Database:** Amazon DynamoDB (Single-table design concepts)
* **API:** AWS API Gateway (REST, Integrated directly with Step Functions)
* **Security:** API Keys & Throttling/Rate Limiting

## 🔄 The Saga Pattern (Workflow)
1.  **Book Flight** (Lambda) -> Saves to `Travel_Flights`
2.  **Book Hotel** (Lambda) -> Saves to `Travel_Hotels`
3.  **Process Payment** (Lambda) -> Saves to `Travel_Payments`

**Compensation Logic (Rollback):**
If the *Hotel* service is full or *Payment* is declined:
1.  The Step Function catches the error.
2.  Triggers `UndoHotel` (if needed).
3.  Triggers `UndoFlight` (Updates status to `CANCELLED`).
4.  System returns to a consistent state.

