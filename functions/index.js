const crypto = require("node:crypto");
const { initializeApp } = require("firebase-admin/app");
const { getDatabase } = require("firebase-admin/database");
const { defineSecret } = require("firebase-functions/params");
const { HttpsError, onCall } = require("firebase-functions/v2/https");

initializeApp();

const wipePassword = defineSecret("CHAT_DATA_WIPE_PASSWORD");
const attemptLimit = 5;
const attemptWindowMs = 15 * 60 * 1000;

async function checkAttemptLimit(ipAddress) {
  const addressHash = crypto.createHash("sha256").update(ipAddress).digest("hex");
  const attemptsRef = getDatabase().ref(`chat/admin_wipe_attempts/${addressHash}`);
  const now = Date.now();
  const result = await attemptsRef.transaction((current) => {
    if (current && current.lockedUntil > now) {
      return;
    }

    const sameWindow = current && now - current.windowStarted < attemptWindowMs;
    const attempts = sameWindow ? current.attempts + 1 : 1;
    return {
      attempts,
      windowStarted: sameWindow ? current.windowStarted : now,
      lockedUntil: attempts >= attemptLimit ? now + attemptWindowMs : 0,
    };
  });

  if (!result.committed) {
    throw new HttpsError("resource-exhausted", "Too many deletion attempts. Try again later.");
  }
}

function passwordMatches(candidate) {
  if (typeof candidate !== "string") {
    return false;
  }
  const expected = Buffer.from(wipePassword.value(), "utf8");
  const supplied = Buffer.from(candidate, "utf8");
  return expected.length >= 16 &&
    expected.length === supplied.length &&
    crypto.timingSafeEqual(expected, supplied);
}

exports.wipeChatData = onCall(
  { region: "us-central1", secrets: [wipePassword] },
  async (request) => {
    const ipAddress = request.rawRequest.ip || request.rawRequest.socket.remoteAddress || "unknown";
    await checkAttemptLimit(ipAddress);
    if (!passwordMatches(request.data?.password)) {
      throw new HttpsError("permission-denied", "The deletion password is incorrect.");
    }

    await getDatabase().ref("chat/messages").remove();

    return { deleted: true };
  }
);
