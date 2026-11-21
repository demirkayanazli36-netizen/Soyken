'use strict';

const crypto = require('node:crypto');
const admin = require('firebase-admin');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { onRequest } = require('firebase-functions/v2/https');
const { onMessagePublished } = require('firebase-functions/v2/pubsub');
const { defineSecret } = require('firebase-functions/params');
const logger = require('firebase-functions/logger');
const { google } = require('googleapis');

admin.initializeApp();

const PACKAGE_NAME = 'com.aistudio.soykenplay.txvqen';
const SUBSCRIPTION_PRODUCT_ID = 'soyken_premium';
const ALLOWED_BASE_PLANS = new Set(['monthly', 'yearly']);
const FIRESTORE_DATABASE_ID =
  process.env.FIRESTORE_DATABASE_ID ||
  'ai-studio-android-soykenpl-53c6cd02-d945-4e02-aa9f-5926a37a6c5f';
const RTDN_TOPIC_NAME = process.env.PLAY_RTDN_TOPIC || 'play-rtdn-soyken-play';

// Stored exclusively in Google Cloud Secret Manager (NEVER embedded in Android APK).
// Set via: firebase functions:secrets:set PLAY_SERVICE_ACCOUNT_JSON
const playServiceAccountJson = defineSecret('PLAY_SERVICE_ACCOUNT_JSON');

const db = getFirestore(admin.app(), FIRESTORE_DATABASE_ID);

function hashPurchaseToken(purchaseToken) {
  return crypto.createHash('sha256').update(purchaseToken).digest('hex');
}

function createAndroidPublisherClient(secretRawValue) {
  if (!secretRawValue || !secretRawValue.trim()) {
    throw new Error('PLAY_SERVICE_ACCOUNT_JSON secret is not configured.');
  }
  const credentials = JSON.parse(secretRawValue);
  const auth = new google.auth.GoogleAuth({
    credentials,
    scopes: ['https://www.googleapis.com/auth/androidpublisher'],
  });
  return google.androidpublisher({ version: 'v3', auth });
}

function parseSubscriptionV2Response(subscriptionV2) {
  const subscriptionState =
    subscriptionV2.subscriptionState || 'SUBSCRIPTION_STATE_UNSPECIFIED';
  const lineItems = Array.isArray(subscriptionV2.lineItems)
    ? subscriptionV2.lineItems
    : [];

  const matchedItem =
    lineItems.find((item) => item.productId === SUBSCRIPTION_PRODUCT_ID) ||
    lineItems[0] ||
    null;

  const productId = matchedItem?.productId || SUBSCRIPTION_PRODUCT_ID;
  const basePlanId = matchedItem?.offerDetails?.basePlanId || '';
  const expiryTimeIso = matchedItem?.expiryTime || null;
  const expiryTimeMillis = expiryTimeIso ? Date.parse(expiryTimeIso) || 0 : 0;
  const autoRenewing = Boolean(matchedItem?.autoRenewingPlan?.autoRenewEnabled);
  const acknowledgementState =
    subscriptionV2.acknowledgementState || 'ACKNOWLEDGEMENT_STATE_UNSPECIFIED';
  const orderId = subscriptionV2.latestOrderId || '';
  const obfuscatedExternalAccountId =
    subscriptionV2.externalAccountIdentifiers?.obfuscatedExternalAccountId || '';

  const nowMs = Date.now();
  const isUnexpired = expiryTimeMillis > nowMs;

  // Subscriptions V2 active states:
  // - SUBSCRIPTION_STATE_ACTIVE: paid & active
  // - SUBSCRIPTION_STATE_IN_GRACE_PERIOD: payment retry in progress; user retains access
  // - SUBSCRIPTION_STATE_CANCELED: auto-renew turned off, user retains access until expiryTime
  const isEntitled =
    productId === SUBSCRIPTION_PRODUCT_ID &&
    (subscriptionState === 'SUBSCRIPTION_STATE_ACTIVE' ||
      subscriptionState === 'SUBSCRIPTION_STATE_IN_GRACE_PERIOD' ||
      (subscriptionState === 'SUBSCRIPTION_STATE_CANCELED' && isUnexpired));

  return {
    active: isEntitled,
    subscriptionState,
    productId,
    basePlanId: ALLOWED_BASE_PLANS.has(basePlanId) ? basePlanId : basePlanId,
    expiryTimeMillis,
    autoRenewing,
    acknowledgementState,
    orderId,
    obfuscatedExternalAccountId,
  };
}

