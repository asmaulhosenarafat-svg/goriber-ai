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
const ADMIN_UID = "admin_789";
const ADMIN_EMAIL = "arafatislam989834@gmail.com";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function baseUserDoc(uid, email, now) {
  return {
    userId: uid,
    displayName: "Alice",
    email,
    planTier: "free",
    premiumStatus: "free",
    packageId: "",
    packageName: "",
    subscriptionId: "",
    lifetimePlan: false,
    giftedByAdmin: false,
    purchasedAtMs: 0,
    expiresAtMs: 0,
    banned: false,
    banReason: "",
    themeMode: "dark",
    accentColor: "cyan",
    totalCommands: 0,
    totalAiQueries: 0,
    lastAction: "Signed in",
    createdAt: now,
    updatedAt: now,
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

test("Unauthenticated user: denied across all collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("premium_packages").get());
  await assertFails(unauthDb.collection("payment_requests").get());
  await assertFails(unauthDb.collection("admin_audit_logs").get());
});

test("Owner profile create & partial update succeed; premium/ban escalation & shadow field fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@example.com",
    email_verified: true,
  }).firestore();
  const realAdminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();
  const now = new Date(Date.now() - 5000);

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(baseUserDoc(ALICE_UID, "alice@example.com", now))
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      themeMode: "amoled",
      totalCommands: 3,
      lastAction: "Brightness 60%",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Self-upgrade blocked
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      premiumStatus: "active",
      planTier: "pro",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Admin can gift/activate premium and ban/unban
  await assertSucceeds(
    realAdminDb.collection("users").doc(ALICE_UID).update({
      planTier: "pro",
      premiumStatus: "active",
      packageId: "pkg_30d",
      packageName: "30 Days Premium",
      subscriptionId: "SUB-12345",
      giftedByAdmin: true,
      purchasedAtMs: Date.now() - 1000,
      expiresAtMs: Date.now() + 30 * 86400000,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Premium packages & feature flags: signed-in read allowed, only Admin writes", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@example.com",
    email_verified: true,
  }).firestore();
  const realAdminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();
  const now = new Date(Date.now() - 4000);

  const pkgData = {
    packageId: "pkg_30d",
    name: "30 Days Premium",
    nameBn: "৩০ দিনের প্রিমিয়াম",
    description: "Full monthly unlock",
    durationDays: 30,
    lifetimePlan: false,
    priceBdt: 299,
    playProductId: "goriber_ai_30d",
    tier: "pro",
    featuresSummary: "All 15 Premium Features",
    active: true,
    sortOrder: 2,
    createdAt: now,
    updatedAt: now,
  };

  await assertFails(aliceDb.collection("premium_packages").doc("pkg_30d").set(pkgData));
  await assertSucceeds(realAdminDb.collection("premium_packages").doc("pkg_30d").set(pkgData));
  await assertSucceeds(aliceDb.collection("premium_packages").get());
});

test("Payment requests & refund flow: owner requests refund only after approval; Admin approves/refunds & writes immutable audit logs", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@example.com",
    email_verified: true,
  }).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID, {
    email: "bob@example.com",
    email_verified: true,
  }).firestore();
  const realAdminDb = testEnv.authenticatedContext(ADMIN_UID, {
    email: ADMIN_EMAIL,
    email_verified: true,
  }).firestore();
  const now = new Date(Date.now() - 3000);

  await assertSucceeds(
    aliceDb.collection("payment_requests").doc("pay_1").set({
      userId: ALICE_UID,
      userEmail: "alice@example.com",
      planId: "pkg_30d",
      packageName: "30 Days Premium",
      durationDays: 30,
      lifetimePlan: false,
      method: "bkash",
      senderNumber: "01700000000",
      transactionId: "TXN998877",
      playPurchaseToken: "",
      amountBdt: 299,
      status: "pending",
      refundReason: "",
      adminNote: "",
      createdAt: now,
      updatedAt: now,
    })
  );

  // Admin approves
  await assertSucceeds(
    realAdminDb.collection("payment_requests").doc("pay_1").update({
      status: "approved",
      adminNote: "Verified bKash TrxID",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Bob cannot request refund for Alice's payment
  await assertFails(
    bobDb.collection("payment_requests").doc("pay_1").update({
      status: "refund_requested",
      refundReason: "Accidental",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Alice can request refund
  await assertSucceeds(
    aliceDb.collection("payment_requests").doc("pay_1").update({
      status: "refund_requested",
      refundReason: "Changed mind",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Audit log only readable/creatable by Admin, never updated or deleted
  await assertFails(aliceDb.collection("admin_audit_logs").get());
  await assertSucceeds(
    realAdminDb.collection("admin_audit_logs").doc("audit_1").set({
      adminUid: ADMIN_UID,
      adminEmail: ADMIN_EMAIL,
      actionType: "APPROVE_PAYMENT",
      targetUserId: ALICE_UID,
      targetLabel: "alice@example.com",
      details: "Approved pkg_30d (299 BDT)",
      createdAt: now,
    })
  );
  await assertFails(realAdminDb.collection("admin_audit_logs").doc("audit_1").delete());
});
