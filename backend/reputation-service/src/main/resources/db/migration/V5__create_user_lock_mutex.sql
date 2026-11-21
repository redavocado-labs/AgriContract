-- Serializes all lock lifecycle operations without requiring a projection row.
CREATE TABLE user_lock_mutex (
    user_id CHAR(36) PRIMARY KEY
);
