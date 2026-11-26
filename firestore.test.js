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

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

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

test("Unauthenticated user cannot read or write profile, snapshots, or activity records", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const past = new Date(Date.now() - 1000);

  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("device_snapshots").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("activity_records").get()
  );

  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      activeDeviceId: "DEV-ALICE1",
      totalSnapshots: 0,
      createdAt: past,
      updatedAt: past,
    })
  );

  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("device_snapshots")
      .doc("DEV-ALICE1")
      .set({
        userId: ALICE_UID,
        deviceId: "DEV-ALICE1",
        deviceName: "Pixel 9",
        deviceModel: "Google Pixel 9",
        appCount: 10,
        activityCount: 5,
        callLogCount: 2,
        syncStatus: "SYNCED",
        createdAt: past,
        updatedAt: past,
      })
  );
});

test("Authenticated owner: valid SyncProfile create and partial update succeed, extra fields fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice Admin",
      email: "alice@example.com",
      activeDeviceId: "DEV-ALICE1",
      totalSnapshots: 1,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Partial update preserves immutable userId & createdAt
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      displayName: "Alice Updated",
      totalSnapshots: 2,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject ghost field / @DocumentId id field
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject immutable createdAt modification
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Authenticated owner: valid CloudDeviceSnapshot create, partial update, query, and delete succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const snapshotsRef = aliceDb.collection("users").doc(ALICE_UID).collection("device_snapshots");

  // Query on empty subcollection must succeed (no existing() inspection in allow list)
  await assertSucceeds(snapshotsRef.orderBy("updatedAt", "desc").get());

  await assertSucceeds(
    snapshotsRef.doc("DEV-ALICE1").set({
      userId: ALICE_UID,
      deviceId: "DEV-ALICE1",
      deviceName: "Primary Phone",
      deviceModel: "Pixel 9",
      appCount: 42,
      activityCount: 12,
      callLogCount: 6,
      unlockCount: 14,
      summaryNote: "Encrypted snapshot synced",
      syncStatus: "SYNCED",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Partial update aligned with FirestoreSyncRepository
  await assertSucceeds(
    snapshotsRef.doc("DEV-ALICE1").update({
      deviceName: "Primary Phone Updated",
      appCount: 45,
      activityCount: 15,
      callLogCount: 8,
      unlockCount: 19,
      syncStatus: "PAUSED",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject immutable deviceId mutation
  await assertFails(
    snapshotsRef.doc("DEV-ALICE1").update({
      deviceId: "DEV-HIJACKED",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject @DocumentId "id" key in fresh document create
  await assertFails(
    snapshotsRef.doc("DEV-ALICE2").set({
      id: "DEV-ALICE2",
      userId: ALICE_UID,
      deviceId: "DEV-ALICE2",
      deviceName: "Second Phone",
      deviceModel: "Pixel 8",
      appCount: 5,
      activityCount: 1,
      callLogCount: 0,
      syncStatus: "SYNCED",
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject tampered future timestamp
  await assertFails(
    snapshotsRef.doc("DEV-ALICE3").set({
      userId: ALICE_UID,
      deviceId: "DEV-ALICE3",
      deviceName: "Future Phone",
      deviceModel: "Pixel 8",
      appCount: 5,
      activityCount: 1,
      callLogCount: 0,
      syncStatus: "SYNCED",
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );

  // Reject invalid syncStatus enum
  await assertFails(
    snapshotsRef.doc("DEV-ALICE4").set({
      userId: ALICE_UID,
      deviceId: "DEV-ALICE4",
      deviceName: "Bad Enum Phone",
      deviceModel: "Pixel 8",
      appCount: 5,
      activityCount: 1,
      callLogCount: 0,
      syncStatus: "synced",
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(snapshotsRef.orderBy("updatedAt", "desc").get());
  await assertSucceeds(snapshotsRef.doc("DEV-ALICE1").delete());
});

test("Authenticated owner: valid CloudActivityRecord create, query, and bounds enforcement", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const recordsRef = aliceDb.collection("users").doc(ALICE_UID).collection("activity_records");

  await assertSucceeds(
    recordsRef.doc("REC_001").set({
      userId: ALICE_UID,
      deviceId: "DEV-ALICE1",
      recordCategory: "APP_EVENT",
      title: "WhatsApp",
      subtitle: "com.whatsapp",
      detail: "INSTALLED • Google Play Store",
      eventDate: "2026-11-25",
      eventTime: "14:20:00",
      recordedAtMillis: 1764080400000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // UNLOCK_LOG record aligned with UnlockTracker + FirestoreSyncRepository
  await assertSucceeds(
    recordsRef.doc("UL_001").set({
      userId: ALICE_UID,
      deviceId: "DEV-ALICE1",
      recordCategory: "UNLOCK_LOG",
      title: "Secured (Fingerprint or PIN/Pattern)",
      subtitle: "BIOMETRIC_ENROLLED • Bat: 84%",
      detail: "Source: LIVE_RECEIVER",
      eventDate: "2026-11-26",
      eventTime: "09:15:00",
      recordedAtMillis: 1764148500000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Query aligned with FirestoreSyncRepository
  await assertSucceeds(
    recordsRef.where("deviceId", "==", "DEV-ALICE1").orderBy("recordedAtMillis", "desc").limit(50).get()
  );

  // Reject oversized title (> 120 chars)
  await assertFails(
    recordsRef.doc("REC_LONG").set({
      userId: ALICE_UID,
      deviceId: "DEV-ALICE1",
      recordCategory: "APP_EVENT",
      title: "A".repeat(121),
      subtitle: "com.example.long",
      eventDate: "2026-11-25",
      eventTime: "14:20:00",
      recordedAtMillis: 1764080400000,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject invalid document ID with illegal characters
  await assertFails(
    recordsRef.doc("REC$INVALID!").set({
      userId: ALICE_UID,
      deviceId: "DEV-ALICE1",
      recordCategory: "APP_EVENT",
      title: "Bad ID",
      subtitle: "com.example.badid",
      eventDate: "2026-11-25",
      eventTime: "14:20:00",
      recordedAtMillis: 1764080400000,
      createdAt: created,
      updatedAt: created,
    })
  );
});

test("Cross-user isolation: Alice cannot read, list, create, update, or delete Bob's data", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    const past = new Date(Date.now() - 10000);
    await db.collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      displayName: "Bob",
      email: "bob@example.com",
      activeDeviceId: "DEV-BOB1",
      totalSnapshots: 1,
      createdAt: past,
      updatedAt: past,
    });
    await db
      .collection("users")
      .doc(BOB_UID)
      .collection("device_snapshots")
      .doc("DEV-BOB1")
      .set({
        userId: BOB_UID,
        deviceId: "DEV-BOB1",
        deviceName: "Bob Phone",
        deviceModel: "Pixel 9 Pro",
        appCount: 20,
        activityCount: 4,
        callLogCount: 3,
        syncStatus: "SYNCED",
        createdAt: past,
        updatedAt: past,
      });
    await db
      .collection("users")
      .doc(BOB_UID)
      .collection("activity_records")
      .doc("BOB_REC_1")
      .set({
        userId: BOB_UID,
        deviceId: "DEV-BOB1",
        recordCategory: "CALL_LOG",
        title: "Protected Contact",
        subtitle: "INCOMING",
        detail: "Duration: 45s",
        eventDate: "2026-11-25",
        eventTime: "15:00:00",
        recordedAtMillis: 1764082800000,
        createdAt: past,
        updatedAt: past,
      });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const past = new Date(Date.now() - 1000);

  await assertFails(aliceDb.collection("users").doc(BOB_UID).get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("device_snapshots").get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("device_snapshots").doc("DEV-BOB1").get());
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("device_snapshots").doc("DEV-BOB1").update({
      deviceName: "Hijacked",
      updatedAt: past,
    })
  );
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("device_snapshots").doc("DEV-BOB1").delete()
  );
  await assertFails(aliceDb.collection("users").doc(BOB_UID).collection("activity_records").get());
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("activity_records").doc("ALICE_INJECT").set({
      userId: ALICE_UID,
      deviceId: "DEV-ALICE1",
      recordCategory: "APP_EVENT",
      title: "Injected",
      subtitle: "com.evil",
      eventDate: "2026-11-25",
      eventTime: "15:05:00",
      recordedAtMillis: 1764083100000,
      createdAt: past,
      updatedAt: past,
    })
  );
});
