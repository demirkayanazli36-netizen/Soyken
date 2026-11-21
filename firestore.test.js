const {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} = require('@firebase/rules-unit-testing');
const { readFileSync } = require('node:fs');
const { test, before, after, beforeEach } = require('node:test');

const PROJECT_ID = 'gen-lang-client-0649889808';
const DATABASE_ID = 'ai-studio-android-soykenpl-53c6cd02-d945-4e02-aa9f-5926a37a6c5f';

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules: readFileSync('firestore.rules', 'utf8'),
      host: '127.0.0.1',
      port: 8085,
    },
  });
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

after(async () => {
  await testEnv.cleanup();
});

function dbFor(context) {
  return context.firestore(DATABASE_ID);
}

test('unauthenticated users cannot read or write premium entitlements', async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = dbFor(ctx);
    await adminDb.doc('users/alice/entitlements/premium').set({
      active: true,
      subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
      productId: 'soyken_premium',
      basePlanId: 'yearly',
      expiryTimeMillis: Date.now() + 86400000,
      autoRenewing: true,
    });
  });

  const anonDb = dbFor(testEnv.unauthenticatedContext());
  await assertFails(anonDb.doc('users/alice/entitlements/premium').get());
  await assertFails(
    anonDb.doc('users/alice/entitlements/premium').set({
      active: true,
      subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
      productId: 'soyken_premium',
      basePlanId: 'monthly',
      expiryTimeMillis: Date.now() + 86400000,
      autoRenewing: true,
    }),
  );
});

test('authenticated owner can read own server-verified premium entitlement', async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = dbFor(ctx);
    await adminDb.doc('users/alice/entitlements/premium').set({
      active: true,
      subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
      productId: 'soyken_premium',
      basePlanId: 'monthly',
      expiryTimeMillis: Date.now() + 86400000,
      autoRenewing: true,
    });
  });

  const aliceDb = dbFor(testEnv.authenticatedContext('alice'));
  await assertSucceeds(aliceDb.doc('users/alice/entitlements/premium').get());
  await assertSucceeds(aliceDb.collection('users/alice/entitlements').get());
});

test('authenticated user cannot read another user entitlement or arbitrary entitlement doc', async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = dbFor(ctx);
    await adminDb.doc('users/bob/entitlements/premium').set({
      active: true,
      subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
      productId: 'soyken_premium',
      basePlanId: 'yearly',
      expiryTimeMillis: Date.now() + 86400000,
      autoRenewing: true,
    });
  });

  const aliceDb = dbFor(testEnv.authenticatedContext('alice'));
  await assertFails(aliceDb.doc('users/bob/entitlements/premium').get());
  await assertFails(aliceDb.collection('users/bob/entitlements').get());
  await assertFails(aliceDb.doc('users/alice/entitlements/other').get());
});

test('authenticated user cannot spoof, create, update, or delete own premium entitlement', async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = dbFor(ctx);
    await adminDb.doc('users/alice/entitlements/premium').set({
      active: false,
      subscriptionState: 'INACTIVE',
      productId: 'soyken_premium',
      basePlanId: '',
      expiryTimeMillis: 0,
      autoRenewing: false,
    });
  });

  const aliceDb = dbFor(testEnv.authenticatedContext('alice'));
  await assertFails(
    aliceDb.doc('users/alice/entitlements/premium').update({ active: true }),
  );
  await assertFails(
    aliceDb.doc('users/alice/entitlements/premium').set({
      active: true,
      subscriptionState: 'SUBSCRIPTION_STATE_ACTIVE',
      productId: 'soyken_premium',
      basePlanId: 'yearly',
      expiryTimeMillis: Date.now() + 999999999,
      autoRenewing: true,
    }),
  );
  await assertFails(aliceDb.doc('users/alice/entitlements/premium').delete());
});

test('play_purchase_tokens collection is strictly inaccessible to all clients', async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const adminDb = dbFor(ctx);
    await adminDb.doc('play_purchase_tokens/hash123').set({
      uid: 'alice',
      productId: 'soyken_premium',
      basePlanId: 'monthly',
    });
  });

  const anonDb = dbFor(testEnv.unauthenticatedContext());
  const aliceDb = dbFor(testEnv.authenticatedContext('alice'));

  await assertFails(anonDb.doc('play_purchase_tokens/hash123').get());
  await assertFails(aliceDb.doc('play_purchase_tokens/hash123').get());
  await assertFails(
    aliceDb.doc('play_purchase_tokens/hash123').set({
      uid: 'alice',
      productId: 'soyken_premium',
      basePlanId: 'yearly',
    }),
  );
});