async function verifyAndPersistSubscription({
  uid,
  purchaseToken,
  publisher,
  source,
}) {
  const response = await publisher.purchases.subscriptionsv2.get({
    packageName: PACKAGE_NAME,
    token: purchaseToken,
  });

  const parsed = parseSubscriptionV2Response(response.data || {});

  // Acknowledge server-side if active/grace/canceled and not yet acknowledged.
  if (
    parsed.active &&
    parsed.acknowledgementState !== 'ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED'
  ) {
    try {
      await publisher.purchases.subscriptions.acknowledge({
        packageName: PACKAGE_NAME,
        subscriptionId: parsed.productId,
        token: purchaseToken,
        requestBody: {
          developerPayload: `soyken_play:${uid}:${source}`,
        },
      });
      logger.info('Acknowledged subscription server-side', {
        uid,
        productId: parsed.productId,
        basePlanId: parsed.basePlanId,
      });
    } catch (ackErr) {
      logger.error('Failed to acknowledge subscription', {
        uid,
        message: ackErr?.message || String(ackErr),
      });
      throw ackErr;
    }
  }

  const tokenHash = hashPurchaseToken(purchaseToken);
  const entitlementRef = db
    .collection('users')
    .doc(uid)
    .collection('entitlements')
    .doc('premium');
  const tokenMapRef = db.collection('play_purchase_tokens').doc(tokenHash);

  const entitlementPayload = {
    active: parsed.active,
    subscriptionState: parsed.subscriptionState,
    productId: parsed.productId,
    basePlanId: parsed.basePlanId,
    expiryTimeMillis: parsed.expiryTimeMillis,
    autoRenewing: parsed.autoRenewing,
    orderId: parsed.orderId,
    verifiedAt: FieldValue.serverTimestamp(),
    updatedAt: FieldValue.serverTimestamp(),
  };

  const batch = db.batch();
  batch.set(entitlementRef, entitlementPayload, { merge: true });
  batch.set(
    tokenMapRef,
    {
      uid,
      productId: parsed.productId,
      basePlanId: parsed.basePlanId,
      updatedAt: FieldValue.serverTimestamp(),
    },
    { merge: true },
  );
  await batch.commit();

  return parsed;
}

/**
 * HTTPS endpoint invoked by the Soyken Play Android app after a Google Play Billing purchase
 * or when restoring/re-verifying an existing subscription.
 *
 * Security:
 * - Layer 1 (App Check): Independently verifies `X-Firebase-AppCheck` via Admin App Check SDK.
 *   Rejects any invalid/tampered token with 401 (`INVALID_APP_CHECK_TOKEN`).
 *   Missing tokens are controlled by `ENFORCE_APP_CHECK === 'true'` so older app versions
 *   continue verifying seamlessly during phased rollout, then strict enforcement can be enabled
 *   without code changes.
 * - Layer 2 (Auth): Always requires a valid Firebase Auth ID token in `Authorization: Bearer <idToken>`.
 * - Layer 3 (Play Billing): Verifies purchaseToken directly against Google Play Developer API Subscriptions V2.
 * - Rejects token hijacking if a token is already bound to a different Firebase UID.
 * - Writes the verified entitlement document via Admin SDK (client writes are blocked in rules).
 */
