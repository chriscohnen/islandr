-- V82: peer-roadwarrior-badge (loop/TASK.md) — marks a peer as currently
-- traveling with its owner, often on untrusted networks (hotel wifi etc.).
-- Orthogonal to deviceType: a traveling laptop is a roadwarrior just as much
-- as a phone, and the flag is meant to be toggled on and off as the person's
-- situation changes, not derived from a fixed device property.
ALTER TABLE peers ADD COLUMN is_roadwarrior INTEGER NOT NULL DEFAULT 0;
