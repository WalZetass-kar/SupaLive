const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validProfilePayload(uid) {
  const now = new Date(Date.now() - 1000);
  return {
    userId: uid,
    email: "alice@example.com",
    fullName: "Alice Dev",
    emailAlertsEnabled: false,
    inAppAlertsEnabled: true,
    failureThreshold: 2,
    createdAt: now,
    updatedAt: now,
  };
}

function validProjectPayload(uid) {
  const now = new Date(Date.now() - 1000);
  return {
    userId: uid,
    projectName: "Prod Analytics",
    projectUrl: "https://xyzcompany.supabase.co",
    projectRef: "xyzcompany",
    maskedKey: "sb_publishable_••••9999",
    publishableKeyEncrypted: "v1:nonce123456:ciphertext123456789",
    keyVersion: 1,
    healthStatus: "healthy",
    monitoringEnabled: true,
    intervalHours: 12,
    phaseOffsetMinutes: 145,
    nextCheckEpochMs: Date.now() + 43200000,
    lastCheckedEpochMs: Date.now() - 5000,
    lastSuccessEpochMs: Date.now() - 5000,
    lastLatencyMs: 184,
    consecutiveFailures: 0,
    lastSanitizedError: "",
    createdAt: now,
    updatedAt: now,
  };
}

function validLogPayload(uid, projectId) {
  const now = new Date(Date.now() - 1000);
  return {
    userId: uid,
    projectId,
    projectName: "Prod Analytics",
    checkType: "scheduled",
    result: "success",
    resultingStatus: "healthy",
    httpStatus: 200,
    responseTimeMs: 184,
    errorCode: "",
    errorMessage: "",
    retryCount: 0,
    checkedAtEpochMs: Date.now() - 5000,
    createdAt: now,
  };
}

function validNotificationPayload(uid, projectId) {
  const now = new Date(Date.now() - 1000);
  return {
    userId: uid,
    projectId,
    projectName: "Prod Analytics",
    notificationType: "health_alert",
    title: "Health check failed",
    message: "Prod Analytics returned HTTP 503 during scheduled check.",
    readFlag: false,
    createdAtEpochMs: Date.now() - 1000,
    createdAt: now,
  };
}

function validAuditPayload(uid, projectId) {
  const now = new Date(Date.now() - 1000);
  return {
    userId: uid,
    action: "project_created",
    resourceType: "project",
    resourceId: projectId,
    summary: "Registered Prod Analytics (xyzcompany)",
    createdAtEpochMs: Date.now() - 1000,
    createdAt: now,
  };
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user: cannot read or write any user collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("projects").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("proj_1")
      .set(validProjectPayload(ALICE_UID))
  );
});

test("Owner: profile create and partial preferences update succeed; immutable fields guarded", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const profileRef = aliceDb.collection("users").doc(ALICE_UID);

  await assertSucceeds(profileRef.set(validProfilePayload(ALICE_UID)));

  await assertSucceeds(
    profileRef.update({
      inAppAlertsEnabled: false,
      failureThreshold: 3,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject modifying createdAt or injecting ghost fields
  await assertFails(
    profileRef.update({
      createdAt: new Date(Date.now() - 200),
      updatedAt: new Date(Date.now() - 200),
    })
  );
  await assertFails(
    profileRef.update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 200),
    })
  );
});

test("Owner: project create, monitoring toggle update, health check update, and list succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const projectsCol = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("projects");
  const projRef = projectsCol.doc("proj_1");

  await assertSucceeds(projRef.set(validProjectPayload(ALICE_UID)));
  await assertSucceeds(projectsCol.get());

  // Partial update: record check outcome
  await assertSucceeds(
    projRef.update({
      healthStatus: "warning",
      consecutiveFailures: 1,
      lastCheckedEpochMs: Date.now(),
      lastLatencyMs: 920,
      lastSanitizedError: "HTTP 503 Service Unavailable",
      nextCheckEpochMs: Date.now() + 43200000,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Partial update: toggle monitoring off
  await assertSucceeds(
    projRef.update({
      monitoringEnabled: false,
      healthStatus: "monitoring_disabled",
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Reject @DocumentId `id` field inside body
  await assertFails(
    projectsCol.doc("proj_2").set({
      ...validProjectPayload(ALICE_UID),
      id: "proj_2",
    })
  );

  // Reject non-Supabase HTTP URL (SSRF rule guard)
  await assertFails(
    projectsCol.doc("proj_3").set({
      ...validProjectPayload(ALICE_UID),
      projectUrl: "http://127.0.0.1:8080",
    })
  );

  // Reject invalid healthStatus enum
  await assertFails(
    projRef.update({
      healthStatus: "hacked_status",
      updatedAt: new Date(Date.now() - 100),
    })
  );

  // Reject future client timestamp
  await assertFails(
    projectsCol.doc("proj_4").set({
      ...validProjectPayload(ALICE_UID),
      createdAt: new Date(Date.now() + 60000),
    })
  );
});

test("Owner: logs, notifications, and audit logs follow strict immutability and update rules", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const userDoc = aliceDb.collection("users").doc(ALICE_UID);

  const logRef = userDoc.collection("logs").doc("log_1");
  await assertSucceeds(logRef.set(validLogPayload(ALICE_UID, "proj_1")));
  await assertSucceeds(userDoc.collection("logs").get());
  // Logs are immutable (cannot update)
  await assertFails(logRef.update({ httpStatus: 500 }));

  const notifRef = userDoc.collection("notifications").doc("notif_1");
  await assertSucceeds(
    notifRef.set(validNotificationPayload(ALICE_UID, "proj_1"))
  );
  // Only readFlag can be updated
  await assertSucceeds(notifRef.update({ readFlag: true }));
  await assertFails(notifRef.update({ title: "Tampered" }));

  const auditRef = userDoc.collection("audit_logs").doc("audit_1");
  await assertSucceeds(auditRef.set(validAuditPayload(ALICE_UID, "proj_1")));
  await assertFails(auditRef.update({ action: "tampered" }));
});

test("Cross-user isolation: Bob cannot read, list, create, update, or delete Alice's data", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db.collection("users").doc(ALICE_UID).set(validProfilePayload(ALICE_UID));
    await db
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("proj_alice")
      .set(validProjectPayload(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("projects").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("proj_alice")
      .get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("proj_alice")
      .update({ projectName: "Hijacked" })
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("projects")
      .doc("proj_alice")
      .delete()
  );
});
