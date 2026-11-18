import crypto from 'node:crypto';
import dns from 'node:dns/promises';
import net from 'node:net';
import { initializeApp, cert } from 'firebase-admin/app';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';

const DEFAULT_DATABASE_ID = 'ai-studio-android-supalive-275ae400-2b3e-4882-9b60-e2892d0b529a';
const MAX_RETRIES = 2;
const BASE_BACKOFF_MS = 400;
const REQUEST_TIMEOUT_MS = 10_000;
const SLOW_LATENCY_WARNING_MS = 2500;
const SUSPECTED_PAUSED_THRESHOLD = 3;
const MAX_BODY_CHARS = 32_768;

const SUPABASE_URL_REGEX = /^https:\/\/([a-z0-9-]+)\.supabase\.co$/;

function requireEnv(name) {
  const value = process.env[name];
  if (!value || !value.trim()) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

function sanitizeLogMessage(raw) {
  if (!raw) return '';
  return String(raw)
    .replace(/sb_publishable_[A-Za-z0-9_\-.]+/g, 'sb_publishable_***')
    .replace(/eyJ[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+\.[A-Za-z0-9_\-]+/g, 'jwt_***')
    .replace(/Bearer\s+[A-Za-z0-9_\-.]+/gi, 'Bearer ***')
    .slice(0, 480);
}

function isPrivateOrBlockedIp(ip) {
  const version = net.isIP(ip);
  if (version === 4) {
    const parts = ip.split('.').map(Number);
    const [a, b] = parts;
    if (a === 0 || a === 10 || a === 127) return true;
    if (a === 169 && b === 254) return true;
    if (a === 172 && b >= 16 && b <= 31) return true;
    if (a === 192 && b === 168) return true;
    if (a === 100 && b >= 64 && b <= 127) return true;
    if (a >= 224) return true;
    return false;
  }
  if (version === 6) {
    const lower = ip.toLowerCase();
    if (lower === '::1' || lower === '::') return true;
    if (lower.startsWith('fe80:') || lower.startsWith('fc') || lower.startsWith('fd')) return true;
    if (lower.startsWith('::ffff:')) {
      return isPrivateOrBlockedIp(lower.slice(7));
    }
    return false;
  }
  return true;
}

async function validateSupabaseUrlAndDns(rawUrl) {
  const trimmed = String(rawUrl || '').trim().replace(/\/+$/, '');
  const match = SUPABASE_URL_REGEX.exec(trimmed);
  if (!match) {
    return { valid: false, reason: 'Invalid Supabase URL format.' };
  }
  const host = `${match[1]}.supabase.co`;
  try {
    const records = await dns.lookup(host, { all: true });
    if (!records.length) {
      return { valid: false, reason: 'DNS resolution returned no addresses.' };
    }
    for (const rec of records) {
      if (isPrivateOrBlockedIp(rec.address)) {
        return { valid: false, reason: 'Blocked private or reserved IP address.' };
      }
    }
    return { valid: true, normalizedUrl: trimmed };
  } catch (err) {
    return { valid: false, reason: sanitizeLogMessage(err.message || 'DNS lookup failed') };
  }
}

function decryptServerKey(encodedPayload, privateKeyPem) {
  const parts = String(encodedPayload || '').split(':');
  if (parts.length !== 2 || parts[0] !== 'v1') {
    throw new Error('Unsupported server key payload format.');
  }
  const encryptedBuffer = Buffer.from(parts[1], 'base64');
  const decrypted = crypto.privateDecrypt(
    {
      key: privateKeyPem,
      padding: crypto.constants.RSA_PKCS1_OAEP_PADDING,
      oaepHash: 'sha256',
    },
    encryptedBuffer,
  );
  return decrypted.toString('utf8');
}

function isMissingTableResponse(bodyText) {
  const lower = String(bodyText || '').toLowerCase();
  return (
    lower.includes('pgrst205') ||
    lower.includes('42p01') ||
    lower.includes('does not exist') ||
    lower.includes('could not find the table')
  );
}

function evaluate200Response(bodyText, elapsedMs, retryCount) {
  try {
    const parsed = JSON.parse(bodyText);
    if (!Array.isArray(parsed)) {
      throw new Error('Expected JSON array');
    }
    const foundRow = parsed.some((item) => item && item.id === 1);
    if (!foundRow) {
      return {
        success: false,
        resultingStatus: 'configuration_error',
        httpStatus: 200,
        responseTimeMs: elapsedMs,
        retryCount,
        errorCode: 'MISSING_SEED_ROW',
        errorMessage: 'Table public.supalive_health responded, but seed row id=1 is missing (or hidden by RLS).',
      };
    }
    const status = elapsedMs > SLOW_LATENCY_WARNING_MS || retryCount > 0 ? 'warning' : 'healthy';
    return {
      success: true,
      resultingStatus: status,
      httpStatus: 200,
      responseTimeMs: elapsedMs,
      retryCount,
      errorCode: '',
      errorMessage: status === 'warning' ? `High latency (${elapsedMs}ms) or transient retry occurred.` : '',
    };
  } catch {
    return {
      success: false,
      resultingStatus: 'configuration_error',
      httpStatus: 200,
      responseTimeMs: elapsedMs,
      retryCount,
      errorCode: 'INVALID_JSON_PAYLOAD',
      errorMessage: 'Expected JSON array from /rest/v1/supalive_health.',
    };
  }
}

function isTransientFailure(outcome) {
  if (outcome.errorCode === 'TIMEOUT' || outcome.errorCode === 'NETWORK_IO') return true;
  return outcome.httpStatus === 429 || (outcome.httpStatus >= 500 && outcome.httpStatus <= 599);
}

async function executeSingleAttempt(endpoint, publishableKey, retryCount, previousConsecutiveFailures) {
  const startNs = process.hrtime.bigint();
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    const response = await fetch(endpoint, {
      method: 'GET',
      headers: {
        apikey: publishableKey,
        Accept: 'application/json',
      },
      redirect: 'manual',
      signal: controller.signal,
    });
    const elapsedMs = Math.max(1, Number((process.hrtime.bigint() - startNs) / 1_000_000n));
    const status = response.status;
    const rawBody = await response.text();
    const bodyText = rawBody.slice(0, MAX_BODY_CHARS);

    if (status === 200) {
      return evaluate200Response(bodyText, elapsedMs, retryCount);
    }
    if (status === 401 || status === 403) {
      return {
        success: false,
        resultingStatus: 'credential_error',
        httpStatus: status,
        responseTimeMs: elapsedMs,
        retryCount,
        errorCode: `HTTP_${status}`,
        errorMessage: `Publishable key rejected or RLS select policy missing (HTTP ${status}).`,
      };
    }
    if (status === 404 || isMissingTableResponse(bodyText)) {
      return {
        success: false,
        resultingStatus: 'configuration_error',
        httpStatus: status,
        responseTimeMs: elapsedMs,
        retryCount,
        errorCode: 'MISSING_HEALTH_TABLE',
        errorMessage: 'Table public.supalive_health not found or not exposed via PostgREST.',
      };
    }
    if (status === 429 || status >= 500) {
      const nextFailures = previousConsecutiveFailures + 1;
      const resultingStatus = nextFailures >= SUSPECTED_PAUSED_THRESHOLD ? 'suspected_paused' : 'unreachable';
      return {
        success: false,
        resultingStatus,
        httpStatus: status,
        responseTimeMs: elapsedMs,
        retryCount,
        errorCode: `HTTP_${status}`,
        errorMessage: sanitizeLogMessage(`Supabase API returned HTTP ${status}.`),
      };
    }
    return {
      success: false,
      resultingStatus: 'configuration_error',
      httpStatus: status,
      responseTimeMs: elapsedMs,
      retryCount,
      errorCode: `HTTP_${status}`,
      errorMessage: sanitizeLogMessage(`Unexpected response HTTP ${status}.`),
    };
  } catch (err) {
    const elapsedMs = Math.max(1, Number((process.hrtime.bigint() - startNs) / 1_000_000n));
    const nextFailures = previousConsecutiveFailures + 1;
    const resultingStatus = nextFailures >= SUSPECTED_PAUSED_THRESHOLD ? 'suspected_paused' : 'unreachable';
    const isTimeout = err?.name === 'AbortError';
    return {
      success: false,
      resultingStatus,
      httpStatus: 0,
      responseTimeMs: elapsedMs,
      retryCount,
      errorCode: isTimeout ? 'TIMEOUT' : 'NETWORK_IO',
      errorMessage: isTimeout
        ? 'Request timed out after 10s.'
        : sanitizeLogMessage(err?.message || 'Network error reaching Supabase.'),
    };
  } finally {
    clearTimeout(timer);
  }
}

async function checkHealth(projectUrl, publishableKey, previousConsecutiveFailures) {
  const validation = await validateSupabaseUrlAndDns(projectUrl);
  if (!validation.valid) {
    return {
      success: false,
      resultingStatus: 'configuration_error',
      httpStatus: 0,
      responseTimeMs: 0,
      retryCount: 0,
      errorCode: 'SSRF_OR_URL_BLOCKED',
      errorMessage: sanitizeLogMessage(validation.reason),
    };
  }

  const endpoint = `${validation.normalizedUrl}/rest/v1/supalive_health?id=eq.1&select=id`;
  let lastOutcome = null;

  for (let attempt = 0; attempt <= MAX_RETRIES; attempt++) {
    const outcome = await executeSingleAttempt(
      endpoint,
      publishableKey,
      attempt,
      previousConsecutiveFailures,
    );
    lastOutcome = outcome;

    if (outcome.success || !isTransientFailure(outcome)) {
      return outcome;
    }

    if (attempt < MAX_RETRIES) {
      const jitter = 50 + Math.floor(Math.random() * 200);
      const backoffMs = BASE_BACKOFF_MS * (1 << attempt) + jitter;
      await new Promise((resolve) => setTimeout(resolve, backoffMs));
    }
  }

  return lastOutcome;
}

function advanceScheduleWithoutDrift(previousScheduledEpochMs, nowEpochMs, intervalHours = 12) {
  const stepMs = Math.max(1, intervalHours) * 3_600_000;
  if (!previousScheduledEpochMs || previousScheduledEpochMs <= 0) {
    return nowEpochMs + stepMs;
  }
  if (previousScheduledEpochMs > nowEpochMs) {
    return previousScheduledEpochMs;
  }
  const elapsed = nowEpochMs - previousScheduledEpochMs;
  const steps = Math.floor(elapsed / stepMs) + 1;
  return previousScheduledEpochMs + steps * stepMs;
}

function notificationTypeForStatus(status) {
  switch (status) {
    case 'credential_error':
      return 'credential_error';
    case 'configuration_error':
      return 'configuration_error';
    case 'suspected_paused':
      return 'suspected_paused';
    default:
      return 'health_alert';
  }
}

async function main() {
  const serviceAccount = JSON.parse(requireEnv('FIREBASE_SERVICE_ACCOUNT'));
  const privateKeyPem = requireEnv('SUPALIVE_RSA_PRIVATE_KEY');
  const databaseId = process.env.FIRESTORE_DATABASE_ID || DEFAULT_DATABASE_ID;

  const app = initializeApp({ credential: cert(serviceAccount) });
  const db = getFirestore(app, databaseId);

  const now = Date.now();
  const snapshot = await db
    .collectionGroup('projects')
    .where('monitoringEnabled', '==', true)
    .where('nextCheckEpochMs', '<=', now)
    .get();

  console.log(`Found ${snapshot.size} due project(s) at ${new Date(now).toISOString()}.`);

  let checkedCount = 0;
  let skippedCount = 0;

  for (const projectDoc of snapshot.docs) {
    const project = projectDoc.data();
    const uid = project.userId;
    const projectId = projectDoc.id;

    if (!uid) {
      skippedCount++;
      continue;
    }

    if (!project.publishableKeyServerEncrypted) {
      console.log(`Skipping ${projectId} (${project.projectName}): no server-encrypted key yet (re-save in app).`);
      skippedCount++;
      continue;
    }

    let publishableKey;
    try {
      publishableKey = decryptServerKey(project.publishableKeyServerEncrypted, privateKeyPem);
    } catch (err) {
      console.error(`Failed to decrypt key for ${projectId}: ${err.message}`);
      skippedCount++;
      continue;
    }

    const prevFailures = Number(project.consecutiveFailures || 0);
    const outcome = await checkHealth(project.projectUrl, publishableKey, prevFailures);
    const checkNow = Date.now();
    const nextCheckEpochMs = advanceScheduleWithoutDrift(
      Number(project.nextCheckEpochMs || 0),
      checkNow,
      Number(project.intervalHours || 12),
    );
    const newFailures = outcome.success ? 0 : prevFailures + 1;
    const lastSuccessEpochMs = outcome.success ? checkNow : Number(project.lastSuccessEpochMs || 0);

    const userRef = db.collection('users').doc(uid);
    const logId = `log_${crypto.randomUUID().replace(/-/g, '').slice(0, 16)}`;

    await Promise.all([
      projectDoc.ref.update({
        healthStatus: outcome.resultingStatus,
        nextCheckEpochMs,
        lastCheckedEpochMs: checkNow,
        lastSuccessEpochMs,
        lastLatencyMs: outcome.responseTimeMs,
        consecutiveFailures: newFailures,
        lastSanitizedError: outcome.errorMessage,
        updatedAt: FieldValue.serverTimestamp(),
      }),
      userRef.collection('logs').doc(logId).set({
        userId: uid,
        projectId,
        projectName: project.projectName || project.projectRef || projectId,
        checkType: 'scheduled',
        result: outcome.success ? 'success' : 'failure',
        resultingStatus: outcome.resultingStatus,
        httpStatus: outcome.httpStatus,
        responseTimeMs: outcome.responseTimeMs,
        errorCode: outcome.errorCode,
        errorMessage: outcome.errorMessage,
        retryCount: outcome.retryCount,
        checkedAtEpochMs: checkNow,
        createdAt: FieldValue.serverTimestamp(),
      }),
    ]);

    const userSnap = await userRef.get();
    const profile = userSnap.exists ? userSnap.data() : null;
    const inAppAlertsEnabled = profile?.inAppAlertsEnabled ?? true;
    const threshold = Math.max(1, Number(profile?.failureThreshold || 2));

    if (inAppAlertsEnabled) {
      if (!outcome.success && newFailures >= threshold) {
        const notifId = `notif_${crypto.randomUUID().replace(/-/g, '').slice(0, 16)}`;
        await userRef.collection('notifications').doc(notifId).set({
          userId: uid,
          projectId,
          projectName: project.projectName || projectId,
          notificationType: notificationTypeForStatus(outcome.resultingStatus),
          title: `${project.projectName}: ${outcome.resultingStatus}`,
          message: outcome.errorMessage || `Scheduled health check failed (${outcome.resultingStatus}).`,
          readFlag: false,
          createdAtEpochMs: checkNow,
          createdAt: FieldValue.serverTimestamp(),
        });
      } else if (outcome.success && prevFailures >= threshold) {
        const notifId = `notif_${crypto.randomUUID().replace(/-/g, '').slice(0, 16)}`;
        await userRef.collection('notifications').doc(notifId).set({
          userId: uid,
          projectId,
          projectName: project.projectName || projectId,
          notificationType: 'recovered',
          title: `${project.projectName}: Recovered`,
          message: `Database responded in ${outcome.responseTimeMs}ms after ${prevFailures} failure(s).`,
          readFlag: false,
          createdAtEpochMs: checkNow,
          createdAt: FieldValue.serverTimestamp(),
        });
      }
    }

    checkedCount++;
    console.log(
      `Checked ${project.projectName} (${projectId}): status=${outcome.resultingStatus}, http=${outcome.httpStatus}, latency=${outcome.responseTimeMs}ms`,
    );
  }

  console.log(`Done. Checked=${checkedCount}, Skipped=${skippedCount}.`);
}

main().catch((err) => {
  console.error('Fatal error in health-check runner:', err);
  process.exit(1);
});
