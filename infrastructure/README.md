# Deploy the travel booking saga

This guide deploys the system by hand in the AWS console. The live deployment was built this way.

`template.yaml` is an AWS SAM template that has never been deployed. It has known problems, which are listed in [The SAM template](#the-sam-template). Don't rely on it without fixing and testing it first.

Use one region for every resource. The live deployment uses `us-east-1`.

## Before you start

You need these things:

- An AWS account and console access.
- Java 21 and Maven 3.9 or later.
- An email address you can verify in SES.

## 1. Build the Lambda jar

From the repository root, run:

```bash
cd travel-workers
mvn -B package
```

Maven writes `travel-workers/target/travel-workers-1.0-SNAPSHOT.jar`, which is about 21 MB and contains every dependency. All five Lambda functions use this one jar. Don't upload `original-travel-workers-1.0-SNAPSHOT.jar`, because it has no dependencies.

## 2. Create the DynamoDB tables

Create four tables:

| Table | Partition key | Extra settings |
| :--- | :--- | :--- |
| `Travel_Payments` | `booking_id`, String | None |
| `Travel_Flights` | `booking_id`, String | None |
| `Travel_Hotels` | `booking_id`, String | None |
| `TravelOutbox` | `eventId`, String | DynamoDB stream with view type **New image**. TTL on the attribute `ttl`. |

## 3. Create the SNS topic

Create a **Standard** topic named `TravelBookingAlerts`.

## 4. Create the SQS queues

1. Create a **Standard** queue named `TravelNotificationDLQ`. Set **Message retention period** to **14 days**. Leave its own dead-letter queue setting off.
2. Create a **Standard** queue named `TravelNotificationQueue` with these settings:
   - **Visibility timeout:** 60 seconds.
   - **Dead-letter queue:** on, set to `TravelNotificationDLQ`.
   - **Maximum receives:** 3.
3. Open `TravelNotificationQueue` and choose **Subscribe to Amazon SNS topic**. Pick `TravelBookingAlerts` and save. The console also adds the queue policy that lets SNS deliver.
4. Leave **raw message delivery** off on the subscription. The consumer expects the SNS envelope.

## 5. Verify the sender in SES

1. In SES, open **Identities** and choose **Create identity**. Choose **Email address** and enter the sender address.
2. Open the link that SES emails to that address.

If your account is in the SES sandbox, also verify every address that receives booking emails.

## 6. Create the Lambda functions

Create five functions with runtime **Java 21**, a 30 second timeout, and 512 MB of memory. Upload the jar from step 1 to each one, and set the handler:

| Function | Handler |
| :--- | :--- |
| `TravelPaymentFunction` | `com.travel.workers.PaymentHandler::handleRequest` |
| `TravelFlightFunction` | `com.travel.workers.FlightHandler::handleRequest` |
| `TravelHotelFunction` | `com.travel.workers.HotelHandler::handleRequest` |
| `TravelOutboxRelayFunction` | `com.travel.workers.OutboxRelayHandler::handleRequest` |
| `TravelNotificationConsumerFunction` | `com.travel.workers.NotificationConsumerHandler::handleRequest` |

Set these environment variables:

| Function | Variable | Value |
| :--- | :--- | :--- |
| `TravelOutboxRelayFunction` | `SNS_TOPIC_ARN` | The ARN of `TravelBookingAlerts`. The fallback ARN in the code does not exist. |
| `TravelNotificationConsumerFunction` | `SES_FROM_EMAIL` | The sender you verified in step 5 |

The table names default to the names in step 2. Set `PAYMENT_TABLE_NAME`, `FLIGHT_TABLE_NAME`, `HOTEL_TABLE_NAME`, or `OUTBOX_TABLE_NAME` only if you used other names. Don't set `AWS_REGION`, because Lambda sets it.

Give each function's execution role these permissions:

| Function | Permissions |
| :--- | :--- |
| `TravelPaymentFunction` | `dynamodb:PutItem` and `dynamodb:UpdateItem` on `Travel_Payments` and `TravelOutbox` |
| `TravelFlightFunction` | `dynamodb:PutItem` and `dynamodb:UpdateItem` on `Travel_Flights` and `TravelOutbox` |
| `TravelHotelFunction` | `dynamodb:PutItem` and `dynamodb:UpdateItem` on `Travel_Hotels` and `TravelOutbox` |
| `TravelOutboxRelayFunction` | The managed policy `AWSLambdaDynamoDBExecutionRole`, and `sns:Publish` on `TravelBookingAlerts` |
| `TravelNotificationConsumerFunction` | The managed policy `AWSLambdaSQSQueueExecutionRole`, and `ses:SendEmail` and `ses:SendRawEmail` |

## 7. Add the triggers

Add the permissions in step 6 first. Otherwise the console rejects the trigger.

1. On `TravelOutboxRelayFunction`, add a **DynamoDB** trigger on the `TravelOutbox` stream:
   - **Batch size:** 10.
   - **Starting position:** Latest.
   - **On-failure destination:** an SQS queue or SNS topic. Without one, Lambda drops a record after its retries run out.
2. On `TravelNotificationConsumerFunction`, add an **SQS** trigger on `TravelNotificationQueue`:
   - **Batch size:** 10.
   - **Report batch item failures:** on. With it, SQS delivers only the failed message again instead of the whole batch.

## 8. Create the state machine

1. In Step Functions, create a **Standard** state machine named `TravelBookingSaga`.
2. Copy all of `step-function.json.example` into the code editor.
3. Replace the 6 copies of `<AWS_ACCOUNT_ID>` with your account ID.
4. Give the state machine's role `lambda:InvokeFunction` on `TravelPaymentFunction`, `TravelFlightFunction`, and `TravelHotelFunction`.

Deploy the jar from step 1 before you save a new state machine definition. The definition calls the hotel and flight functions with `CANCEL`, and an older jar handles `CANCEL` differently.

## 9. Get an email when a rollback fails

A run that ends with the error `CompensationFailed` needs a person to repair the records. The Step Functions metric `ExecutionsFailed` cannot tell these runs apart from declined payments, so use an EventBridge rule:

1. Create a **Standard** SNS topic named `TravelBookingOpsAlerts`. Leave encryption off. Add an **Email** subscription to your address and confirm it from your inbox. Don't use `TravelBookingAlerts`, because the notification queue reads that topic.
2. In EventBridge, open **Event buses**, open the `default` bus, and create a rule named `TravelSagaCompensationFailed`. Don't use **Scheduled rules**, because those rules only run on a timer.
3. Use this event pattern. Replace the ARN with your state machine's ARN:
   ```json
   {
     "source": ["aws.states"],
     "detail-type": ["Step Functions Execution Status Change"],
     "detail": {
       "status": ["FAILED"],
       "error": ["CompensationFailed"],
       "stateMachineArn": ["arn:aws:states:us-east-1:<AWS_ACCOUNT_ID>:stateMachine:TravelBookingSaga"]
     }
   }
   ```
4. Set the target to the SNS topic `TravelBookingOpsAlerts`.

To test the rule, change `CompensationFailed` to `PaymentError` and start a run with an amount above 1000. An email from AWS Notifications should arrive within about a minute. Then change the pattern back.

## 10. Check the deployment

In Step Functions, open `TravelBookingSaga` and choose **Start execution**. Each input needs all five fields, or the run fails with `States.Runtime` before any step runs. Use a recipient address that SES accepts, and a new `bookingId` for each run.

| Test | Input change | Expected end | Expected emails |
| :--- | :--- | :--- | :--- |
| A. Success | `"destination": "Paris"` | `Booking Success` | 3: payment, flight, hotel |
| B. Hotel full | `"destination": "Atlantis"` | `Saga Fail`, error `SagaRollbackComplete` | 4: payment, flight, flight cancelled, refund |
| C. Flight fails | `"destination": "Mars"` | `Saga Fail`, error `SagaRollbackComplete` | 2: payment, refund |

Start from this input for test A:

```json
{
  "bookingId": "bk-manual-001",
  "userId": "user-101",
  "userEmail": "you@example.com",
  "destination": "Paris",
  "amount": 250
}
```

## Redrive the DLQ

SQS does not move messages out of `TravelNotificationDLQ` on its own. Fix the cause first, and then move the messages back by hand.

1. Look at the messages:
   ```bash
   aws sqs receive-message \
     --queue-url <TravelNotificationDLQ_URL> \
     --attribute-names All \
     --max-number-of-messages 10
   ```
2. Move every message back to the main queue:
   ```bash
   aws sqs start-message-move-task \
     --source-arn <TravelNotificationDLQ_ARN> \
     --destination-arn <TravelNotificationQueue_ARN>
   ```
3. Check the move:
   ```bash
   aws sqs list-message-move-tasks \
     --source-arn <TravelNotificationDLQ_ARN>
   ```

You can also choose **Start DLQ redrive** on the DLQ's page in the SQS console.

## The SAM template

`template.yaml` describes the same tables, topic, queues, and functions, with an `-${Environment}` suffix on each name. It has never been deployed, and these problems are known:

- `DefinitionUri` points at `step-function.json`, which is gitignored. Only the `.example` file is in the repository.
- The ARNs in the `.example` file name functions without the `-${Environment}` suffix, and they ignore the template's `DefinitionSubstitutions`.
- The template sets the `AWS_REGION` environment variable, which Lambda reserves. Deployment is expected to fail. This comes from the AWS documentation and was not tried.
- The template creates no API Gateway.
- The stream trigger on `TravelOutbox` has no `OnFailure` destination.
- `TravelNotificationDLQAlarm` has no `AlarmActions`, so it notifies no one.
