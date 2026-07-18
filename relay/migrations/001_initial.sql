PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS relay_meta (
  singleton INTEGER PRIMARY KEY CHECK (singleton = 1),
  schema_version INTEGER NOT NULL,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS accounts (
  account_id TEXT PRIMARY KEY,
  auth_public_key BLOB NOT NULL UNIQUE,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_challenges (
  challenge_id TEXT PRIMARY KEY,
  auth_public_key BLOB NOT NULL,
  challenge BLOB NOT NULL,
  expires_at INTEGER NOT NULL,
  used_at INTEGER,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_auth_challenges_expiry ON auth_challenges(expires_at);

CREATE TABLE IF NOT EXISTS auth_tokens (
  token_hash BLOB PRIMARY KEY,
  account_id TEXT NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE,
  device_id TEXT,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER,
  created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_auth_tokens_account ON auth_tokens(account_id);
CREATE INDEX IF NOT EXISTS idx_auth_tokens_device ON auth_tokens(account_id, device_id);

CREATE TABLE IF NOT EXISTS devices (
  account_id TEXT NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE,
  device_id TEXT NOT NULL,
  content_public_key BLOB NOT NULL,
  device_type TEXT NOT NULL CHECK (device_type IN ('agent', 'android')),
  last_seen_at INTEGER NOT NULL,
  revoked_at INTEGER,
  created_at INTEGER NOT NULL,
  PRIMARY KEY (account_id, device_id)
);
CREATE INDEX IF NOT EXISTS idx_devices_active ON devices(account_id, revoked_at);

CREATE TABLE IF NOT EXISTS envelopes (
  row_id INTEGER PRIMARY KEY AUTOINCREMENT,
  id TEXT NOT NULL UNIQUE,
  account_id TEXT NOT NULL REFERENCES accounts(account_id) ON DELETE CASCADE,
  sender_device_id TEXT NOT NULL,
  target_id TEXT NOT NULL,
  stream_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  expires_at INTEGER,
  key_id TEXT NOT NULL,
  cipher_bundle TEXT NOT NULL,
  received_at INTEGER NOT NULL,
  UNIQUE (account_id, sender_device_id, stream_id, seq),
  FOREIGN KEY (account_id, sender_device_id) REFERENCES devices(account_id, device_id)
);
CREATE INDEX IF NOT EXISTS idx_envelopes_stream ON envelopes(account_id, sender_device_id, stream_id, seq);

CREATE TABLE IF NOT EXISTS deliveries (
  envelope_id TEXT NOT NULL REFERENCES envelopes(id) ON DELETE CASCADE,
  account_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  acked_at INTEGER,
  PRIMARY KEY (envelope_id, device_id),
  FOREIGN KEY (account_id, device_id) REFERENCES devices(account_id, device_id)
);
CREATE INDEX IF NOT EXISTS idx_deliveries_outbox ON deliveries(account_id, device_id, acked_at, envelope_id);

CREATE TABLE IF NOT EXISTS stream_acks (
  account_id TEXT NOT NULL,
  receiver_device_id TEXT NOT NULL,
  sender_device_id TEXT NOT NULL,
  stream_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (account_id, receiver_device_id, sender_device_id, stream_id),
  FOREIGN KEY (account_id, receiver_device_id) REFERENCES devices(account_id, device_id)
);

CREATE TABLE IF NOT EXISTS tombstone_receipts (
  account_id TEXT NOT NULL,
  envelope_id TEXT NOT NULL REFERENCES envelopes(id) ON DELETE CASCADE,
  device_id TEXT NOT NULL,
  received_at INTEGER NOT NULL,
  PRIMARY KEY (envelope_id, device_id),
  FOREIGN KEY (account_id, device_id) REFERENCES devices(account_id, device_id)
);