exports.verifyPlaySubscription = onRequest(
  {
    region: 'us-central1',
    secrets: [playServiceAccountJson],
    timeoutSeconds: 30,
    memory: '256MiB',
  },
  async (req, res) => {
    if (req.method !== 'POST') {
      res.status(405).json({ error: 'METHOD_NOT_ALLOWED' });
      return;
    }

    try {
      // Layer 1: Independent Firebase App Check verification
      const appCheckToken = (req.get('X-Firebase-AppCheck') || '').trim();
      const enforceAppCheck = process.env.ENFORCE_APP_CHECK === 'true';
      let appCheckAppId = null;

      if (appCheckToken) {
        try {
          const appCheckClaims = await admin.appCheck().verifyToken(appCheckToken);
          appCheckAppId = appCheckClaims?.appId || null;
        } catch (appCheckErr) {
          logger.warn('Rejected invalid Firebase App Check token', {
            code: appCheckErr?.code || 'unknown',
            message: appCheckErr?.message || String(appCheckErr),
          });
          res.status(401).json({
            error: 'INVALID_APP_CHECK_TOKEN',
            message: 'Uygulama güvenlik doğrulaması (App Check) başarısız oldu. Lütfen tekrar deneyin.',
          });
          return;
        }
      } else if (enforceAppCheck) {
        logger.warn('Rejected request missing required Firebase App Check token');
        res.status(401).json({
          error: 'MISSING_APP_CHECK_TOKEN',
          message: 'Uygulama güvenlik doğrulaması eksik. Lütfen Soyken Play uygulamasını güncelleyin.',
        });
        return;
      }

      // Layer 2: Independent Firebase Auth ID token verification (always required)
      const authHeader = req.get('Authorization') || '';
      if (!authHeader.startsWith('Bearer ')) {
        res.status(401).json({ error: 'MISSING_AUTH_TOKEN' });
        return;
      }

      const idToken = authHeader.slice('Bearer '.length).trim();
      if (!idToken) {
        res.status(401).json({ error: 'INVALID_AUTH_TOKEN' });
        return;
      }

      let decodedToken;
      try {
        decodedToken = await admin.auth().verifyIdToken(idToken, true);
      } catch (authErr) {
        logger.warn('Rejected invalid Firebase Auth ID token', {
          code: authErr?.code || 'unknown',
          message: authErr?.message || String(authErr),
        });
        res.status(401).json({
          error: 'INVALID_AUTH_TOKEN',
          message: 'Oturum doğrulaması geçersiz. Lütfen Google hesabınızla yeniden giriş yapın.',
        });
        return;
      }
      const uid = decodedToken.uid;

      if (!appCheckToken) {
        logger.warn('missing_app_check_token_legacy_client', {
          uid,
          enforceAppCheck: false,
        });
      } else {
        logger.info('Verified Firebase App Check and Auth tokens', {
          uid,
          appCheckAppId,
        });
      }

      const body = req.body && typeof req.body === 'object' ? req.body : {};
      const purchaseToken =
        typeof body.purchaseToken === 'string' ? body.purchaseToken.trim() : '';
      const requestedProductId =
        typeof body.productId === 'string' ? body.productId.trim() : SUBSCRIPTION_PRODUCT_ID;

      if (!purchaseToken || purchaseToken.length < 16 || purchaseToken.length > 4096) {
        res.status(400).json({ error: 'INVALID_PURCHASE_TOKEN' });
        return;
      }

      if (requestedProductId !== SUBSCRIPTION_PRODUCT_ID) {
        res.status(400).json({ error: 'UNSUPPORTED_PRODUCT_ID' });
        return;
      }

      // Prevent cross-account purchase token replay
      const tokenHash = hashPurchaseToken(purchaseToken);
      const existingMapping = await db
        .collection('play_purchase_tokens')
        .doc(tokenHash)
        .get();
      if (existingMapping.exists && existingMapping.data()?.uid && existingMapping.data().uid !== uid) {
        logger.warn('Purchase token already bound to another account', {
          requestUid: uid,
          tokenHashPrefix: tokenHash.slice(0, 12),
        });
        res.status(409).json({
          error: 'TOKEN_OWNED_BY_ANOTHER_ACCOUNT',
          message: 'Bu abonelik başka bir Google hesabına tanımlıdır.',
        });
        return;
      }

      const publisher = createAndroidPublisherClient(playServiceAccountJson.value());
      const verified = await verifyAndPersistSubscription({
        uid,
        purchaseToken,
        publisher,
        source: 'client_verify',
      });

      res.status(200).json({
        active: verified.active,
        subscriptionState: verified.subscriptionState,
        productId: verified.productId,
        basePlanId: verified.basePlanId,
        expiryTimeMillis: verified.expiryTimeMillis,
        autoRenewing: verified.autoRenewing,
        orderId: verified.orderId,
        verifiedAtMillis: Date.now(),
      });
    } catch (err) {
      const status = err?.code === 404 || err?.status === 404 ? 404 : 500;
      logger.error('verifyPlaySubscription failed', {
        status,
        message: err?.message || String(err),
      });
      res.status(status).json({
        error: status === 404 ? 'PURCHASE_NOT_FOUND' : 'VERIFICATION_FAILED',
        message: 'Abonelik doğrulaması tamamlanamadı.',
      });
    }
  },
);

