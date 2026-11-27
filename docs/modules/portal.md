# Portal module

The corporate insurance portal: the part of the platform people log into. Four roles, each with its own area of
the React app in [`frontend/`](../../frontend).

| Role | Can do |
| --- | --- |
| **Employee** | See available policies, enrol (with dependents), submit and track claims with documents, ask the chatbot, raise queries to agents, track reimbursements and renewals, read notifications. |
| **HR** | Review, approve or reject claims; review flagged (suspected-fraud) claims; approve enrolments; start reimbursements; manage renewals; see reports. |
| **Agent** | Work a queue of employee queries, set availability, see assisted claims. |
| **Admin** | Manage employees, agents and HR users; create and edit policies; see every claim, enrolment, reimbursement and renewal; read the audit log. |

## What is in it

| Area | Notes |
| --- | --- |
| Authentication | Four login endpoints, one per role, each issuing the platform JWT. Passwords are hashed with BCrypt (Spring's default strength, 10; the original README said 12, the code does not). Password reset by e-mail token. |
| Claims | Submission with documents and a status lifecycle. At submission a rule-based check (`FraudService`) flags duplicate claims, frequent claims, invalid dates, reused documents, amount spikes, repeated procedures and high-risk combinations, and `ClaimService` assigns the claim to the active HR user with the fewest pending claims. Reimbursement follows approval. |
| Policies | CRUD by admin; document upload to Supabase S3 when configured (`SupabaseStorageService`, created lazily, so the portal runs without it). |
| Chatbot | Built-in answers about the employee's own claims, policies and queries, and Cohere for open-ended questions (`COHERE_API_KEY`). A missing key logs a warning at startup. |
| Notifications | In-app notifications plus e-mail. |
| Renewals | Scheduled jobs at 02:00 and 03:00 (`RenewalService`) plus manual execution by admin. |
| Audit | Key actions across login, claims, queries, enrolments, reimbursements, renewals and admin operations are written to `portal_audit_logs`. |

Structure is the classic layering: `controller` → `service` → `repository` → `model` (JPA), with `config` for JWT,
CORS and password encoding. Tables are prefixed `portal_` and created by Hibernate; see
[MERGE-NOTES](../MERGE-NOTES.md#database).

## Access

Authorisation is enforced twice: by the platform's URL rules (listed below, evaluated top to bottom, deny by
default) and, for many endpoints, by an in-method role check with `JwtUtil` that predates the merge and is kept as
defence in depth ("extra check" column). The column "Access" is the URL rule; it is generated from
`PlatformSecurityConfig` and the controllers, so regenerate it rather than editing it by hand.

## Endpoint reference (90 endpoints)


### AdminController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| POST | `/admin/login` | Public |  |
| POST | `/admin/agent/register` | Admin | yes |
| POST | `/admin/hr/register` | Admin | yes |
| PUT | `/admin/employee/{id}/status` | Admin | yes |
| PUT | `/admin/employee/{id}` | Admin | yes |
| DELETE | `/admin/employee/{id}` | Admin | yes |
| PUT | `/admin/hr/{id}/status` | Admin | yes |
| PUT | `/admin/hr/{id}` | Admin | yes |
| DELETE | `/admin/hr/{id}` | Admin | yes |
| PUT | `/admin/agent/{id}/status` | Admin | yes |
| PUT | `/admin/agent/{id}` | Admin | yes |
| DELETE | `/admin/agent/{id}` | Admin | yes |
| GET | `/admin/claims` | Admin | yes |
| GET | `/admin/claims/fraud` | Admin | yes |
| GET | `/admin/audit/logs` | Admin | yes |

### AdminEnrollmentController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| GET | `/admin/enrollments/all` | Admin |  |
| GET | `/admin/enrollments/stats` | Admin | yes |

### AgentController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| GET | `/agent` | Any signed-in user |  |
| POST | `/agent/register` | Admin |  |
| POST | `/agent/login` | Public |  |
| POST | `/agent/availability` | Agent |  |
| GET | `/agent/{id}/availability` | Agent |  |
| GET | `/agent/availability/all` | Any signed-in user |  |
| GET | `/agent/available` | Any signed-in user |  |
| GET | `/agent/queries/pending/{agentId}` | Agent |  |
| PUT | `/agent/queries/respond/{queryId}` | Agent | yes |
| GET | `/agent/queries/all/{agentId}` | Agent |  |

### AuthController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| POST | `/auth/register` | Public |  |
| POST | `/auth/login` | Public |  |
| GET | `/auth/employees` | Any signed-in user |  |
| POST | `/auth/forgot-password` | Public |  |
| POST | `/auth/reset-password/{token}` | Public |  |

### ChatbotController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| POST | `/employee/chatbot` | Employee | yes |

### ClaimController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| POST | `/employee/claims` | Employee | yes |
| POST | `/employee/claims/submit` | Employee | yes |
| POST | `/employee/claims/update` | Employee | yes |
| GET | `/employee/claims` | Employee | yes |
| GET | `/employee/claims/all` | Employee |  |

### EmployeeController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| GET | `/employee/policies` | Any signed-in user |  |

### EmployeeEnrollmentController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| POST | `/employee/enrollments/request` | Employee | yes |
| GET | `/employee/enrollments/my` | Employee | yes |
| POST | `/employee/enrollments/{enrollmentId}/dependents` | Employee | yes |
| GET | `/employee/enrollments/{enrollmentId}/dependents` | Employee | yes |

### EmployeeQueryController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| POST | `/employee/queries` | Employee | yes |
| GET | `/employee/queries` | Employee | yes |

### EmployeeRenewalController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| GET | `/employee/renewals/my-policies` | Employee | yes |

### FileController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| GET | `/api/files/download/{filename:.+}` | Public |  |
| GET | `/api/files/view/{filename:.+}` | Public |  |

### HrController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| POST | `/hr/login` | Public |  |
| GET | `/hr` | Any signed-in user |  |
| GET | `/hr/claims` | HR |  |
| POST | `/hr/claims/approve/{claimId}` | HR |  |
| POST | `/hr/claims/reject/{claimId}` | HR |  |
| GET | `/hr/claims/fraud` | HR | yes |

### HrEnrollmentController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| GET | `/hr/enrollments/all` | HR |  |
| GET | `/hr/enrollments/pending` | HR |  |
| POST | `/hr/enrollments/{enrollmentId}/approve` | HR |  |
| POST | `/hr/enrollments/{enrollmentId}/reject` | HR |  |
| GET | `/hr/enrollments/{enrollmentId}` | HR | yes |

### HrRenewalController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| GET | `/hr/renewals/upcoming` | HR |  |
| GET | `/hr/renewals/my-employees` | HR | yes |

### NotificationController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| GET | `/notifications/user/{userId}` | Any signed-in user |  |
| GET | `/notifications/user/{userId}/unread` | Any signed-in user |  |
| GET | `/notifications/role/{role}` | Admin or HR |  |
| GET | `/notifications/role/{role}/type` | Admin or HR |  |
| PUT | `/notifications/{notificationId}/read` | Any signed-in user |  |
| DELETE | `/notifications/{notificationId}` | Admin or HR |  |

### PolicyController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| POST | `/admin/policies` | Admin | yes |
| GET | `/admin/policies` | Admin | yes |
| GET | `/admin/policies/active` | Admin | yes |
| GET | `/admin/policies/{id}` | Admin | yes |
| DELETE | `/admin/policies/{id}` | Admin | yes |

### ReimbursementController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| POST | `/hr/claims/{claimId}/initiate-reimbursement` | HR |  |
| GET | `/hr/reimbursements/pending` | HR |  |
| GET | `/hr/reimbursements/history` | HR |  |
| GET | `/admin/reimbursements/pending` | Admin |  |
| GET | `/admin/reimbursements/all` | Admin |  |
| POST | `/admin/reimbursements/{id}/process` | Admin |  |
| POST | `/admin/reimbursements/{id}/complete` | Admin |  |
| GET | `/admin/reimbursements/stats` | Admin |  |
| GET | `/employee/reimbursements/my` | Employee | yes |
| GET | `/employee/reimbursements/{id}` | Employee | yes |

### RenewalController

| Method | Path | Access | Extra check |
| --- | --- | --- | --- |
| GET | `/admin/renewals/config` | Admin |  |
| PUT | `/admin/renewals/config` | Admin |  |
| GET | `/admin/renewals/upcoming` | Admin |  |
| GET | `/admin/renewals/stats` | Admin |  |
| POST | `/admin/renewals/check` | Admin |  |
| POST | `/admin/renewals/execute-job` | Admin |  |
| POST | `/admin/renewals/policies/{policyId}/renew` | Admin |  |
| GET | `/admin/renewals/policies/{policyId}/history` | Admin | yes |

## Known limitations

- `GET /notifications/user/{id}` requires a signed-in user but does not check that `{id}` is the caller.
- Claim documents under `/api/files/**` and `/uploads/**` are public, because the UI opens them with plain links.
- Three screens call endpoints that do not exist (`GET /employee/enrollments/{id}`, `GET /employees`,
  `POST /hr/reimbursements/initiate`); they failed before the merge and still do.
- If the fraud check throws, the claim is saved as *not* flagged (it fails open) and the error goes to stderr.
- `ClaimAutomationService` is an empty class left over from the original; the automation lives in `ClaimService`.
- Several services use field injection with circular references (`spring.main.allow-circular-references=true`).
- Entities are serialised directly to JSON, which is why `spring.jpa.open-in-view` stays on.
