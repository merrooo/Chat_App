# Chat-APP

## Protected Firebase data wipe

The app's **Delete all messages** action is destructive. It calls the
`wipeChatData` Cloud Function, which verifies a signed-in Firebase Auth account against
an allowlisted administrator UID and deletes only the Realtime Database
`/chat/messages` node. User profiles, groups, contacts, and other database paths are
preserved. Attachments currently stored inside message records are removed with them.

To enable the protected delete action:

1. Generate a strong password locally, for example:
   `node -e "console.log(require('node:crypto').randomBytes(24).toString('base64url'))"`
2. Set it in Firebase Secret Manager:
   `firebase functions:secrets:set CHAT_DATA_WIPE_PASSWORD`
   Enter the generated value at the prompt; do not put it in source code or Realtime Database.
3. Deploy the function with Firebase CLI from the repository root:
   `firebase deploy --only functions`
4. Cloud Functions deployment may require the Firebase project to use the Blaze plan.

The password is verified by the server, requires at least 16 bytes, and is rate-limited
to five attempts per client IP per 15-minute window. The function deletes only
`/chat/messages`; all other database paths are preserved.