/**
 * Pub/Sub listener for Google Play Real-Time Developer Notifications (RTDN).
 * Automatically updates `/users/{uid}/entitlements/premium` when Google Play emits
 * renewals, cancellations, grace period, account hold, recovery, revocation, or expiration.
 */
exports.playRtdnWebhook = onMessagePublished(
  {
    topic: RTDN_TOPIC_NAME,
    region: 'us-central1',
    secrets: [playServiceAccountJson],
    timeoutSeconds: 30,
    memory: '256MiB',
  },
  async (event) => {
    const rawData = event.data?.message?.data;
    if (!rawData) {
      logger.warn('Received empty RTDN Pub/Sub payload');
      return;
    }

    let notification;
    try {
      const jsonStr = Buffer.from(rawData, 'base64').toString('utf8');
      notification = JSON.parse(jsonStr);
    } catch (parseErr) {
      logger.error('Failed to decode RTDN Pub/Sub JSON', {
        message: parseErr?.message || String(parseErr),
      });
      return;
    }

    if (notification.packageName !== PACKAGE_NAME) {
      logger.warn('Ignoring RTDN for unrelated package', {
        packageName: notification.packageName,
      });
      return;
    }

    if (notification.testNotification) {
      logger.info('Received Google Play RTDN test notification', {
        version: notification.testNotification.version,
      });
      return;
    }

    const subNotification = notification.subscriptionNotification;
    if (!subNotification || !subNotification.purchaseToken) {
      logger.info('Ignoring non-subscription RTDN event');
      return;
    }

    const purchaseToken = subNotification.purchaseToken;
    const notificationType = subNotification.notificationType;
    const tokenHash = hashPurchaseToken(purchaseToken);

    const publisher = createAndroidPublisherClient(playServiceAccountJson.value());

    let uid = null;
    const mappingSnap = await db.collection('play_purchase_tokens').doc(tokenHash).get();
    if (mappingSnap.exists && mappingSnap.data()?.uid) {
      uid = mappingSnap.data().uid;
    } else {
      // Fallback: check obfuscatedExternalAccountId attached during BillingFlowParams
      const subResp = await publisher.purchases.subscriptionsv2.get({
        packageName: PACKAGE_NAME,
        token: purchaseToken,
      });
      const candidateUid =
        subResp.data?.externalAccountIdentifiers?.obfuscatedExternalAccountId || '';
      if (candidateUid) {
        uid = candidateUid;
      }
    }

    if (!uid) {
      logger.warn('Could not resolve Firebase UID for RTDN event', {
        notificationType,
        tokenHashPrefix: tokenHash.slice(0, 12),
      });
      return;
    }

    const verified = await verifyAndPersistSubscription({
      uid,
      purchaseToken,
      publisher,
      source: `rtdn_${notificationType}`,
    });

    logger.info('Synchronized RTDN subscription state', {
      uid,
      notificationType,
      active: verified.active,
      subscriptionState: verified.subscriptionState,
      basePlanId: verified.basePlanId,
    });
  },
);
